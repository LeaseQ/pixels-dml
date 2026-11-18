/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Encodes a tag column identity into the RocksDB logical tag key. */
public final class TagIndexKeyCodec
{
    private static final byte MARKER = 0x7F;
    private static final int HEADER_SIZE = 1 + Integer.BYTES;

    private TagIndexKeyCodec() {}

    public static byte[] encode(String columnName, byte[] tag)
    {
        if (columnName == null || columnName.isEmpty())
        {
            return tag.clone();
        }
        byte[] column = columnName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + column.length + tag.length);
        buffer.put(MARKER).putInt(column.length).put(column).put(tag);
        return buffer.array();
    }

    public static byte[] prefix(String columnName, byte[] tagPrefix)
    {
        return encode(columnName, tagPrefix);
    }

    public static boolean isEncoded(byte[] key)
    {
        if (key == null || key.length < HEADER_SIZE || key[0] != MARKER)
        {
            return false;
        }
        int columnLength = ByteBuffer.wrap(key, 1, Integer.BYTES).getInt();
        return columnLength >= 0 && HEADER_SIZE + columnLength <= key.length;
    }

    public static String columnName(byte[] key)
    {
        if (!isEncoded(key))
        {
            return "";
        }
        int columnLength = ByteBuffer.wrap(key, 1, Integer.BYTES).getInt();
        return new String(key, HEADER_SIZE, columnLength, StandardCharsets.UTF_8);
    }

    public static byte[] tag(byte[] key)
    {
        if (!isEncoded(key))
        {
            return key.clone();
        }
        int columnLength = ByteBuffer.wrap(key, 1, Integer.BYTES).getInt();
        return Arrays.copyOfRange(key, HEADER_SIZE + columnLength, key.length);
    }
}
