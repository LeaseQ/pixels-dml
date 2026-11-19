/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import io.pixelsdb.pixels.common.exception.MainIndexException;
import io.pixelsdb.pixels.common.exception.IndexException;
import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.common.exception.SinglePointIndexException;
import io.pixelsdb.pixels.common.index.IndexOption;
import io.pixelsdb.pixels.common.index.MainIndex;
import io.pixelsdb.pixels.common.index.SinglePointIndex;
import io.pixelsdb.pixels.common.index.SinglePointIndexFactory;
import io.pixelsdb.pixels.common.index.service.IndexService;
import io.pixelsdb.pixels.common.metadata.MetadataService;
import io.pixelsdb.pixels.common.physical.Storage;
import io.pixelsdb.pixels.common.utils.ConfigFactory;
import io.pixelsdb.pixels.common.utils.IndexUtils;
import io.pixelsdb.pixels.core.PixelsFooterCache;
import io.pixelsdb.pixels.core.PixelsReader;
import io.pixelsdb.pixels.core.PixelsReaderImpl;
import io.pixelsdb.pixels.core.PixelsWriter;
import io.pixelsdb.pixels.core.PixelsWriterImpl;
import io.pixelsdb.pixels.core.TypeDescription;
import io.pixelsdb.pixels.core.encoding.EncodingLevel;
import io.pixelsdb.pixels.core.reader.PixelsReaderOption;
import io.pixelsdb.pixels.core.reader.PixelsRecordReader;
import io.pixelsdb.pixels.core.utils.PrimaryKeyBytes;
import io.pixelsdb.pixels.core.vector.BinaryColumnVector;
import io.pixelsdb.pixels.core.vector.BooleanColumnVector;
import io.pixelsdb.pixels.core.vector.ByteColumnVector;
import io.pixelsdb.pixels.core.vector.ColumnVector;
import io.pixelsdb.pixels.core.vector.DecimalColumnVector;
import io.pixelsdb.pixels.core.vector.DoubleColumnVector;
import io.pixelsdb.pixels.core.vector.FloatColumnVector;
import io.pixelsdb.pixels.core.vector.IntColumnVector;
import io.pixelsdb.pixels.core.vector.LongDecimalColumnVector;
import io.pixelsdb.pixels.core.vector.LongColumnVector;
import io.pixelsdb.pixels.core.vector.ShortColumnVector;
import io.pixelsdb.pixels.core.vector.VectorizedRowBatch;
import io.pixelsdb.pixels.index.IndexProto;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import static java.util.Objects.requireNonNull;

/** Rewrites source Pixels rows while applying the durable label tag index. */
final class PixelsTagCompactor
{
    private static final int READ_BATCH_SIZE = 1024;
    /** Keep source-row resolution requests bounded for wide or composite primary keys. */
    static final int MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC = 16_384;

    @FunctionalInterface
    interface PrimaryLookup
    {
        long get(IndexProto.IndexKey key, int bucketId) throws SinglePointIndexException;
    }

    private final List<String> sourcePaths;
    private final Storage inputStorage;
    private final Storage outputStorage;
    private final String targetPath;
    private final String tagColumnName;
    private final Map<PixelsTagCompactionUpdateMap.Location, Map<String, byte[]>> updates;
    private final Map<Long, IndexProto.IndexKey> primaryKeysByRowId;
    private final Map<Long, IndexProto.RowLocation> relocatedLocations = new HashMap<>();
    private final IndexService indexService;
    private final long primaryTableId;
    private final long primaryIndexId;
    private final List<String> primaryKeyColumnNames;
    private final List<TypeDescription> primaryKeyTypes;
    private final MetadataService metadataService;
    private final long blockSize;
    private final short replication;
    private long primaryResolveRowCount;
    private int primaryResolveRpcCount;
    private long primaryResolveNanos;

    /**
     * Immutable snapshot of the durable tag index used by all compaction tasks.
     * Resolving this once is important: the same tag rows are visible to every
     * source-file task, so resolving them in each task multiplies index RPCs by
     * the compaction concurrency.
     */
    static final class TagIndexSnapshot
    {
        private final Map<PixelsTagCompactionUpdateMap.Location, Map<String, byte[]>> updates;
        private final int taggedRowCount;
        private final int primaryResolveRowCount;
        private final int primaryResolveRpcCount;
        private final long primaryResolveNanos;

        private TagIndexSnapshot(
                Map<PixelsTagCompactionUpdateMap.Location, Map<String, byte[]>> updates,
                int taggedRowCount,
                int primaryResolveRowCount,
                int primaryResolveRpcCount,
                long primaryResolveNanos)
        {
            this.updates = Map.copyOf(requireNonNull(updates, "updates is null"));
            this.taggedRowCount = taggedRowCount;
            this.primaryResolveRowCount = primaryResolveRowCount;
            this.primaryResolveRpcCount = primaryResolveRpcCount;
            this.primaryResolveNanos = primaryResolveNanos;
        }

        Map<PixelsTagCompactionUpdateMap.Location, Map<String, byte[]>> getUpdates()
        {
            return updates;
        }

        int getTaggedRowCount()
        {
            return taggedRowCount;
        }

        int getPrimaryResolveRowCount()
        {
            return primaryResolveRowCount;
        }

        int getPrimaryResolveRpcCount()
        {
            return primaryResolveRpcCount;
        }

        long getPrimaryResolveNanos()
        {
            return primaryResolveNanos;
        }
    }

    private PixelsTagCompactor(
            List<String> sourcePaths,
            Storage inputStorage,
            Storage outputStorage,
            String targetPath,
            String tagColumnName,
            Map<PixelsTagCompactionUpdateMap.Location, Map<String, byte[]>> updates,
            Map<Long, IndexProto.IndexKey> primaryKeysByRowId,
            IndexService indexService,
            long primaryTableId,
            long primaryIndexId,
            List<String> primaryKeyColumnNames,
            List<TypeDescription> primaryKeyTypes,
            MetadataService metadataService,
            long blockSize,
            short replication)
    {
        this.sourcePaths = List.copyOf(requireNonNull(sourcePaths, "sourcePaths is null"));
        this.inputStorage = requireNonNull(inputStorage, "inputStorage is null");
        this.outputStorage = requireNonNull(outputStorage, "outputStorage is null");
        this.targetPath = requireNonNull(targetPath, "targetPath is null");
        this.tagColumnName = requireNonNull(tagColumnName, "tagColumnName is null");
        this.updates = Map.copyOf(requireNonNull(updates, "updates is null"));
        // Tag rows are resolved before the rewrite; source rows are resolved in
        // bounded batches while they are read, so this map must remain mutable
        // until compact() has finished.
        this.primaryKeysByRowId = new HashMap<>(requireNonNull(primaryKeysByRowId,
                "primaryKeysByRowId is null"));
        this.indexService = requireNonNull(indexService, "indexService is null");
        this.primaryTableId = primaryTableId;
        this.primaryIndexId = primaryIndexId;
        this.primaryKeyColumnNames = List.copyOf(requireNonNull(primaryKeyColumnNames,
                "primaryKeyColumnNames is null"));
        this.primaryKeyTypes = List.copyOf(requireNonNull(primaryKeyTypes,
                "primaryKeyTypes is null"));
        if (this.primaryKeyColumnNames.isEmpty() ||
                this.primaryKeyColumnNames.size() != this.primaryKeyTypes.size())
        {
            throw new IllegalArgumentException("primary-key column names and types must have the same non-zero size");
        }
        this.metadataService = requireNonNull(metadataService, "metadataService is null");
        this.blockSize = blockSize;
        this.replication = replication;
    }

    static PixelsTagCompactor create(
            List<String> sourcePaths,
            Storage inputStorage,
            Storage outputStorage,
            String targetPath,
            String tagColumnName,
            List<IndexProto.TagIndexEntry> tagEntries,
            long primaryTableId,
            long primaryIndexId,
            IndexService indexService,
            List<String> primaryKeyColumnNames,
            List<TypeDescription> primaryKeyTypes,
            MetadataService metadataService,
            long blockSize,
            short replication)
            throws IndexException, MainIndexException
    {
        TagIndexSnapshot snapshot = prepareTagIndexSnapshot(
                tagEntries, primaryTableId, primaryIndexId, indexService);
        return create(sourcePaths, inputStorage, outputStorage, targetPath, tagColumnName,
                snapshot, primaryTableId, primaryIndexId, indexService, primaryKeyColumnNames,
                primaryKeyTypes, metadataService, blockSize, replication);
    }

    static PixelsTagCompactor create(
            List<String> sourcePaths,
            Storage inputStorage,
            Storage outputStorage,
            String targetPath,
            String tagColumnName,
            TagIndexSnapshot snapshot,
            long primaryTableId,
            long primaryIndexId,
            IndexService indexService,
            List<String> primaryKeyColumnNames,
            List<TypeDescription> primaryKeyTypes,
            MetadataService metadataService,
            long blockSize,
            short replication)
            throws MainIndexException
    {
        requireNonNull(snapshot, "snapshot is null");
        requireNonNull(indexService, "indexService is null");
        requireNonNull(metadataService, "metadataService is null");
        return new PixelsTagCompactor(
                sourcePaths, inputStorage, outputStorage, targetPath, tagColumnName,
                snapshot.getUpdates(), java.util.Collections.emptyMap(), indexService,
                primaryTableId, primaryIndexId,
                primaryKeyColumnNames, primaryKeyTypes, metadataService, blockSize, replication);
    }

    static TagIndexSnapshot prepareTagIndexSnapshot(
            List<IndexProto.TagIndexEntry> tagEntries,
            long primaryTableId,
            long primaryIndexId,
            IndexService indexService)
            throws IndexException
    {
        requireNonNull(tagEntries, "tagEntries is null");
        requireNonNull(indexService, "indexService is null");

        Map<ByteBuffer, Long> primaryKeyRowIds = new HashMap<>();
        Map<Long, IndexProto.IndexKey> primaryKeysByRowId = new HashMap<>();
        Map<Long, PixelsTagCompactionUpdateMap.Location> rowLocations = new HashMap<>();
        Set<ByteBuffer> distinctPrimaryKeys = new HashSet<>();
        List<IndexProto.IndexKey> pendingKeys = new ArrayList<>(MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC);
        int resolveRpcCount = 0;
        int resolvedRowCount = 0;
        long resolveNanos = 0L;

        for (IndexProto.TagIndexEntry entry : tagEntries)
        {
            for (ByteString encodedPrimaryKey : entry.getPrimaryKeysList())
            {
                ByteBuffer primaryKey = ByteBuffer.wrap(encodedPrimaryKey.toByteArray());
                if (distinctPrimaryKeys.add(primaryKey))
                {
                    pendingKeys.add(IndexProto.IndexKey.newBuilder()
                            .setTableId(primaryTableId)
                            .setIndexId(primaryIndexId)
                            .setKey(encodedPrimaryKey)
                            .setTimestamp(Long.MAX_VALUE)
                            .build());
                }
                if (pendingKeys.size() >= MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC)
                {
                    long start = System.nanoTime();
                    List<IndexProto.ResolvePrimaryEntry> resolvedEntries = indexService.resolvePrimaryEntries(
                            primaryTableId, primaryIndexId, pendingKeys);
                    resolveNanos += System.nanoTime() - start;
                    resolveRpcCount++;
                    resolvedRowCount += recordTagRows(
                            pendingKeys, resolvedEntries, primaryKeyRowIds, primaryKeysByRowId, rowLocations);
                    pendingKeys.clear();
                }
            }
        }
        if (!pendingKeys.isEmpty())
        {
            long start = System.nanoTime();
            List<IndexProto.ResolvePrimaryEntry> resolvedEntries = indexService.resolvePrimaryEntries(
                    primaryTableId, primaryIndexId, pendingKeys);
            resolveNanos += System.nanoTime() - start;
            resolveRpcCount++;
            resolvedRowCount += recordTagRows(
                    pendingKeys, resolvedEntries, primaryKeyRowIds, primaryKeysByRowId, rowLocations);
        }
        if (primaryKeyRowIds.size() != distinctPrimaryKeys.size())
        {
            throw new IllegalArgumentException("tag index primary key has no primary-index row id");
        }

        List<PixelsTagCompactionUpdateMap.Entry> entries = new ArrayList<>(tagEntries.size());
        for (IndexProto.TagIndexEntry entry : tagEntries)
        {
            List<byte[]> encodedRowIds = new ArrayList<>(entry.getPrimaryKeysCount());
            for (ByteString encodedPrimaryKey : entry.getPrimaryKeysList())
            {
                Long rowId = primaryKeyRowIds.get(ByteBuffer.wrap(encodedPrimaryKey.toByteArray()));
                if (rowId == null)
                {
                    throw new IllegalArgumentException("tag index primary key resolution disappeared");
                }
                encodedRowIds.add(ByteBuffer.allocate(Long.BYTES).putLong(rowId).array());
            }
            entries.add(new PixelsTagCompactionUpdateMap.Entry(
                    entry.getColumnName(), entry.getTag().toByteArray(), encodedRowIds));
        }
        Map<PixelsTagCompactionUpdateMap.Location, Map<String, byte[]>> updates =
                PixelsTagCompactionUpdateMap.build(entries, rowLocations);
        return new TagIndexSnapshot(updates, primaryKeyRowIds.size(),
                resolvedRowCount, resolveRpcCount, resolveNanos);
    }

    private static int recordTagRows(
            List<IndexProto.IndexKey> requestedKeys,
            List<IndexProto.ResolvePrimaryEntry> resolvedEntries,
            Map<ByteBuffer, Long> primaryKeyRowIds,
            Map<Long, IndexProto.IndexKey> primaryKeysByRowId,
            Map<Long, PixelsTagCompactionUpdateMap.Location> rowLocations)
    {
        if (resolvedEntries.size() != requestedKeys.size())
        {
            throw new IllegalArgumentException("primary resolve response size does not match tag index request");
        }
        for (int i = 0; i < resolvedEntries.size(); i++)
        {
            IndexProto.ResolvePrimaryEntry resolved = resolvedEntries.get(i);
            if (resolved.getRowId() < 0 || !resolved.hasIndexKey() || !resolved.hasRowLocation())
            {
                throw new IllegalArgumentException("tag index primary resolve response contains an invalid row");
            }
            IndexProto.IndexKey requestedKey = requestedKeys.get(i);
            if (!requestedKey.getKey().equals(resolved.getIndexKey().getKey()))
            {
                throw new IllegalArgumentException("primary resolve response order does not match tag index request");
            }
            long rowId = resolved.getRowId();
            IndexProto.IndexKey previousKey = primaryKeysByRowId.put(rowId, resolved.getIndexKey());
            if (previousKey != null && !previousKey.equals(resolved.getIndexKey()))
            {
                throw new IllegalArgumentException("multiple primary keys resolve to the same row id: " + rowId);
            }
            primaryKeyRowIds.put(ByteBuffer.wrap(resolved.getIndexKey().getKey().toByteArray()), rowId);
            IndexProto.RowLocation location = resolved.getRowLocation();
            rowLocations.put(rowId, new PixelsTagCompactionUpdateMap.Location(
                    location.getFileId(), location.getRgId(), location.getRgRowOffset()));
        }
        return resolvedEntries.size();
    }

    static Map<Long, PixelsTagCompactionUpdateMap.Location> buildRowLocationsByRowId(
            List<IndexProto.ResolvePrimaryEntry> resolvedEntries)
    {
        requireNonNull(resolvedEntries, "resolvedEntries is null");
        Map<Long, PixelsTagCompactionUpdateMap.Location> rowLocations =
                new HashMap<>(resolvedEntries.size() * 4 / 3 + 1);
        for (IndexProto.ResolvePrimaryEntry resolved : resolvedEntries)
        {
            IndexProto.RowLocation location = resolved.getRowLocation();
            rowLocations.put(resolved.getRowId(), new PixelsTagCompactionUpdateMap.Location(
                    location.getFileId(), location.getRgId(), location.getRgRowOffset()));
        }
        return rowLocations;
    }

    Map<Long, IndexProto.IndexKey> getPrimaryKeysByRowId()
    {
        return primaryKeysByRowId;
    }

    long getPrimaryResolveRowCount()
    {
        return primaryResolveRowCount;
    }

    int getPrimaryResolveRpcCount()
    {
        return primaryResolveRpcCount;
    }

    long getPrimaryResolveNanos()
    {
        return primaryResolveNanos;
    }

    static long lookupPrimaryRowId(long tableId, long indexId, ByteString encodedPrimaryKey,
            PrimaryLookup lookup)
            throws SinglePointIndexException
    {
        IndexProto.IndexKey key = IndexProto.IndexKey.newBuilder()
                .setTableId(tableId)
                .setIndexId(indexId)
                .setKey(encodedPrimaryKey)
                .setTimestamp(Long.MAX_VALUE)
                .build();
        return lookup.get(key, IndexUtils.getBucketIdFromByteBuffer(encodedPrimaryKey));
    }

    int compact() throws IOException, MetadataException
    {
        if (sourcePaths.isEmpty())
        {
            throw new IllegalArgumentException("sourcePaths is empty");
        }

        TypeDescription schema;
        io.pixelsdb.pixels.core.PixelsProto.CompressionKind compressionKind;
        int compressionBlockSize;
        int pixelStride;
        TimeZone timeZone;
        PixelsFooterCache footerCache = new PixelsFooterCache();
        PixelsReader firstReader = PixelsReaderImpl.newBuilder()
                .setStorage(inputStorage)
                .setPath(sourcePaths.get(0))
                .setPixelsFooterCache(footerCache)
                .build();
        try
        {
            schema = firstReader.getFileSchema();
            compressionKind = firstReader.getCompressionKind();
            compressionBlockSize = (int) firstReader.getCompressionBlockSize();
            pixelStride = (int) firstReader.getPixelStride();
            timeZone = TimeZone.getTimeZone(firstReader.getWriterTimeZone());
        }
        finally
        {
            firstReader.close();
        }

        Map<String, Integer> tagColumns = new LinkedHashMap<>();
        for (String configuredColumn : updates.values().stream()
                .flatMap(columnUpdates -> columnUpdates.keySet().stream())
                .distinct().toList())
        {
            String columnName = configuredColumn.isEmpty() ? tagColumnName : configuredColumn;
            int column = schema.getFieldNames().indexOf(columnName);
            if (column < 0)
            {
                for (int candidate = 0; candidate < schema.getFieldNames().size(); candidate++)
                {
                    if (schema.getFieldNames().get(candidate).equalsIgnoreCase(columnName))
                    {
                        column = candidate;
                        break;
                    }
                }
            }
            if (column < 0)
            {
                throw new IllegalArgumentException("tag column is not present in Pixels schema: " + columnName);
            }
            tagColumns.put(configuredColumn, column);
        }
        int[] primaryKeyOrdinals = resolvePrimaryKeyOrdinals(schema);

        ConfigFactory config = ConfigFactory.Instance();
        PixelsWriter writer = PixelsWriterImpl.newBuilder()
                .setSchema(schema)
                .setHasHiddenColumn(true)
                .setPixelStride(pixelStride)
                .setRowGroupSize(Integer.parseInt(config.getProperty("row.group.size")))
                .setCompressionKind(compressionKind)
                .setCompressionBlockSize(compressionBlockSize)
                .setTimeZone(timeZone)
                .setEncodingLevel(EncodingLevel.EL2)
                .setStorage(outputStorage)
                .setPath(targetPath)
                .setBlockSize(blockSize)
                .setReplication(replication)
                .setBlockPadding(false)
                .build();
        try
        {
            OutputRowPosition outputPosition = new OutputRowPosition();
            for (String sourcePath : sourcePaths)
            {
                compactSource(schema, tagColumns, primaryKeyOrdinals, writer, sourcePath, footerCache, outputPosition);
            }
            writer.close();
            // PixelsWriterImpl finalizes the last row group while closing the
            // writer. Read the count only after close so the metadata catalog
            // does not publish a compact file with numRowGroup=0.
            return writer.getNumRowGroup();
        }
        catch (IOException | RuntimeException e)
        {
            writer.abort();
            throw e;
        }
    }

    private void compactSource(
            TypeDescription schema,
            Map<String, Integer> tagColumns,
            int[] primaryKeyOrdinals,
            PixelsWriter writer,
            String sourcePath,
            PixelsFooterCache footerCache,
            OutputRowPosition outputPosition)
            throws IOException, MetadataException
    {
        long sourceFileId = metadataService.getFileId(sourcePath);
        long sourceRows = 0L;
        int sourceBatchCount = 0;
        long readNanos = 0L;
        long primaryKeyNanos = 0L;
        long updateNanos = 0L;
        long writerNanos = 0L;
        PixelsReader reader = PixelsReaderImpl.newBuilder()
                .setStorage(inputStorage)
                .setPath(sourcePath)
                .setPixelsFooterCache(footerCache)
                .build();
        try
        {
            String[] columns = schema.getFieldNames().toArray(new String[0]);
            List<IndexProto.IndexKey> pendingPrimaryKeys = new ArrayList<>(MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC);
            List<IndexProto.RowLocation> pendingOutputLocations =
                    new ArrayList<>(MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC);
            for (int rowGroupId = 0; rowGroupId < reader.getRowGroupNum(); rowGroupId++)
            {
                PixelsReaderOption option = new PixelsReaderOption()
                        .includeCols(columns)
                        .exposeHiddenColumn(true)
                        .rgRange(rowGroupId, 1);
                PixelsRecordReader recordReader = reader.read(option);
                int rowOffset = 0;
                while (true)
                {
                    long readStartNanos = System.nanoTime();
                    VectorizedRowBatch rowBatch = recordReader.readBatch(READ_BATCH_SIZE);
                    readNanos += System.nanoTime() - readStartNanos;
                    if (rowBatch.size > 0)
                    {
                        sourceRows += rowBatch.size;
                        sourceBatchCount++;
                        long primaryKeyStartNanos = System.nanoTime();
                        for (int position = 0; position < rowBatch.size; position++)
                        {
                            ByteString primaryKey = ByteString.copyFrom(PrimaryKeyBytes.fromColumnVectors(
                                    primaryKeyTypes, rowBatch.cols, primaryKeyOrdinals, position));
                            pendingPrimaryKeys.add(IndexProto.IndexKey.newBuilder()
                                    .setTableId(primaryTableId)
                                    .setIndexId(primaryIndexId)
                                    .setKey(primaryKey)
                                    .setTimestamp(Long.MAX_VALUE)
                                    .build());
                            pendingOutputLocations.add(outputPosition.location(0, position));
                        }
                        primaryKeyNanos += System.nanoTime() - primaryKeyStartNanos;
                        long updateStartNanos = System.nanoTime();
                        applyUpdates(rowBatch, sourceFileId, rowGroupId, rowOffset, tagColumns,
                                schema);
                        updateNanos += System.nanoTime() - updateStartNanos;
                        attachHiddenColumn(rowBatch);
                        long writerStartNanos = System.nanoTime();
                        boolean rowGroupRemainsOpen = writer.addRowBatch(rowBatch);
                        writerNanos += System.nanoTime() - writerStartNanos;
                        outputPosition.advance(rowBatch.size, !rowGroupRemainsOpen);
                        rowOffset += rowBatch.size;
                        if (sourceBatchCount % 100 == 0)
                        {
                            System.out.println("[PIXELS_COMPACTION_ROW_PROGRESS] source=" + sourcePath +
                                    " rows=" + sourceRows +
                                    " read_ms=" + readNanos / 1_000_000.0 +
                                    " primary_key_ms=" + primaryKeyNanos / 1_000_000.0 +
                                    " update_ms=" + updateNanos / 1_000_000.0 +
                                    " writer_ms=" + writerNanos / 1_000_000.0);
                        }
                        if (pendingPrimaryKeys.size() >= MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC)
                        {
                            resolveAndRecordPrimaryRows(pendingPrimaryKeys, pendingOutputLocations);
                        }
                    }
                    if (rowBatch.endOfFile)
                    {
                        break;
                    }
                }
            }
            resolveAndRecordPrimaryRows(pendingPrimaryKeys, pendingOutputLocations);
            System.out.println("[PIXELS_COMPACTION_ROW_METRIC] source=" + sourcePath +
                    " rows=" + sourceRows +
                    " batches=" + sourceBatchCount +
                    " read_ms=" + readNanos / 1_000_000.0 +
                    " primary_key_ms=" + primaryKeyNanos / 1_000_000.0 +
                    " update_ms=" + updateNanos / 1_000_000.0 +
                    " writer_ms=" + writerNanos / 1_000_000.0);
        }
        finally
        {
            reader.close();
        }
    }

    private void resolveAndRecordPrimaryRows(
            List<IndexProto.IndexKey> primaryKeys,
            List<IndexProto.RowLocation> outputLocations)
            throws IOException
    {
        if (primaryKeys.isEmpty())
        {
            return;
        }
        try
        {
            long start = System.nanoTime();
            List<IndexProto.ResolvePrimaryEntry> resolvedEntries = indexService.resolvePrimaryEntries(
                    primaryTableId, primaryIndexId, primaryKeys);
            primaryResolveNanos += System.nanoTime() - start;
            primaryResolveRpcCount++;
            primaryResolveRowCount += resolvedEntries.size();
            recordResolvedRows(resolvedEntries, outputLocations, relocatedLocations, primaryKeysByRowId);
        }
        catch (IndexException e)
        {
            throw new IOException("failed to resolve primary rows during tag-aware compaction", e);
        }
        finally
        {
            primaryKeys.clear();
            outputLocations.clear();
        }
    }

    static void recordResolvedRows(
            List<IndexProto.ResolvePrimaryEntry> resolvedEntries,
            List<IndexProto.RowLocation> outputLocations,
            Map<Long, IndexProto.RowLocation> relocatedLocations,
            Map<Long, IndexProto.IndexKey> primaryKeysByRowId)
    {
        requireNonNull(resolvedEntries, "resolvedEntries is null");
        requireNonNull(outputLocations, "outputLocations is null");
        requireNonNull(relocatedLocations, "relocatedLocations is null");
        requireNonNull(primaryKeysByRowId, "primaryKeysByRowId is null");
        if (resolvedEntries.size() != outputLocations.size())
        {
            throw new IllegalArgumentException("primary resolve response size does not match the source batch");
        }
        for (int i = 0; i < resolvedEntries.size(); i++)
        {
            IndexProto.ResolvePrimaryEntry resolved = resolvedEntries.get(i);
            if (resolved.getRowId() < 0 || !resolved.hasIndexKey())
            {
                throw new IllegalArgumentException("primary resolve response contains an invalid row");
            }
            relocatedLocations.put(resolved.getRowId(), outputLocations.get(i));
            primaryKeysByRowId.put(resolved.getRowId(), resolved.getIndexKey());
        }
    }

    private int[] resolvePrimaryKeyOrdinals(TypeDescription schema)
    {
        int[] ordinals = new int[primaryKeyColumnNames.size()];
        List<String> fieldNames = schema.getFieldNames();
        for (int i = 0; i < primaryKeyColumnNames.size(); i++)
        {
            String primaryKeyColumnName = primaryKeyColumnNames.get(i);
            int ordinal = fieldNames.indexOf(primaryKeyColumnName);
            if (ordinal < 0)
            {
                for (int candidate = 0; candidate < fieldNames.size(); candidate++)
                {
                    if (fieldNames.get(candidate).equalsIgnoreCase(primaryKeyColumnName))
                    {
                        ordinal = candidate;
                        break;
                    }
                }
            }
            if (ordinal < 0)
            {
                throw new IllegalArgumentException(
                        "primary-key column is not present in the Pixels schema: " + primaryKeyColumnName);
            }
            ordinals[i] = ordinal;
        }
        return ordinals;
    }

    Map<Long, IndexProto.RowLocation> getRelocatedLocations()
    {
        return Map.copyOf(relocatedLocations);
    }

    static final class OutputRowPosition
    {
        private int rowGroupId;
        private int rowOffset;

        IndexProto.RowLocation location(long fileId, int position)
        {
            return IndexProto.RowLocation.newBuilder()
                    .setFileId(fileId)
                    .setRgId(rowGroupId)
                    .setRgRowOffset(rowOffset + position)
                    .build();
        }

        void advance(int rows, boolean rowGroupClosed)
        {
            if (rowGroupClosed)
            {
                rowGroupId++;
                rowOffset = 0;
            }
            else
            {
                rowOffset += rows;
            }
        }
    }

    private void applyUpdates(
            VectorizedRowBatch rowBatch,
            long fileId,
            int rowGroupId,
            int rowOffset,
            Map<String, Integer> tagColumns,
            TypeDescription schema)
    {
        for (int position = 0; position < rowBatch.size; position++)
        {
            PixelsTagCompactionUpdateMap.Location location =
                    new PixelsTagCompactionUpdateMap.Location(fileId, rowGroupId, rowOffset + position);
            Map<String, byte[]> columnUpdates = updates.get(location);
            if (columnUpdates != null)
            {
                for (Map.Entry<String, byte[]> update : columnUpdates.entrySet())
                {
                    int tagColumn = tagColumns.get(update.getKey());
                    setValue(rowBatch.cols[tagColumn], schema.getChildren().get(tagColumn),
                            position, update.getValue());
                }
            }
        }
    }

    private static void attachHiddenColumn(VectorizedRowBatch rowBatch)
    {
        if (rowBatch.hiddenColumnVector == null)
        {
            throw new IllegalArgumentException("source Pixels file has no readable hidden column");
        }
        rowBatch.cols = Arrays.copyOf(rowBatch.cols, rowBatch.cols.length + 1);
        rowBatch.cols[rowBatch.cols.length - 1] = rowBatch.hiddenColumnVector;
        rowBatch.numCols = rowBatch.cols.length;
        rowBatch.projectionSize = rowBatch.cols.length;
    }

    private static void setValue(ColumnVector target, TypeDescription type, int position, byte[] value)
    {
        target.isNull[position] = false;
        switch (type.getCategory())
        {
            case VARCHAR:
            case CHAR:
            case STRING:
            case BINARY:
            case VARBINARY:
                ((BinaryColumnVector) target).setVal(position, value);
                return;
            case BOOLEAN:
                ((BooleanColumnVector) target).vector[position] = (byte) (value[0] != 0 ? 1 : 0);
                return;
            case BYTE:
                ((ByteColumnVector) target).vector[position] = value[0];
                return;
            case SHORT:
                ((ShortColumnVector) target).vector[position] = ByteBuffer.wrap(value).getShort();
                return;
            case INT:
                ((IntColumnVector) target).vector[position] = ByteBuffer.wrap(value).getInt();
                return;
            case LONG:
                ((LongColumnVector) target).vector[position] = ByteBuffer.wrap(value).getLong();
                return;
            case FLOAT:
                ((FloatColumnVector) target).vector[position] = ByteBuffer.wrap(value).getInt();
                return;
            case DOUBLE:
                ((DoubleColumnVector) target).vector[position] = ByteBuffer.wrap(value).getLong();
                return;
            case DECIMAL:
                if (type.getPrecision() <= TypeDescription.MAX_SHORT_DECIMAL_PRECISION)
                {
                    if (value.length != Long.BYTES)
                    {
                        throw new IllegalArgumentException("short decimal tag requires " +
                                Long.BYTES + " bytes, got " + value.length);
                    }
                    ((DecimalColumnVector) target).vector[position] = ByteBuffer.wrap(value).getLong();
                }
                else
                {
                    if (value.length != 2 * Long.BYTES)
                    {
                        throw new IllegalArgumentException("long decimal tag requires " +
                                (2 * Long.BYTES) + " bytes, got " + value.length);
                    }
                    ByteBuffer buffer = ByteBuffer.wrap(value);
                    LongDecimalColumnVector decimal = (LongDecimalColumnVector) target;
                    decimal.vector[position * 2] = buffer.getLong();
                    decimal.vector[position * 2 + 1] = buffer.getLong();
                }
                return;
            default:
                throw new IllegalArgumentException("unsupported tag column type: " + type.getCategory());
        }
    }
}
