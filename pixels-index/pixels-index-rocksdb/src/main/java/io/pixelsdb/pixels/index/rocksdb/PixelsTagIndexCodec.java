/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Encodes the primary-key list stored as the value of a tag index entry.
 *
 * <p>The value is versioned and length-delimited so composite or variable
 * length primary keys remain unambiguous. The tag itself is intentionally not
 * encoded here: callers use the tag bytes as the RocksDB key.</p>
 */
public final class PixelsTagIndexCodec
{
    private static final int FORMAT_VERSION = 1;

    private PixelsTagIndexCodec() {}

    public static byte[] encode(List<byte[]> primaryKeys)
    {
        requireNonNull(primaryKeys, "primaryKeys is null");
        long size = Integer.BYTES * 2L;
        for (byte[] primaryKey : primaryKeys)
        {
            requireNonNull(primaryKey, "primary key is null");
            size += Integer.BYTES + primaryKey.length;
        }
        if (size > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("encoded primary-key list is too large");
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        buffer.putInt(FORMAT_VERSION);
        buffer.putInt(primaryKeys.size());
        for (byte[] primaryKey : primaryKeys)
        {
            buffer.putInt(primaryKey.length);
            buffer.put(primaryKey);
        }
        return buffer.array();
    }

    public static List<byte[]> decode(byte[] encoded)
    {
        requireNonNull(encoded, "encoded is null");
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        if (buffer.remaining() < Integer.BYTES * 2)
        {
            throw new IllegalArgumentException("encoded primary-key list is truncated");
        }
        int version = buffer.getInt();
        if (version != FORMAT_VERSION)
        {
            throw new IllegalArgumentException("unsupported tag index value version: " + version);
        }
        int count = buffer.getInt();
        if (count < 0)
        {
            throw new IllegalArgumentException("negative primary-key count");
        }

        List<byte[]> primaryKeys = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            if (buffer.remaining() < Integer.BYTES)
            {
                throw new IllegalArgumentException("encoded primary-key list is truncated");
            }
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining())
            {
                throw new IllegalArgumentException("invalid primary-key length: " + length);
            }
            byte[] primaryKey = new byte[length];
            buffer.get(primaryKey);
            primaryKeys.add(primaryKey);
        }
        if (buffer.hasRemaining())
        {
            throw new IllegalArgumentException("encoded primary-key list has trailing bytes");
        }
        return List.copyOf(primaryKeys);
    }

    public static byte[] append(byte[] existing, List<byte[]> primaryKeys)
    {
        requireNonNull(primaryKeys, "primaryKeys is null");
        List<byte[]> merged = new ArrayList<>();
        if (existing != null)
        {
            merged.addAll(decode(existing));
        }
        for (byte[] primaryKey : primaryKeys)
        {
            requireNonNull(primaryKey, "primary key is null");
            boolean alreadyPresent = merged.stream().anyMatch(existingKey -> Arrays.equals(existingKey, primaryKey));
            if (!alreadyPresent)
            {
                merged.add(primaryKey.clone());
            }
        }
        return encode(merged);
    }
}
