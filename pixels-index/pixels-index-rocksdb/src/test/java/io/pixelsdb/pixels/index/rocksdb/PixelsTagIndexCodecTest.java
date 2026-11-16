/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PixelsTagIndexCodecTest
{
    @Test
    void roundTripPreservesCompositePrimaryKeys()
    {
        List<byte[]> primaryKeys = List.of(
                new byte[] {1, 2, 3},
                new byte[] {},
                new byte[] {-1, 0, 127});

        byte[] encoded = PixelsTagIndexCodec.encode(primaryKeys);

        List<byte[]> decoded = PixelsTagIndexCodec.decode(encoded);
        assertEquals(primaryKeys.size(), decoded.size());
        for (int i = 0; i < primaryKeys.size(); i++)
        {
            assertArrayEquals(primaryKeys.get(i), decoded.get(i));
        }
    }

    @Test
    void appendMergesPrimaryKeysWithoutDuplicatingExistingEntries()
    {
        byte[] existing = PixelsTagIndexCodec.encode(List.of(new byte[] {1}, new byte[] {2}));

        byte[] merged = PixelsTagIndexCodec.append(
                existing,
                List.of(new byte[] {2}, new byte[] {3}));

        List<byte[]> decoded = PixelsTagIndexCodec.decode(merged);
        assertEquals(3, decoded.size());
        assertArrayEquals(new byte[] {1}, decoded.get(0));
        assertArrayEquals(new byte[] {2}, decoded.get(1));
        assertArrayEquals(new byte[] {3}, decoded.get(2));
    }

    @Test
    void appendMergesEightBytePrimaryKeysWithoutDuplicatingEntries()
    {
        List<byte[]> existingKeys = List.of(longKey(1L), longKey(2L));
        List<byte[]> incomingKeys = List.of(longKey(2L), longKey(3L), longKey(3L));

        List<byte[]> merged = PixelsTagIndexCodec.append(existingKeys, incomingKeys);

        assertEquals(3, merged.size());
        assertArrayEquals(longKey(1L), merged.get(0));
        assertArrayEquals(longKey(2L), merged.get(1));
        assertArrayEquals(longKey(3L), merged.get(2));
    }

    private static byte[] longKey(long value)
    {
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
