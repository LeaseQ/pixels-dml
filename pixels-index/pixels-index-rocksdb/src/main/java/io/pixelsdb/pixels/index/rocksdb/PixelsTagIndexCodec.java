/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        return encode(append(existing == null ? List.of() : decode(existing), primaryKeys));
    }

    /**
     * Merge decoded primary keys without the quadratic scan used by the
     * original encoded-value append path.  The returned list owns copies of
     * newly added keys and preserves the existing order.
     */
    static List<byte[]> append(List<byte[]> existing, List<byte[]> primaryKeys)
    {
        requireNonNull(existing, "existing is null");
        requireNonNull(primaryKeys, "primaryKeys is null");
        if (allEightByteKeys(existing, primaryKeys))
        {
            return appendEightByteKeys(existing, primaryKeys);
        }
        List<byte[]> merged = new ArrayList<>(existing.size() + primaryKeys.size());
        Set<ByteBuffer> seen = new HashSet<>(Math.max(16, existing.size() + primaryKeys.size()));
        for (byte[] existingKey : existing)
        {
            requireNonNull(existingKey, "existing primary key is null");
            merged.add(existingKey);
            seen.add(ByteBuffer.wrap(existingKey));
        }
        for (byte[] primaryKey : primaryKeys)
        {
            requireNonNull(primaryKey, "primary key is null");
            if (seen.add(ByteBuffer.wrap(primaryKey)))
            {
                merged.add(primaryKey.clone());
            }
        }
        return merged;
    }

    /**
     * Checks whether a primary-key vector contains no duplicates.  Shared-key
     * UPDATEs use this once for the whole vector before reusing the result for
     * every changed column.
     */
    public static boolean areUnique(List<byte[]> primaryKeys)
    {
        requireNonNull(primaryKeys, "primaryKeys is null");
        if (allEightByteKeys(List.of(), primaryKeys))
        {
            int capacity = tableSizeFor(Math.max(16L, (long) primaryKeys.size() * 2));
            long[] keys = new long[capacity];
            boolean[] occupied = new boolean[capacity];
            for (byte[] primaryKey : primaryKeys)
            {
                requireNonNull(primaryKey, "primary key is null");
                if (!add(keys, occupied, readLong(primaryKey)))
                {
                    return false;
                }
            }
            return true;
        }
        Set<ByteBuffer> seen = new HashSet<>(Math.max(16, primaryKeys.size()));
        for (byte[] primaryKey : primaryKeys)
        {
            requireNonNull(primaryKey, "primary key is null");
            if (!seen.add(ByteBuffer.wrap(primaryKey)))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean allEightByteKeys(List<byte[]> existing, List<byte[]> primaryKeys)
    {
        for (byte[] primaryKey : existing)
        {
            if (primaryKey == null || primaryKey.length != Long.BYTES)
            {
                return false;
            }
        }
        for (byte[] primaryKey : primaryKeys)
        {
            if (primaryKey == null || primaryKey.length != Long.BYTES)
            {
                return false;
            }
        }
        long total = (long) existing.size() + primaryKeys.size();
        // Keep the specialized table bounded.  Larger or variable-length
        // inputs use the general byte-array path below.
        return total <= 8_000_000L;
    }

    private static List<byte[]> appendEightByteKeys(List<byte[]> existing, List<byte[]> primaryKeys)
    {
        int expectedSize = (int) ((long) existing.size() + primaryKeys.size());
        int capacity = tableSizeFor(Math.max(16L, (long) expectedSize * 2));
        long[] keys = new long[capacity];
        boolean[] occupied = new boolean[capacity];
        List<byte[]> merged = new ArrayList<>(expectedSize);
        for (byte[] primaryKey : existing)
        {
            long key = readLong(primaryKey);
            if (add(keys, occupied, key))
            {
                merged.add(primaryKey);
            }
        }
        for (byte[] primaryKey : primaryKeys)
        {
            long key = readLong(primaryKey);
            if (add(keys, occupied, key))
            {
                merged.add(primaryKey.clone());
            }
        }
        return merged;
    }

    private static int tableSizeFor(long expectedSize)
    {
        int capacity = 1;
        while (capacity < expectedSize && capacity < (1 << 30))
        {
            capacity <<= 1;
        }
        return capacity;
    }

    private static boolean add(long[] keys, boolean[] occupied, long key)
    {
        int mask = keys.length - 1;
        int slot = hash(key) & mask;
        while (occupied[slot])
        {
            if (keys[slot] == key)
            {
                return false;
            }
            slot = (slot + 1) & mask;
        }
        occupied[slot] = true;
        keys[slot] = key;
        return true;
    }

    private static int hash(long key)
    {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) (key ^ (key >>> 32));
    }

    private static long readLong(byte[] bytes)
    {
        return ((long) (bytes[0] & 0xff) << 56) |
                ((long) (bytes[1] & 0xff) << 48) |
                ((long) (bytes[2] & 0xff) << 40) |
                ((long) (bytes[3] & 0xff) << 32) |
                ((long) (bytes[4] & 0xff) << 24) |
                ((long) (bytes[5] & 0xff) << 16) |
                ((long) (bytes[6] & 0xff) << 8) |
                (bytes[7] & 0xffL);
    }
}
