/*
 * Copyright 2023 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.cli.executor;

import com.google.common.base.Joiner;
import io.pixelsdb.pixels.cli.Main;
import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.common.exception.IndexException;
import io.pixelsdb.pixels.common.exception.RetinaException;
import io.pixelsdb.pixels.common.index.IndexOption;
import io.pixelsdb.pixels.common.index.MainIndex;
import io.pixelsdb.pixels.common.index.service.IndexService;
import io.pixelsdb.pixels.common.index.service.IndexServiceProvider;
import io.pixelsdb.pixels.common.metadata.MetadataService;
import io.pixelsdb.pixels.common.metadata.domain.Compact;
import io.pixelsdb.pixels.common.metadata.domain.File;
import io.pixelsdb.pixels.common.metadata.domain.Layout;
import io.pixelsdb.pixels.common.metadata.domain.Path;
import io.pixelsdb.pixels.common.metadata.domain.Table;
import io.pixelsdb.pixels.common.physical.Status;
import io.pixelsdb.pixels.common.physical.Storage;
import io.pixelsdb.pixels.common.physical.StorageFactory;
import io.pixelsdb.pixels.common.retina.RetinaService;
import io.pixelsdb.pixels.common.utils.ConfigFactory;
import io.pixelsdb.pixels.common.utils.NetUtils;
import io.pixelsdb.pixels.common.utils.PixelsFileNameUtils;
import io.pixelsdb.pixels.core.compactor.CompactLayout;
import io.pixelsdb.pixels.core.compactor.PixelsCompactor;
import io.pixelsdb.pixels.index.IndexProto;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * @author hank
 * @create 2023-04-16
 */
public class CompactExecutor implements CommandExecutor
{
    private final RetinaService retinaService = RetinaService.Instance();

    @Override
    public void execute(Namespace ns, String command) throws Exception
    {
        String schemaName = ns.getString("schema");
        String tableName = ns.getString("table");
        String naive = ns.getString("naive");
        int threadNum = Integer.parseInt(ns.getString("concurrency"));
        ExecutorService compactExecutor = Executors.newFixedThreadPool(threadNum);

        // get compact layout
        MetadataService metadataService = MetadataService.Instance();
        List<Layout> layouts = metadataService.getLayouts(schemaName, tableName);
        System.out.println("existing number of layouts: " + layouts.size());
        Layout layout = null;
        for (Layout layout1 : layouts)
        {
            if (layout1.isWritable())
            {
                layout = layout1;
                break;
            }
        }

        requireNonNull(layout, String.format("writable layout is not found for table '%s.%s'.", schemaName, tableName));
        Compact compact = layout.getCompact();
        int numRowGroupInBlock = compact.getNumRowGroupInFile();
        int numColumn = compact.getNumColumn();
        CompactLayout compactLayout;
        if (naive.equalsIgnoreCase("yes") || naive.equalsIgnoreCase("y"))
        {
            compactLayout = CompactLayout.buildNaive(numRowGroupInBlock, numColumn);
        }
        else
        {
            compactLayout = CompactLayout.fromCompact(compact);
        }

        // get input file paths
        ConfigFactory configFactory = ConfigFactory.Instance();
        Main.validateOrderedOrCompactPaths(layout.getOrderedPathUris());
        Main.validateOrderedOrCompactPaths(layout.getCompactPathUris());
        // PIXELS-399: it is not a problem if the order or compact path contains multiple directories
        Storage orderStorage = StorageFactory.Instance().getStorage(layout.getOrderedPathUris()[0]);
        Storage compactStorage = StorageFactory.Instance().getStorage(layout.getCompactPathUris()[0]);
        long blockSize = Long.parseLong(configFactory.getProperty("block.size"));
        short replication = Short.parseShort(configFactory.getProperty("block.replication"));

        // Tag updates are opt-in because the existing compaction command must remain
        // compatible with tables that do not use the DML tag index.
        String tagColumnName = configFactory.getProperty("dml.tag.column");
        final boolean useTagCompactor = tagColumnName != null && !tagColumnName.trim().isEmpty();
        final long primaryTableId;
        final long primaryIndexId;
        final IndexService indexService;
        final MainIndex mainIndex;
        final List<IndexProto.TagIndexEntry> tagEntries;
        if (useTagCompactor)
        {
            Table table = metadataService.getTable(schemaName, tableName);
            io.pixelsdb.pixels.common.metadata.domain.SinglePointIndex primaryIndexMetadata =
                    metadataService.getPrimaryIndex(table.getId());
            if (primaryIndexMetadata == null)
            {
                throw new MetadataException("tag-aware compaction requires a primary index");
            }
            primaryTableId = table.getId();
            primaryIndexId = primaryIndexMetadata.getId();
            indexService = IndexServiceProvider.getService(IndexServiceProvider.ServiceMode.rpc);
            mainIndex = null;
            tagEntries = indexService.listTagIndexEntries(table.getId(), primaryIndexId);
            System.out.println("tag-aware compaction enabled for column '" + tagColumnName + "' with " +
                    tagEntries.size() + " tag entries.");
        }
        else
        {
            primaryTableId = -1;
            primaryIndexId = -1;
            indexService = null;
            mainIndex = null;
            tagEntries = java.util.Collections.emptyList();
        }

        // Issue #998: compact need to exclude empty files
        List<Status> statuses = orderStorage.listStatus(layout.getOrderedPathUris());
        Iterator<Status> statusIterator = statuses.iterator();
        while (statusIterator.hasNext())
        {
            if (metadataService.getFileType(statusIterator.next().getPath()) != File.Type.REGULAR)
            {
                statusIterator.remove();
            }
        }

        // Issue #1305: obtain local hostname for the unified compact file naming.
        String hostName = NetUtils.getLocalHostName();

        /**
         * Issue #1305:
         * Group files by virtualNodeId before compaction.
         * GC-eligible files (retina/ordered/compact) carry a real virtualNodeId and must
         * not be mixed across groups to preserve create_ts/delete_ts monotonicity.
         * single-type files fall into the VNODE_ID_NONE (-1) bucket and are compacted freely.
         * copy-type files and unrecognised-format files are skipped.
         */
        Map<Integer, List<Status>> groupedStatuses = new LinkedHashMap<>();
        for (Status status : statuses)
        {
            String path = status.getPath();
            PixelsFileNameUtils.PxlFileType fileType = PixelsFileNameUtils.extractFileType(path);
            if (fileType == null)
            {
                System.err.println("Skipping file with unrecognized naming format: " + path);
                continue;
            }
            if (fileType == PixelsFileNameUtils.PxlFileType.COPY)
            {
                System.err.println("Skipping copy file (test/benchmark data, not for compaction): " + path);
                continue;
            }
            int vNodeId = PixelsFileNameUtils.extractVirtualNodeId(path);
            groupedStatuses.computeIfAbsent(vNodeId, k -> new ArrayList<>()).add(status);
        }

        List<Path> targetPaths = layout.getCompactPaths();
        ConcurrentLinkedQueue<CompactionResult> compactionResults = new ConcurrentLinkedQueue<>();
        List<Future<?>> compactionTasks = new ArrayList<>();
        int targetPathId = 0;

        // Issue #1305: iterate over each virtualNodeId group independently to preserve
        // the monotonicity invariants required by Storage GC.
        long startTime = System.currentTimeMillis();
        int thdId = 0;
        for (Map.Entry<Integer, List<Status>> entry : groupedStatuses.entrySet())
        {
            int vNodeId = entry.getKey();
            List<Status> groupStatuses = entry.getValue();
            int groupSize = groupStatuses.size();

            for (int i = 0; i < groupSize; ++thdId)
            {
                /**
                 * Issue #160:
                 * Compact the tail files that can not fulfill the compactLayout
                 * defined in the metadata.
                 * Note that if (i + numRowGroupInBlock == statues.size()),
                 * then the remaining files are not tail files.
                 *
                 * Here we set numRowGroupInBlock to the number of tail files,
                 * and rebuild a pure compactLayout for the tail files as the
                 * compactLayout in metadata does not work for the tail files.
                 */
                // Issue #1305: use local batchSize/batchLayout instead of mutating
                // numRowGroupInBlock/compactLayout, so they stay unchanged across batches and groups.
                int batchSize = Math.min(numRowGroupInBlock, groupSize - i);
                CompactLayout batchLayout = (batchSize < numRowGroupInBlock)
                        ? CompactLayout.buildPure(batchSize, numColumn)
                        : compactLayout;

                List<String> sourcePaths = new ArrayList<>();
                for (int j = 0; j < batchSize; ++j)
                {
                    if (!groupStatuses.get(i + j).getPath().endsWith("/"))
                    {
                        sourcePaths.add(groupStatuses.get(i + j).getPath());
                    }
                }

                Path targetPath = targetPaths.get(targetPathId++);
                String targetDirPath = targetPath.getUri();
                targetPathId %= targetPaths.size();
                if (!targetDirPath.endsWith("/"))
                {
                    targetDirPath += "/";
                }
                // Issue #1305: use unified naming format (hostName + vNodeId) instead of DateUtil timestamp only.
                String targetFileName = PixelsFileNameUtils.buildCompactFileName(hostName, vNodeId);
                String targetFilePath = targetDirPath + targetFileName;

                System.out.println("(" + thdId + ") vNodeId=" + vNodeId + ", " + sourcePaths.size() +
                        " ordered files to be compacted into '" + targetFilePath + "'.");

                PixelsCompactor.Builder compactorBuilder = PixelsCompactor.newBuilder()
                        .setSourcePaths(sourcePaths)
                    /**
                     * Issue #192:
                     * No need to deep copy compactLayout as it is never modified in-place
                     * (e.g., call setters to change some members). Thus, it is safe to use
                     * the current reference of compactLayout even if the compactors will
                     * be running multiple threads.
                     *
                     * Deep copy it if it is in-place modified in the future.
                     */
                        .setCompactLayout(batchLayout)
                        .setInputStorage(orderStorage)
                        .setOutputStorage(compactStorage)
                        .setPath(targetFilePath)
                        .setBlockSize(blockSize)
                        .setReplication(replication)
                        .setBlockPadding(false)
                        .setHasHiddenColumn(true);

                final String finalTargetFileName = targetFileName;
                final String finalTargetFilePath = targetFilePath;
                final Path finalTargetPath = targetPath;
                long threadStart = System.currentTimeMillis();

                compactionTasks.add(compactExecutor.submit((Callable<Void>) () -> {
                    // Issue #192: run compaction in threads.
                    int numRowGroup;
                    Map<Long, IndexProto.RowLocation> relocatedLocations = java.util.Collections.emptyMap();
                    Map<Long, IndexProto.IndexKey> primaryKeysByRowId = java.util.Collections.emptyMap();
                    if (useTagCompactor)
                    {
                        PixelsTagCompactor pixelsTagCompactor = PixelsTagCompactor.create(
                                sourcePaths, orderStorage, compactStorage, finalTargetFilePath,
                                tagColumnName, tagEntries, primaryTableId, primaryIndexId,
                                indexService, metadataService,
                                blockSize, replication);
                        numRowGroup = pixelsTagCompactor.compact();
                        relocatedLocations = pixelsTagCompactor.getRelocatedLocations();
                        primaryKeysByRowId = pixelsTagCompactor.getPrimaryKeysByRowId();
                    }
                    else
                    {
                        PixelsCompactor pixelsCompactor = compactorBuilder.build();
                        pixelsCompactor.compact();
                        pixelsCompactor.close();
                        numRowGroup = pixelsCompactor.getNumRowGroup();
                    }
                    File compactFile = new File();
                    compactFile.setName(finalTargetFileName);
                    compactFile.setType(File.Type.REGULAR);
                    compactFile.setNumRowGroup(numRowGroup);
                    compactFile.setPathId(finalTargetPath.getId());
                    compactionResults.offer(new CompactionResult(
                            compactFile, finalTargetPath, relocatedLocations, primaryKeysByRowId));
                    System.out.println("Compact file '" + finalTargetFilePath + "' is built in " +
                            ((System.currentTimeMillis() - threadStart) / 1000.0) + "s");
                    return null;
                }));

                i += batchSize;
            }
        }

        // Issue #192: wait for the compaction to complete.
        compactExecutor.shutdown();
        while (!compactExecutor.awaitTermination(100, TimeUnit.SECONDS));
        throwIfAnyCompactionFailed(compactionTasks);
        List<CompactionResult> results = new ArrayList<>(compactionResults);
        List<File> compactFiles = new ArrayList<>(results.size());
        for (CompactionResult result : results)
        {
            compactFiles.add(result.file());
        }
        if (useTagCompactor)
        {
            MetadataPublication metadataPublication = new MetadataPublication()
            {
                @Override
                public void addFiles(List<File> files) throws MetadataException
                {
                    if (!metadataService.addFiles(files))
                    {
                        throw new MetadataException("failed to add compact files to metadata");
                    }
                }

                @Override
                public long getFileId(String filePath) throws MetadataException
                {
                    return metadataService.getFileId(filePath);
                }

                @Override
                public void deleteFiles(List<Long> fileIds) throws MetadataException
                {
                    if (!metadataService.deleteFiles(fileIds))
                    {
                        throw new MetadataException("failed to roll back compact files from metadata");
                    }
                }

            };
            publishCompactionResults(results, indexService, primaryTableId, primaryIndexId, metadataPublication);
        }
        else if (!metadataService.addFiles(compactFiles))
        {
            throw new MetadataException("failed to add compact files to metadata");
        }

        if (retinaService.isEnabled())
        {
            for (CompactionResult result : results)
            {
                File file = result.file();
                Path path = result.path();
                try
                {
                    retinaService.addVisibility(File.getFilePath(path, file));
                } catch (RetinaException e)
                {
                    System.out.println("add visibility for compact file '" + file + "' failed");
                }
            }
        }

        long endTime = System.currentTimeMillis();
        System.out.println("Pixels files in '" + Joiner.on(";").join(layout.getOrderedPathUris()) + "' are compacted into '" +
                Joiner.on(";").join(layout.getCompactPathUris()) + "' by " + threadNum + " threads in " +
                (endTime - startTime) / 1000 + "s.");
    }

    interface MetadataPublication
    {
        void addFiles(List<File> files) throws MetadataException;

        long getFileId(String filePath) throws MetadataException;

        void deleteFiles(List<Long> fileIds) throws MetadataException;
    }

    static void publishCompactionResults(
            List<CompactionResult> results,
            MainIndex mainIndex,
            MetadataPublication metadataPublication)
            throws MetadataException
    {
        List<File> compactFiles = new ArrayList<>(results.size());
        for (CompactionResult result : results)
        {
            compactFiles.add(result.file());
        }
        metadataPublication.addFiles(compactFiles);

        List<Long> publishedFileIds = new ArrayList<>(results.size());
        try
        {
            Map<CompactionResult, Long> fileIds = new LinkedHashMap<>();
            for (CompactionResult result : results)
            {
                long fileId = metadataPublication.getFileId(File.getFilePath(result.path(), result.file()));
                fileIds.put(result, fileId);
                publishedFileIds.add(fileId);
            }
            for (CompactionResult result : results)
            {
                if (result.relocatedLocations().isEmpty())
                {
                    continue;
                }
                long fileId = fileIds.get(result);
                for (Map.Entry<Long, IndexProto.RowLocation> entry : result.relocatedLocations().entrySet())
                {
                    IndexProto.RowLocation location = entry.getValue().toBuilder()
                            .setFileId(fileId)
                            .build();
                    if (!mainIndex.putEntry(entry.getKey(), location))
                    {
                        throw new MetadataException("failed to update main index for compacted row " +
                                entry.getKey());
                    }
                }
            }
        }
        catch (MetadataException failure)
        {
            rollbackPublishedFiles(metadataPublication, publishedFileIds, failure);
            throw failure;
        }
        catch (RuntimeException failure)
        {
            rollbackPublishedFiles(metadataPublication, publishedFileIds, failure);
            throw new MetadataException("failed to publish compacted Pixels files", failure);
        }
    }

    static void publishCompactionResults(
            List<CompactionResult> results,
            IndexService indexService,
            long tableId,
            long indexId,
            MetadataPublication metadataPublication)
            throws MetadataException
    {
        List<File> compactFiles = new ArrayList<>(results.size());
        for (CompactionResult result : results)
        {
            compactFiles.add(result.file());
        }
        metadataPublication.addFiles(compactFiles);

        List<Long> publishedFileIds = new ArrayList<>(results.size());
        try
        {
            for (CompactionResult result : results)
            {
                long fileId = metadataPublication.getFileId(File.getFilePath(result.path(), result.file()));
                publishedFileIds.add(fileId);
                for (Map.Entry<Long, IndexProto.RowLocation> relocated : result.relocatedLocations().entrySet())
                {
                    IndexProto.IndexKey primaryKey = result.primaryKeysByRowId().get(relocated.getKey());
                    if (primaryKey == null)
                    {
                        throw new MetadataException("missing primary key for compacted row " + relocated.getKey());
                    }
                    IndexProto.PrimaryIndexEntry entry = IndexProto.PrimaryIndexEntry.newBuilder()
                            .setIndexKey(primaryKey)
                            .setRowId(relocated.getKey())
                            .setRowLocation(relocated.getValue().toBuilder().setFileId(fileId).build())
                            .build();
                    int bucketId = io.pixelsdb.pixels.common.utils.IndexUtils
                            .getBucketIdFromByteBuffer(primaryKey.getKey());
                    if (!indexService.putPrimaryIndexEntry(entry,
                            IndexOption.builder().vNodeId(bucketId).build()))
                    {
                        throw new MetadataException("failed to update IndexServer for compacted row " +
                                relocated.getKey());
                    }
                }
            }
        }
        catch (MetadataException failure)
        {
            rollbackPublishedFiles(metadataPublication, publishedFileIds, failure);
            throw failure;
        }
        catch (IndexException | RuntimeException failure)
        {
            rollbackPublishedFiles(metadataPublication, publishedFileIds, failure);
            throw new MetadataException("failed to publish compacted Pixels index locations", failure);
        }
    }

    private static void rollbackPublishedFiles(
            MetadataPublication metadataPublication,
            List<Long> publishedFileIds,
            Exception failure)
    {
        if (publishedFileIds.isEmpty())
        {
            return;
        }
        try
        {
            metadataPublication.deleteFiles(publishedFileIds);
        }
        catch (MetadataException cleanupFailure)
        {
            failure.addSuppressed(cleanupFailure);
        }
    }

    static final class CompactionResult
    {
        private final File file;
        private final Path path;
        private final Map<Long, IndexProto.RowLocation> relocatedLocations;
        private final Map<Long, IndexProto.IndexKey> primaryKeysByRowId;

        CompactionResult(File file, Path path,
                Map<Long, IndexProto.RowLocation> relocatedLocations)
        {
            this(file, path, relocatedLocations, java.util.Collections.emptyMap());
        }

        CompactionResult(File file, Path path,
                Map<Long, IndexProto.RowLocation> relocatedLocations,
                Map<Long, IndexProto.IndexKey> primaryKeysByRowId)
        {
            this.file = file;
            this.path = path;
            this.relocatedLocations = relocatedLocations;
            this.primaryKeysByRowId = primaryKeysByRowId;
        }

        private File file()
        {
            return file;
        }

        private Path path()
        {
            return path;
        }

        private Map<Long, IndexProto.RowLocation> relocatedLocations()
        {
            return relocatedLocations;
        }

        private Map<Long, IndexProto.IndexKey> primaryKeysByRowId()
        {
            return primaryKeysByRowId;
        }
    }

    static void throwIfAnyCompactionFailed(List<? extends Future<?>> compactionTasks)
            throws MetadataException
    {
        for (Future<?> compactionTask : compactionTasks)
        {
            try
            {
                compactionTask.get();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new MetadataException("interrupted while waiting for Pixels compaction tasks", e);
            }
            catch (ExecutionException e)
            {
                throw new MetadataException("a Pixels compaction task failed", e.getCause());
            }
        }
    }
}
