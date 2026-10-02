/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PixelsTagCompactionUpdateMapTest
{
    @Test
    void resolvesTagEntriesToPhysicalRows()
    {
        PixelsTagCompactionUpdateMap.Location first =
                new PixelsTagCompactionUpdateMap.Location(10L, 2, 4);
        PixelsTagCompactionUpdateMap.Location second =
                new PixelsTagCompactionUpdateMap.Location(10L, 2, 5);

        Map<PixelsTagCompactionUpdateMap.Location, byte[]> updates =
                PixelsTagCompactionUpdateMap.build(
                        List.of(new PixelsTagCompactionUpdateMap.Entry(
                                "vip".getBytes(StandardCharsets.UTF_8),
                                List.of(rowId(1), rowId(2)))),
                        Map.of(1L, first, 2L, second));

        assertEquals(2, updates.size());
        assertArrayEquals("vip".getBytes(StandardCharsets.UTF_8), updates.get(first));
        assertArrayEquals("vip".getBytes(StandardCharsets.UTF_8), updates.get(second));
    }

    @Test
    void rejectsConflictingTagsForTheSamePhysicalRow()
    {
        PixelsTagCompactionUpdateMap.Location location =
                new PixelsTagCompactionUpdateMap.Location(10L, 2, 4);

        assertThrows(IllegalArgumentException.class, () ->
                PixelsTagCompactionUpdateMap.build(
                        List.of(
                                new PixelsTagCompactionUpdateMap.Entry(
                                        "gold".getBytes(StandardCharsets.UTF_8), List.of(rowId(1))),
                                new PixelsTagCompactionUpdateMap.Entry(
                                        "vip".getBytes(StandardCharsets.UTF_8), List.of(rowId(1)))),
                        Map.of(1L, location)));
    }

    private static byte[] rowId(long rowId)
    {
        return ByteBuffer.allocate(Long.BYTES).putLong(rowId).array();
    }
}
