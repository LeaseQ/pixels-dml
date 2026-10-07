/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import io.pixelsdb.pixels.common.exception.SinglePointIndexException;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Persistent tag index backed by RocksDB.
 *
 * <p>The tag bytes are the RocksDB key. The value is a versioned list of
 * serialized primary keys, allowing one tag to refer to multiple rows. Appends
 * are serialized per index instance and are idempotent for an identical
 * primary-key byte sequence. Appending a primary key to a new tag removes it
 * from older tags, so the latest tag wins.</p>
 */
public final class PixelsTagIndex implements Closeable
{
    /**
     * Tag records use a dedicated virtual-node namespace so they cannot be
     * decoded as primary-index records in the primary index column family.
     */
    public static final int TAG_VNODE_ID = -1;

    public static final class Entry
    {
        private final byte[] tag;
        private final List<byte[]> primaryKeys;

        private Entry(byte[] tag, List<byte[]> primaryKeys)
        {
            this.tag = tag.clone();
            this.primaryKeys = List.copyOf(primaryKeys);
        }

        public byte[] tag()
        {
            return tag.clone();
        }

        public List<byte[]> primaryKeys()
        {
            List<byte[]> copies = new ArrayList<>(primaryKeys.size());
            for (byte[] primaryKey : primaryKeys)
            {
                copies.add(primaryKey.clone());
            }
            return Collections.unmodifiableList(copies);
        }
    }

    private final RocksDB rocksDB;
    private final ColumnFamilyHandle columnFamilyHandle;
    private final WriteOptions writeOptions;
    private final boolean ownsFactoryReference;

    /**
     * Constructor used by unit tests or callers that own the RocksDB handle.
     */
    PixelsTagIndex(RocksDB rocksDB)
    {
        this.rocksDB = requireNonNull(rocksDB, "rocksDB is null");
        this.columnFamilyHandle = null;
        this.writeOptions = new WriteOptions();
        this.ownsFactoryReference = false;
    }

    /**
     * Opens the shared Pixels RocksDB column family for a tag index.
     */
    public PixelsTagIndex(long tableId, long indexId, int vNodeId)
            throws RocksDBException, SinglePointIndexException
    {
        this.columnFamilyHandle = RocksDBFactory.getOrCreateColumnFamily(tableId, indexId, TAG_VNODE_ID);
        this.rocksDB = RocksDBFactory.getOpenRocksDB();
        this.writeOptions = new WriteOptions();
        this.ownsFactoryReference = true;
    }

    public synchronized void append(byte[] tag, List<byte[]> primaryKeys) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        requireNonNull(primaryKeys, "primaryKeys is null");
        try (WriteBatch batch = new WriteBatch();
             RocksIterator iterator = columnFamilyHandle == null
                     ? rocksDB.newIterator()
                     : rocksDB.newIterator(columnFamilyHandle))
        {
            // A primary key has one current tag. Remove it from every older
            // tag before appending it to the latest tag. The synchronized
            // method makes append order the update order for this index.
            iterator.seekToFirst();
            while (iterator.isValid())
            {
                if (!Arrays.equals(iterator.key(), tag))
                {
                    List<byte[]> existingPrimaryKeys = PixelsTagIndexCodec.decode(iterator.value());
                    List<byte[]> remaining = new ArrayList<>();
                    for (byte[] existingPrimaryKey : existingPrimaryKeys)
                    {
                        if (!contains(primaryKeys, existingPrimaryKey))
                        {
                            remaining.add(existingPrimaryKey);
                        }
                    }
                    if (remaining.size() != existingPrimaryKeys.size())
                    {
                        if (remaining.isEmpty())
                        {
                            delete(batch, iterator.key());
                        }
                        else
                        {
                            put(batch, iterator.key(), PixelsTagIndexCodec.encode(remaining));
                        }
                    }
                }
                iterator.next();
            }
            put(batch, tag, PixelsTagIndexCodec.append(readValue(tag), primaryKeys));
            rocksDB.write(writeOptions, batch);
        }
    }

    private static boolean contains(List<byte[]> primaryKeys, byte[] candidate)
    {
        for (byte[] primaryKey : primaryKeys)
        {
            if (Arrays.equals(primaryKey, candidate))
            {
                return true;
            }
        }
        return false;
    }

    private void put(WriteBatch batch, byte[] key, byte[] value) throws RocksDBException
    {
        if (columnFamilyHandle == null)
        {
            batch.put(key, value);
        }
        else
        {
            batch.put(columnFamilyHandle, key, value);
        }
    }

    private void delete(WriteBatch batch, byte[] key) throws RocksDBException
    {
        if (columnFamilyHandle == null)
        {
            batch.delete(key);
        }
        else
        {
            batch.delete(columnFamilyHandle, key);
        }
    }

    public synchronized List<byte[]> get(byte[] tag) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        byte[] value = readValue(tag);
        return value == null ? List.of() : PixelsTagIndexCodec.decode(value);
    }

    /**
     * Returns a stable snapshot of every tag entry in this column family.
     * The returned byte arrays are defensive copies and can be used after the
     * iterator has been closed.
     */
    public synchronized List<Entry> entries()
    {
        List<Entry> entries = new ArrayList<>();
        try (RocksIterator iterator = columnFamilyHandle == null
                ? rocksDB.newIterator()
                : rocksDB.newIterator(columnFamilyHandle))
        {
            iterator.seekToFirst();
            while (iterator.isValid())
            {
                entries.add(new Entry(iterator.key(), PixelsTagIndexCodec.decode(iterator.value())));
                iterator.next();
            }
        }
        return List.copyOf(entries);
    }

    private byte[] readValue(byte[] tag) throws RocksDBException
    {
        if (columnFamilyHandle == null)
        {
            return rocksDB.get(tag);
        }
        return rocksDB.get(columnFamilyHandle, tag);
    }

    @Override
    public void close() throws IOException
    {
        writeOptions.close();
        if (ownsFactoryReference)
        {
            RocksDBFactory.close();
        }
    }
}
