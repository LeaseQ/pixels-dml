/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import io.pixelsdb.pixels.common.exception.MainIndexException;
import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.common.exception.SinglePointIndexException;
import io.pixelsdb.pixels.common.index.IndexOption;
import io.pixelsdb.pixels.common.index.MainIndex;
import io.pixelsdb.pixels.common.index.SinglePointIndex;
import io.pixelsdb.pixels.common.index.SinglePointIndexFactory;
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
import io.pixelsdb.pixels.core.vector.BinaryColumnVector;
import io.pixelsdb.pixels.core.vector.BooleanColumnVector;
import io.pixelsdb.pixels.core.vector.ByteColumnVector;
import io.pixelsdb.pixels.core.vector.ColumnVector;
import io.pixelsdb.pixels.core.vector.DoubleColumnVector;
import io.pixelsdb.pixels.core.vector.FloatColumnVector;
import io.pixelsdb.pixels.core.vector.IntColumnVector;
import io.pixelsdb.pixels.core.vector.LongColumnVector;
import io.pixelsdb.pixels.core.vector.ShortColumnVector;
import io.pixelsdb.pixels.core.vector.VectorizedRowBatch;
import io.pixelsdb.pixels.index.IndexProto;
import io.pixelsdb.pixels.index.rocksdb.PixelsTagIndex;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import static java.util.Objects.requireNonNull;

/** Rewrites source Pixels rows while applying the durable label tag index. */
final class PixelsTagCompactor
{
    private static final int READ_BATCH_SIZE = 1024;

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
    private final Map<PixelsTagCompactionUpdateMap.Location, byte[]> updates;
    private final Map<PixelsTagCompactionUpdateMap.Location, Long> rowIdsBySourceLocation;
    private final Map<Long, IndexProto.RowLocation> relocatedLocations = new HashMap<>();
    private final MetadataService metadataService;
    private final long blockSize;
    private final short replication;

    private PixelsTagCompactor(
            List<String> sourcePaths,
            Storage inputStorage,
            Storage outputStorage,
            String targetPath,
            String tagColumnName,
            Map<PixelsTagCompactionUpdateMap.Location, byte[]> updates,
            Map<PixelsTagCompactionUpdateMap.Location, Long> rowIdsBySourceLocation,
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
        this.rowIdsBySourceLocation = Map.copyOf(requireNonNull(rowIdsBySourceLocation,
                "rowIdsBySourceLocation is null"));
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
            List<PixelsTagIndex.Entry> tagEntries,
            long primaryTableId,
            long primaryIndexId,
            MainIndex mainIndex,
            MetadataService metadataService,
            long blockSize,
            short replication)
            throws MainIndexException, SinglePointIndexException
    {
        requireNonNull(tagEntries, "tagEntries is null");
        requireNonNull(mainIndex, "mainIndex is null");
        requireNonNull(metadataService, "metadataService is null");

        Set<Long> rowIds = new HashSet<>();
        Map<ByteBuffer, Long> primaryKeyRowIds = new HashMap<>();
        for (PixelsTagIndex.Entry entry : tagEntries)
        {
            for (byte[] encodedPrimaryKey : entry.primaryKeys())
            {
                long rowId = lookupPrimaryRowId(primaryTableId, primaryIndexId,
                        ByteString.copyFrom(encodedPrimaryKey), (key, bucketId) -> {
                            SinglePointIndex primaryIndex = SinglePointIndexFactory.Instance().getSinglePointIndex(
                                    primaryTableId, primaryIndexId,
                                    IndexOption.builder().vNodeId(bucketId).build());
                            return primaryIndex.getUniqueRowId(key);
                        });
                if (rowId < 0)
                {
                    throw new IllegalArgumentException("tag index primary key has no primary-index row id");
                }
                primaryKeyRowIds.put(ByteBuffer.wrap(encodedPrimaryKey), rowId);
                rowIds.add(rowId);
            }
        }

        Map<Long, PixelsTagCompactionUpdateMap.Location> rowLocations = new HashMap<>();
        for (Long rowId : rowIds)
        {
            IndexProto.RowLocation location = mainIndex.getLocation(rowId);
            if (location == null)
            {
                throw new IllegalArgumentException("tag index row id has no main-index location: " + rowId);
            }
            rowLocations.put(rowId, new PixelsTagCompactionUpdateMap.Location(
                    location.getFileId(), location.getRgId(), location.getRgRowOffset()));
        }

        Map<PixelsTagCompactionUpdateMap.Location, Long> rowIdsBySourceLocation = new HashMap<>();
        for (Map.Entry<Long, PixelsTagCompactionUpdateMap.Location> entry : rowLocations.entrySet())
        {
            rowIdsBySourceLocation.put(entry.getValue(), entry.getKey());
        }

        List<PixelsTagCompactionUpdateMap.Entry> entries = new ArrayList<>(tagEntries.size());
        for (PixelsTagIndex.Entry entry : tagEntries)
        {
            List<byte[]> encodedRowIds = new ArrayList<>(entry.primaryKeys().size());
            for (byte[] encodedPrimaryKey : entry.primaryKeys())
            {
                Long rowId = primaryKeyRowIds.get(ByteBuffer.wrap(encodedPrimaryKey));
                if (rowId == null)
                {
                    throw new IllegalArgumentException("tag index primary key resolution disappeared");
                }
                encodedRowIds.add(ByteBuffer.allocate(Long.BYTES).putLong(rowId).array());
            }
            entries.add(new PixelsTagCompactionUpdateMap.Entry(entry.tag(), encodedRowIds));
        }
        Map<PixelsTagCompactionUpdateMap.Location, byte[]> updates =
                PixelsTagCompactionUpdateMap.build(entries, rowLocations);
        return new PixelsTagCompactor(
                sourcePaths, inputStorage, outputStorage, targetPath, tagColumnName,
                updates, rowIdsBySourceLocation, metadataService, blockSize, replication);
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

        int tagColumn = schema.getFieldNames().indexOf(tagColumnName);
        if (tagColumn < 0)
        {
            throw new IllegalArgumentException("tag column is not present in Pixels schema: " + tagColumnName);
        }

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
                compactSource(schema, tagColumn, writer, sourcePath, footerCache, outputPosition);
            }
            int rowGroupCount = writer.getNumRowGroup();
            writer.close();
            return rowGroupCount;
        }
        catch (IOException | RuntimeException e)
        {
            writer.abort();
            throw e;
        }
    }

    private void compactSource(
            TypeDescription schema,
            int tagColumn,
            PixelsWriter writer,
            String sourcePath,
            PixelsFooterCache footerCache,
            OutputRowPosition outputPosition)
            throws IOException, MetadataException
    {
        long sourceFileId = metadataService.getFileId(sourcePath);
        PixelsReader reader = PixelsReaderImpl.newBuilder()
                .setStorage(inputStorage)
                .setPath(sourcePath)
                .setPixelsFooterCache(footerCache)
                .build();
        try
        {
            String[] columns = schema.getFieldNames().toArray(new String[0]);
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
                    VectorizedRowBatch rowBatch = recordReader.readBatch(READ_BATCH_SIZE);
                    if (rowBatch.size > 0)
                    {
                        recordRelocatedRows(rowBatch, sourceFileId, rowGroupId, rowOffset, outputPosition);
                        applyUpdates(rowBatch, sourceFileId, rowGroupId, rowOffset, tagColumn,
                                schema.getChildren().get(tagColumn));
                        attachHiddenColumn(rowBatch);
                        boolean rowGroupRemainsOpen = writer.addRowBatch(rowBatch);
                        outputPosition.advance(rowBatch.size, !rowGroupRemainsOpen);
                        rowOffset += rowBatch.size;
                    }
                    if (rowBatch.endOfFile)
                    {
                        break;
                    }
                }
            }
        }
        finally
        {
            reader.close();
        }
    }

    private void recordRelocatedRows(
            VectorizedRowBatch rowBatch,
            long sourceFileId,
            int sourceRowGroupId,
            int sourceRowOffset,
            OutputRowPosition outputPosition)
    {
        for (int position = 0; position < rowBatch.size; position++)
        {
            Long rowId = rowIdsBySourceLocation.get(new PixelsTagCompactionUpdateMap.Location(
                    sourceFileId, sourceRowGroupId, sourceRowOffset + position));
            if (rowId != null)
            {
                relocatedLocations.put(rowId, outputPosition.location(0, position));
            }
        }
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
            int tagColumn,
            TypeDescription tagType)
    {
        for (int position = 0; position < rowBatch.size; position++)
        {
            PixelsTagCompactionUpdateMap.Location location =
                    new PixelsTagCompactionUpdateMap.Location(fileId, rowGroupId, rowOffset + position);
            byte[] tag = updates.get(location);
            if (tag != null)
            {
                setValue(rowBatch.cols[tagColumn], tagType, position, tag);
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
            default:
                throw new IllegalArgumentException("unsupported tag column type: " + type.getCategory());
        }
    }
}
