/*
 * Copyright 2025 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.common.index;

import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMainIndexBuffer
{
    @Test
    void relocationReplacesAnOlderBufferedFileLocation()
    {
        MainIndexCache cache = new MainIndexCache();
        MainIndexBuffer buffer = new MainIndexBuffer(cache);
        IndexProto.RowLocation oldLocation = IndexProto.RowLocation.newBuilder()
                .setFileId(10L).setRgId(0).setRgRowOffset(7).build();
        IndexProto.RowLocation newLocation = IndexProto.RowLocation.newBuilder()
                .setFileId(11L).setRgId(0).setRgRowOffset(3).build();

        cache.admitRange(new RowIdRange(42L, 43L, 10L, 0, 7, 8));
        assertTrue(buffer.insert(42L, oldLocation));
        assertTrue(buffer.insert(42L, newLocation));

        assertEquals(newLocation, buffer.lookup(42L));
    }
}
