/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import com.google.protobuf.ByteString;
import io.pixelsdb.pixels.common.index.service.IndexService;
import io.pixelsdb.pixels.common.metadata.MetadataService;
import io.pixelsdb.pixels.common.physical.Storage;
import io.pixelsdb.pixels.core.TypeDescription;
import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class PixelsTagCompactorSnapshotTest
{
    @Test
    void resolvesTagRowsInBoundedBatchesAndProducesOneReusableSnapshot() throws Exception
    {
        int rowCount = PixelsTagCompactor.MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC + 1;
        List<IndexProto.TagIndexEntry> tagEntries = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            tagEntries.add(IndexProto.TagIndexEntry.newBuilder()
                    .setTag(ByteString.copyFromUtf8("tag-" + row))
                    .addPrimaryKeys(ByteString.copyFromUtf8("key-" + row))
                    .build());
        }

        IndexService indexService = mock(IndexService.class);
        List<Integer> requestSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<IndexProto.IndexKey> keys = invocation.getArgument(2);
            requestSizes.add(keys.size());
            List<IndexProto.ResolvePrimaryEntry> resolved = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++)
            {
                IndexProto.IndexKey key = keys.get(i);
                resolved.add(IndexProto.ResolvePrimaryEntry.newBuilder()
                        .setIndexKey(key)
                        .setRowId(rowId(key))
                        .setRowLocation(IndexProto.RowLocation.newBuilder()
                                .setFileId(100L)
                                .setRgId(0)
                                .setRgRowOffset((int) rowId(key))
                                .build())
                        .build());
            }
            return resolved;
        }).when(indexService).resolvePrimaryEntries(anyLong(), anyLong(), any());

        PixelsTagCompactor.TagIndexSnapshot snapshot = PixelsTagCompactor.prepareTagIndexSnapshot(
                tagEntries, 1L, 2L, indexService);

        assertEquals(List.of(PixelsTagCompactor.MAX_PRIMARY_RESOLUTION_KEYS_PER_RPC, 1), requestSizes);
        assertEquals(rowCount, snapshot.getTaggedRowCount());
        assertEquals(2, snapshot.getPrimaryResolveRpcCount());
        assertEquals(rowCount, snapshot.getPrimaryResolveRowCount());
        assertEquals(rowCount, snapshot.getUpdates().size());
    }

    @Test
    void doesNotCopyTheFullTagSnapshotIntoEveryCompactionTask() throws Exception
    {
        IndexService indexService = mock(IndexService.class);
        doAnswer(invocation -> {
            List<IndexProto.IndexKey> keys = invocation.getArgument(2);
            return keys.stream()
                    .map(key -> IndexProto.ResolvePrimaryEntry.newBuilder()
                            .setIndexKey(key)
                            .setRowId(7L)
                            .setRowLocation(IndexProto.RowLocation.newBuilder()
                                    .setFileId(100L)
                                    .setRgId(0)
                                    .setRgRowOffset(0)
                                    .build())
                            .build())
                    .toList();
        }).when(indexService).resolvePrimaryEntries(anyLong(), anyLong(), any());
        PixelsTagCompactor.TagIndexSnapshot snapshot = PixelsTagCompactor.prepareTagIndexSnapshot(
                List.of(IndexProto.TagIndexEntry.newBuilder()
                        .setTag(ByteString.copyFromUtf8("vip"))
                        .addPrimaryKeys(ByteString.copyFromUtf8("key"))
                        .build()), 1L, 2L, indexService);

        PixelsTagCompactor compactor = PixelsTagCompactor.create(
                List.of("source"), mock(Storage.class), mock(Storage.class), "target", "tag",
                snapshot, 1L, 2L, indexService, List.of("id"),
                List.of(TypeDescription.fromString("long")), mock(MetadataService.class), 1L, (short) 1);

        assertEquals(0, compactor.getPrimaryKeysByRowId().size());
    }

    private static long rowId(IndexProto.IndexKey key)
    {
        String value = key.getKey().toStringUtf8();
        return Long.parseLong(value.substring("key-".length()));
    }
}
