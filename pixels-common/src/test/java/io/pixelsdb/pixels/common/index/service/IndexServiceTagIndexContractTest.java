/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.common.index.service;

import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexServiceTagIndexContractTest
{
    @Test
    void representsTagUpdatesAndResolvedPrimaryRowsInTheExistingIndexProtocol()
    {
        IndexProto.TagIndexUpdate update = IndexProto.TagIndexUpdate.newBuilder()
                .setTag(com.google.protobuf.ByteString.copyFromUtf8("new"))
                .addPrimaryKeys(com.google.protobuf.ByteString.copyFromUtf8("1"))
                .build();
        IndexProto.ResolvePrimaryEntry resolved = IndexProto.ResolvePrimaryEntry.newBuilder()
                .setRowId(7L)
                .setRowLocation(IndexProto.RowLocation.newBuilder().setFileId(11L).build())
                .build();

        assertEquals("new", update.getTag().toStringUtf8());
        assertEquals(List.of("1"), update.getPrimaryKeysList().stream()
                .map(com.google.protobuf.ByteString::toStringUtf8).toList());
        assertEquals(7L, resolved.getRowId());
        assertEquals(11L, resolved.getRowLocation().getFileId());
    }
}
