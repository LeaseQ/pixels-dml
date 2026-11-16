/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import com.google.protobuf.ByteString;
import io.pixelsdb.pixels.common.index.service.IndexService;
import io.pixelsdb.pixels.common.utils.IndexUtils;
import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CompactExecutorIndexRelocationTest
{
    @Test
    void groupsRelocationsByPrimaryIndexBucketAndUsesBatchCalls() throws Exception
    {
        IndexService indexService = mock(IndexService.class);
        List<List<IndexProto.PrimaryIndexEntry>> batches = new ArrayList<>();
        doAnswer(invocation -> {
            batches.add(List.copyOf(invocation.getArgument(2)));
            return true;
        }).when(indexService).putPrimaryIndexEntries(anyLong(), anyLong(), anyList(), any());

        Map<Long, IndexProto.RowLocation> relocatedLocations = new LinkedHashMap<>();
        Map<Long, IndexProto.IndexKey> primaryKeysByRowId = new LinkedHashMap<>();
        int firstBucket = -1;
        int secondBucket = -1;
        for (int candidate = 0; secondBucket < 0; candidate++)
        {
            ByteString key = ByteString.copyFromUtf8("primary-" + candidate);
            int bucket = IndexUtils.getBucketIdFromByteBuffer(key);
            if (firstBucket < 0)
            {
                firstBucket = bucket;
            }
            else if (bucket != firstBucket)
            {
                secondBucket = bucket;
            }
            long rowId = candidate + 1L;
            IndexProto.IndexKey indexKey = IndexProto.IndexKey.newBuilder()
                    .setTableId(101L)
                    .setIndexId(202L)
                    .setKey(key)
                    .setTimestamp(Long.MAX_VALUE)
                    .build();
            primaryKeysByRowId.put(rowId, indexKey);
            relocatedLocations.put(rowId, IndexProto.RowLocation.newBuilder()
                    .setRgId(candidate)
                    .setRgRowOffset(candidate + 10)
                    .build());
        }

        int relocationCount = CompactExecutor.updatePrimaryIndexLocations(
                indexService, 101L, 202L, 303L, relocatedLocations, primaryKeysByRowId);

        assertEquals(2, relocationCount);
        assertEquals(2, batches.size());
        assertEquals(relocatedLocations.size(), batches.stream().mapToInt(List::size).sum());
        for (List<IndexProto.PrimaryIndexEntry> batch : batches)
        {
            assertFalse(batch.isEmpty());
            int bucket = IndexUtils.getBucketIdFromByteBuffer(batch.get(0).getIndexKey().getKey());
            assertTrue(batch.stream().allMatch(entry ->
                    IndexUtils.getBucketIdFromByteBuffer(entry.getIndexKey().getKey()) == bucket));
            assertTrue(batch.stream().allMatch(entry -> entry.getRowLocation().getFileId() == 303L));
        }
    }
}
