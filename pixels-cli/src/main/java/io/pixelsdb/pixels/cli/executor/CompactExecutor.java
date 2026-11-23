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
import io.pixelsdb.pixels.common.metadata.domain.Column;
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
import io.pixelsdb.pixels.core.TypeDescription;
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
    /** Keep relocation RPCs comfortably below the configured gRPC message limit. */
    static final int MAX_PRIMARY_RELOCATION_ENTRIES_PER_RPC = 16_384;
    /** Experimental opt-in parallelism for independent primary-index buckets. */
    static final int PRIMARY_RELOCATE_PARALLELISM = Math.max(1, Integer.getInteger(
            "pixels.compact.primary-relocate-parallelism", 1));
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
        String configuredTagColumns = configFactory.getProperty("dml.tag.columns");
        if (configuredTagColumns == null || configuredTagColumns.trim().isEmpty())
        {
            configuredTagColumns = configFactory.getProperty("dml.tag.column");
        }
        final List<String> tagColumnNames = configuredTagColumns == null ?
                List.of() : java.util.Arrays.stream(configuredTagColumns.split(","))
                        .map(String::trim).filter(name -> !name.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
        final boolean useTagCompactor = !tagColumnNames.isEmpty();
        final String tagColumnName = useTagCompactor ? tagColumnNames.get(0) : null;
        final long primaryTableId;
        final long primaryIndexId;
        final IndexService indexService;
        final MainIndex mainIndex;
        final List<IndexProto.TagIndexEntry> tagEntries;
        final PixelsTagCompactor.TagIndexSnapshot tagIndexSnapshot;
        final List<String> primaryKeyColumnNames;
        final List<TypeDescription> primaryKeyTypes;
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
            List<IndexProto.TagIndexEntry> allTagEntries =
                    indexService.listTagIndexEntries(table.getId(), primaryIndexId);
            tagEntries = allTagEntries.stream()
                    .filter(entry -> entry.getColumnName().isEmpty() ||
                            tagColumnNames.stream().anyMatch(name ->
                                    entry.getColumnName().equalsIgnoreCase(name)))
                    .collect(java.util.stream.Collectors.toList());
            tagIndexSnapshot = PixelsTagCompactor.prepareTagIndexSnapshot(
                    tagEntries, primaryTableId, primaryIndexId, indexService);
            List<Column> tableColumns = metadataService.getColumns(schemaName, tableName, false);
            primaryKeyColumnNames = new ArrayList<>();
            primaryKeyTypes = new ArrayList<>();
            for (Integer keyColumnId : primaryIndexMetadata.getKeyColumns().getKeyColumnIds())
            {
                Column primaryKeyColumn = tableColumns.stream()
                        .filter(column -> column.getId() == keyColumnId)
                        .findFirst()
                        .orElseThrow(() -> new MetadataException(
                                "primary-key column is not present in metadata: " + keyColumnId));
                primaryKeyColumnNames.add(primaryKeyColumn.getName());
                primaryKeyTypes.add(TypeDescription.fromString(primaryKeyColumn.getType()));
            }
            System.out.println("tag-aware compaction enabled for columns '" + String.join(",", tagColumnNames) + "' with " +
                    tagEntries.size() + " tag entries.");
            System.out.println("[PIXELS_TAG_INDEX_METRIC] tag_entries=" + tagEntries.size() +
                    " tagged_rows=" + tagIndexSnapshot.getTaggedRowCount() +
                    " resolve_rows=" + tagIndexSnapshot.getPrimaryResolveRowCount() +
                    " resolve_rpc_count=" + tagIndexSnapshot.getPrimaryResolveRpcCount() +
                    " resolve_ms=" + tagIndexSnapshot.getPrimaryResolveNanos() / 1_000_000.0);
        }
        else
        {
            primaryTableId = -1;
            primaryIndexId = -1;
            indexService = null;
            mainIndex = null;
            tagEntries = java.util.Collections.emptyList();
            tagIndexSnapshot = null;
            primaryKeyColumnNames = java.util.Collections.emptyList();
            primaryKeyTypes = java.util.Collections.emptyList();
        }

        // Issue #998: compact need to exclude empty files.  A tag-aware UPDATE
        // records the new values in the durable tag index and does not create an
        // ordered data file.  After the first COMPACT, the current readable
        // files therefore live under the compact paths.  Include those files
        // when there are pending tag entries; otherwise a post-COMPACT UPDATE
        // is acknowledged but can never be materialized by this command.
        List<Status> statuses = new ArrayList<>(orderStorage.listStatus(layout.getOrderedPathUris()));
        if (useTagCompactor && !tagEntries.isEmpty())
        {
            statuses.addAll(compactStorage.listStatus(layout.getCompactPathUris()));
        }
        Iterator<Status> statusIterator = statuses.iterator();
        while (statusIterator.hasNext())
        {
            Status status = statusIterator.next();
            // A table-root URI is a supported layout form.  LocalFS returns
            // the root directory itself from listStatus(root), but directory
            // URIs are not registered as metadata files.  Filter them before
            // asking MetadataService for a file type.
            if (status.isDirectory() || !status.getName().endsWith(".pxl") ||
                    orderStorage.isDirectory(status.getPath()) ||
                    metadataService.getFileType(status.getPath()) != File.Type.REGULAR)
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
                List<Long> oldFileIds = new ArrayList<>();
                for (int j = 0; j < batchSize; ++j)
                {
                    if (!groupStatuses.get(i + j).getPath().endsWith("/"))
                    {
                        String sourcePath = groupStatuses.get(i + j).getPath();
                        sourcePaths.add(sourcePath);
                        oldFileIds.add(metadataService.getFileId(sourcePath));
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
                final List<Long> finalOldFileIds = List.copyOf(oldFileIds);
                long sourceMinRowId = Long.MAX_VALUE;
                long sourceMaxRowId = -1L;
                for (long oldFileId : finalOldFileIds)
                {
                    File oldFile = metadataService.getFileById(oldFileId);
                    if (oldFile != null && oldFile.getMinRowId() >= 0 &&
                            oldFile.getMaxRowId() >= oldFile.getMinRowId())
                    {
                        sourceMinRowId = Math.min(sourceMinRowId, oldFile.getMinRowId());
                        sourceMaxRowId = Math.max(sourceMaxRowId, oldFile.getMaxRowId());
                    }
                }
                final long finalSourceMinRowId = sourceMinRowId;
                final long finalSourceMaxRowId = sourceMaxRowId;
                long threadStart = System.currentTimeMillis();

                compactionTasks.add(compactExecutor.submit((Callable<Void>) () -> {
                    // Issue #192: run compaction in threads.
                    long rewriteStartNanos = System.nanoTime();
                    int numRowGroup;
                    Map<Long, IndexProto.RowLocation> relocatedLocations = java.util.Collections.emptyMap();
                    Map<Long, IndexProto.IndexKey> primaryKeysByRowId = java.util.Collections.emptyMap();
                    long primaryResolveRowCount = 0L;
                    int primaryResolveRpcCount = 0;
                    long primaryResolveNanos = 0L;
                    if (useTagCompactor)
                    {
                        PixelsTagCompactor pixelsTagCompactor = PixelsTagCompactor.create(
                                sourcePaths, orderStorage, compactStorage, finalTargetFilePath,
                                tagColumnName, tagIndexSnapshot, primaryTableId, primaryIndexId,
                                indexService, primaryKeyColumnNames, primaryKeyTypes, metadataService,
                                blockSize, replication);
                        numRowGroup = pixelsTagCompactor.compact();
                        relocatedLocations = pixelsTagCompactor.getRelocatedLocations();
                        primaryKeysByRowId = pixelsTagCompactor.getPrimaryKeysByRowId();
                        primaryResolveRowCount = pixelsTagCompactor.getPrimaryResolveRowCount();
                        primaryResolveRpcCount = pixelsTagCompactor.getPrimaryResolveRpcCount();
                        primaryResolveNanos = pixelsTagCompactor.getPrimaryResolveNanos();
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
                    // Keep the new file hidden until indexes have been updated and the
                    // metadata transaction atomically swaps it with its source files.
                    compactFile.setType(File.Type.TEMPORARY_GC);
                    compactFile.setNumRowGroup(numRowGroup);
                    compactFile.setPathId(finalTargetPath.getId());
                    long minRowId = finalSourceMinRowId;
                    long maxRowId = finalSourceMaxRowId;
                    if (!relocatedLocations.isEmpty())
                    {
                        minRowId = relocatedLocations.keySet().stream()
                                .mapToLong(Long::longValue)
                                .min()
                                .orElse(minRowId);
                        maxRowId = relocatedLocations.keySet().stream()
                                .mapToLong(Long::longValue)
                                .max()
                                .orElse(maxRowId);
                    }
                    if (minRowId >= 0 && maxRowId >= minRowId)
                    {
                        compactFile.setMinRowId(minRowId);
                        compactFile.setMaxRowId(maxRowId);
                    }
                    compactionResults.offer(new CompactionResult(
                            compactFile, finalTargetPath, finalOldFileIds,
                            relocatedLocations, primaryKeysByRowId,
                            primaryResolveRowCount, primaryResolveRpcCount, primaryResolveNanos,
                            System.nanoTime() - rewriteStartNanos));
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
        long workerNanos = results.stream().mapToLong(CompactionResult::rewriteNanos).sum();
        long workerWallNanos = results.stream().mapToLong(CompactionResult::rewriteNanos).max().orElse(0L);
        long resolveNanos = results.stream().mapToLong(CompactionResult::primaryResolveNanos).sum();
        System.out.println("[PIXELS_COMPACTION_STAGE_METRIC] stage=physical_rewrite outputs=" + results.size() +
                " worker_ms=" + workerNanos / 1_000_000.0 +
                " non_resolve_worker_ms=" + Math.max(0L, workerNanos - resolveNanos) / 1_000_000.0 +
                " worker_wall_ms=" + workerWallNanos / 1_000_000.0);
        long cleanupAt = getRetireDeadline(configFactory);
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

            @Override
            public void atomicSwapFiles(long newFileId, List<Long> oldFileIds, long deadline)
                    throws MetadataException
            {
                metadataService.atomicSwapFiles(newFileId, oldFileIds, deadline);
            }
        };
        long publishStartNanos = System.nanoTime();
        if (useTagCompactor)
        {
            publishCompactionResults(results, indexService, primaryTableId, primaryIndexId,
                    metadataPublication, cleanupAt);
        }
        else
        {
            publishCompactionResults(results, metadataPublication, cleanupAt);
        }
        System.out.println("[PIXELS_COMPACTION_STAGE_METRIC] stage=publish outputs=" + results.size() +
                " publish_ms=" + (System.nanoTime() - publishStartNanos) / 1_000_000.0);

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

        void atomicSwapFiles(long newFileId, List<Long> oldFileIds, long cleanupAt)
                throws MetadataException;
    }

    private static long getRetireDeadline(ConfigFactory configFactory)
    {
        String delayHours = configFactory.getProperty("retina.storage.gc.file.retire.delay.hours");
        double hours = delayHours == null ? 24.0 : Double.parseDouble(delayHours);
        if (hours < 0)
        {
            throw new IllegalArgumentException("retire delay must be non-negative");
        }
        return System.currentTimeMillis() + (long) (hours * 3_600_000L);
    }

    static void publishCompactionResults(
            List<CompactionResult> results,
            MetadataPublication metadataPublication,
            long cleanupAt)
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
            Map<CompactionResult, Long> fileIds = getPublishedFileIds(results, metadataPublication,
                    publishedFileIds);
            atomicSwapCompactionResults(results, fileIds, metadataPublication, cleanupAt);
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
            MainIndex mainIndex,
            MetadataPublication metadataPublication,
            long cleanupAt)
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
            Map<CompactionResult, Long> fileIds = getPublishedFileIds(results, metadataPublication,
                    publishedFileIds);
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
            atomicSwapCompactionResults(results, fileIds, metadataPublication, cleanupAt);
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
            MetadataPublication metadataPublication,
            long cleanupAt)
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
            Map<CompactionResult, Long> fileIds = getPublishedFileIds(results, metadataPublication,
                    publishedFileIds);
            int relocationRpcCount = 0;
            long relocatedRowCount = 0;
            long primaryResolveRowCount = 0L;
            long primaryResolveRpcCount = 0L;
            long primaryResolveNanos = 0L;
            for (CompactionResult result : results)
            {
                primaryResolveRowCount += result.primaryResolveRowCount();
                primaryResolveRpcCount += result.primaryResolveRpcCount();
                primaryResolveNanos += result.primaryResolveNanos();
                long fileId = fileIds.get(result);
                relocationRpcCount += updatePrimaryIndexLocations(
                        indexService, tableId, indexId, fileId,
                        result.relocatedLocations(), result.primaryKeysByRowId(),
                        PRIMARY_RELOCATE_PARALLELISM);
                relocatedRowCount += result.relocatedLocations().size();
            }
            System.out.println("[PIXELS_COMPACTION_RESOLVE_METRIC] rows=" + primaryResolveRowCount +
                    " resolve_rpc_count=" + primaryResolveRpcCount +
                    " resolve_ms=" + primaryResolveNanos / 1_000_000.0);
            System.out.println("[PIXELS_COMPACTION_INDEX_METRIC] relocated_rows=" + relocatedRowCount +
                    " relocation_rpc_count=" + relocationRpcCount);
            atomicSwapCompactionResults(results, fileIds, metadataPublication, cleanupAt);
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

    /**
     * Relocate the rows of one compacted output file using the existing batch
     * primary-index RPC.  Entries are grouped by vnode because a single-point
     * index instance is selected by the vnode in IndexOption.  The old code
     * called the single-entry RPC once per row, making a million-row
     * compaction a million network round trips.
     */
    static int updatePrimaryIndexLocations(
            IndexService indexService,
            long tableId,
            long indexId,
            long fileId,
            Map<Long, IndexProto.RowLocation> relocatedLocations,
            Map<Long, IndexProto.IndexKey> primaryKeysByRowId)
            throws IndexException, MetadataException
    {
        return updatePrimaryIndexLocations(indexService, tableId, indexId, fileId,
                relocatedLocations, primaryKeysByRowId, 1);
    }

    static int updatePrimaryIndexLocations(
            IndexService indexService,
            long tableId,
            long indexId,
            long fileId,
            Map<Long, IndexProto.RowLocation> relocatedLocations,
            Map<Long, IndexProto.IndexKey> primaryKeysByRowId,
            int parallelism)
            throws IndexException, MetadataException
    {
        requireNonNull(indexService, "indexService is null");
        requireNonNull(relocatedLocations, "relocatedLocations is null");
        requireNonNull(primaryKeysByRowId, "primaryKeysByRowId is null");
        if (relocatedLocations.isEmpty())
        {
            return 0;
        }

        Map<Integer, List<IndexProto.PrimaryIndexEntry>> entriesByBucket = new LinkedHashMap<>();
        for (Map.Entry<Long, IndexProto.RowLocation> relocated : relocatedLocations.entrySet())
        {
            long rowId = relocated.getKey();
            IndexProto.IndexKey primaryKey = primaryKeysByRowId.get(rowId);
            if (primaryKey == null)
            {
                throw new MetadataException("missing primary key for compacted row " + rowId);
            }
            IndexProto.PrimaryIndexEntry entry = IndexProto.PrimaryIndexEntry.newBuilder()
                    .setIndexKey(primaryKey)
                    .setRowId(rowId)
                    .setRowLocation(relocated.getValue().toBuilder().setFileId(fileId).build())
                    .build();
            int bucketId = io.pixelsdb.pixels.common.utils.IndexUtils
                    .getBucketIdFromByteBuffer(primaryKey.getKey());
            entriesByBucket.computeIfAbsent(bucketId, ignored -> new ArrayList<>()).add(entry);
        }

        if (parallelism <= 1 || entriesByBucket.size() <= 1)
        {
            int rpcCount = 0;
            for (List<IndexProto.PrimaryIndexEntry> entries : entriesByBucket.values())
            {
                rpcCount += updatePrimaryIndexBucket(indexService, tableId, indexId, fileId,
                        entries, io.pixelsdb.pixels.common.utils.IndexUtils.getBucketIdFromByteBuffer(
                                entries.get(0).getIndexKey().getKey()));
            }
            return rpcCount;
        }

        int workerCount = Math.min(parallelism, entriesByBucket.size());
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<Integer>> futures = new ArrayList<>(entriesByBucket.size());
        try
        {
            for (Map.Entry<Integer, List<IndexProto.PrimaryIndexEntry>> bucket : entriesByBucket.entrySet())
            {
                int bucketId = bucket.getKey();
                List<IndexProto.PrimaryIndexEntry> entries = List.copyOf(bucket.getValue());
                futures.add(executor.submit(() -> updatePrimaryIndexBucket(
                        indexService, tableId, indexId, fileId, entries, bucketId)));
            }
            int rpcCount = 0;
            for (Future<Integer> future : futures)
            {
                try
                {
                    rpcCount += future.get();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new MetadataException("interrupted while relocating compacted primary rows", e);
                }
                catch (ExecutionException e)
                {
                    Throwable cause = e.getCause();
                    if (cause instanceof IndexException)
                    {
                        throw (IndexException) cause;
                    }
                    if (cause instanceof MetadataException)
                    {
                        throw (MetadataException) cause;
                    }
                    throw new MetadataException("failed to relocate compacted primary rows", cause);
                }
            }
            return rpcCount;
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    private static int updatePrimaryIndexBucket(
            IndexService indexService,
            long tableId,
            long indexId,
            long fileId,
            List<IndexProto.PrimaryIndexEntry> entries,
            int bucketId)
            throws IndexException, MetadataException
    {
        int rpcCount = 0;
        for (int offset = 0; offset < entries.size(); offset += MAX_PRIMARY_RELOCATION_ENTRIES_PER_RPC)
        {
            int end = Math.min(entries.size(), offset + MAX_PRIMARY_RELOCATION_ENTRIES_PER_RPC);
            List<IndexProto.PrimaryIndexEntry> batch = entries.subList(offset, end);
            if (!indexService.putPrimaryIndexEntries(tableId, indexId, batch,
                    IndexOption.builder().vNodeId(bucketId).build()))
            {
                throw new MetadataException("failed to update IndexServer for compacted primary rows");
            }
            rpcCount++;
        }
        return rpcCount;
    }

    static void publishCompactionResults(
            List<CompactionResult> results,
            MainIndex mainIndex,
            MetadataPublication metadataPublication)
            throws MetadataException
    {
        publishCompactionResults(results, mainIndex, metadataPublication, System.currentTimeMillis());
    }

    static void publishCompactionResults(
            List<CompactionResult> results,
            IndexService indexService,
            long tableId,
            long indexId,
            MetadataPublication metadataPublication)
            throws MetadataException
    {
        publishCompactionResults(results, indexService, tableId, indexId,
                metadataPublication, System.currentTimeMillis());
    }

    private static Map<CompactionResult, Long> getPublishedFileIds(
            List<CompactionResult> results,
            MetadataPublication metadataPublication,
            List<Long> publishedFileIds)
            throws MetadataException
    {
        Map<CompactionResult, Long> fileIds = new LinkedHashMap<>();
        for (CompactionResult result : results)
        {
            long fileId = metadataPublication.getFileId(File.getFilePath(result.path(), result.file()));
            fileIds.put(result, fileId);
            publishedFileIds.add(fileId);
        }
        return fileIds;
    }

    private static void atomicSwapCompactionResults(
            List<CompactionResult> results,
            Map<CompactionResult, Long> fileIds,
            MetadataPublication metadataPublication,
            long cleanupAt)
            throws MetadataException
    {
        for (CompactionResult result : results)
        {
            metadataPublication.atomicSwapFiles(fileIds.get(result), result.oldFileIds(), cleanupAt);
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
        private final List<Long> oldFileIds;
        private final Map<Long, IndexProto.RowLocation> relocatedLocations;
        private final Map<Long, IndexProto.IndexKey> primaryKeysByRowId;
        private final long primaryResolveRowCount;
        private final int primaryResolveRpcCount;
        private final long primaryResolveNanos;
        private final long rewriteNanos;

        CompactionResult(File file, Path path,
                Map<Long, IndexProto.RowLocation> relocatedLocations)
        {
            this(file, path, java.util.Collections.emptyList(), relocatedLocations,
                    java.util.Collections.emptyMap());
        }

        CompactionResult(File file, Path path,
                Map<Long, IndexProto.RowLocation> relocatedLocations,
                Map<Long, IndexProto.IndexKey> primaryKeysByRowId)
        {
            this(file, path, java.util.Collections.emptyList(), relocatedLocations, primaryKeysByRowId);
        }

        CompactionResult(File file, Path path,
                List<Long> oldFileIds,
                Map<Long, IndexProto.RowLocation> relocatedLocations,
                Map<Long, IndexProto.IndexKey> primaryKeysByRowId)
        {
            this(file, path, oldFileIds, relocatedLocations, primaryKeysByRowId,
                    0L, 0, 0L, 0L);
        }

        CompactionResult(File file, Path path,
                List<Long> oldFileIds,
                Map<Long, IndexProto.RowLocation> relocatedLocations,
                Map<Long, IndexProto.IndexKey> primaryKeysByRowId,
                long primaryResolveRowCount,
                int primaryResolveRpcCount,
                long primaryResolveNanos,
                long rewriteNanos)
        {
            this.file = file;
            this.path = path;
            this.oldFileIds = List.copyOf(oldFileIds);
            this.relocatedLocations = relocatedLocations;
            this.primaryKeysByRowId = primaryKeysByRowId;
            this.primaryResolveRowCount = primaryResolveRowCount;
            this.primaryResolveRpcCount = primaryResolveRpcCount;
            this.primaryResolveNanos = primaryResolveNanos;
            this.rewriteNanos = rewriteNanos;
        }

        private File file()
        {
            return file;
        }

        private Path path()
        {
            return path;
        }

        private List<Long> oldFileIds()
        {
            return oldFileIds;
        }

        private Map<Long, IndexProto.RowLocation> relocatedLocations()
        {
            return relocatedLocations;
        }

        private Map<Long, IndexProto.IndexKey> primaryKeysByRowId()
        {
            return primaryKeysByRowId;
        }

        private long primaryResolveRowCount()
        {
            return primaryResolveRowCount;
        }

        private int primaryResolveRpcCount()
        {
            return primaryResolveRpcCount;
        }

        private long primaryResolveNanos()
        {
            return primaryResolveNanos;
        }

        private long rewriteNanos()
        {
            return rewriteNanos;
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
