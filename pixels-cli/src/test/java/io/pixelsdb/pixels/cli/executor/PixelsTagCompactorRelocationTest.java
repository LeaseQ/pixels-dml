/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import com.google.protobuf.ByteString;
import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PixelsTagCompactorRelocationTest
{
    @Test
    void recordsTaggedAndUntaggedRowsFromOneResolvedBatch()
    {
        IndexProto.IndexKey taggedKey = key("tagged");
        IndexProto.IndexKey untaggedKey = key("untagged");
        List<IndexProto.ResolvePrimaryEntry> resolved = List.of(
                resolved(11L, taggedKey, 101L, 0, 0),
                resolved(12L, untaggedKey, 101L, 0, 1));
        List<IndexProto.RowLocation> outputLocations = List.of(
                location(901L, 0, 0),
                location(901L, 0, 1));
        Map<Long, IndexProto.RowLocation> relocated = new LinkedHashMap<>();
        Map<Long, IndexProto.IndexKey> keysByRowId = new LinkedHashMap<>();

        PixelsTagCompactor.recordResolvedRows(
                resolved, outputLocations, relocated, keysByRowId);

        assertEquals(outputLocations.get(0), relocated.get(11L));
        assertEquals(outputLocations.get(1), relocated.get(12L));
        assertEquals(taggedKey, keysByRowId.get(11L));
        assertEquals(untaggedKey, keysByRowId.get(12L));
    }

    @Test
    void rejectsAResolveResponseThatDoesNotPreserveBatchCardinality()
    {
        List<IndexProto.ResolvePrimaryEntry> resolved = new ArrayList<>();
        resolved.add(resolved(11L, key("only"), 101L, 0, 0));
        Map<Long, IndexProto.RowLocation> relocated = new LinkedHashMap<>();
        Map<Long, IndexProto.IndexKey> keysByRowId = new LinkedHashMap<>();

        assertThrows(IllegalArgumentException.class, () -> PixelsTagCompactor.recordResolvedRows(
                resolved, List.of(), relocated, keysByRowId));
    }

    private static IndexProto.IndexKey key(String value)
    {
        return IndexProto.IndexKey.newBuilder()
                .setTableId(1L)
                .setIndexId(2L)
                .setKey(ByteString.copyFromUtf8(value))
                .setTimestamp(Long.MAX_VALUE)
                .build();
    }

    private static IndexProto.ResolvePrimaryEntry resolved(
            long rowId,
            IndexProto.IndexKey key,
            long fileId,
            int rowGroupId,
            int rowOffset)
    {
        return IndexProto.ResolvePrimaryEntry.newBuilder()
                .setIndexKey(key)
                .setRowId(rowId)
                .setRowLocation(location(fileId, rowGroupId, rowOffset))
                .build();
    }

    private static IndexProto.RowLocation location(long fileId, int rowGroupId, int rowOffset)
    {
        return IndexProto.RowLocation.newBuilder()
                .setFileId(fileId)
                .setRgId(rowGroupId)
                .setRgRowOffset(rowOffset)
                .build();
    }
}
