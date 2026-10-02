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
import org.rocksdb.WriteOptions;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Persistent tag index backed by RocksDB.
 *
 * <p>The tag bytes are the RocksDB key. The value is a versioned list of
 * serialized primary keys, allowing one tag to refer to multiple rows. Appends
 * are serialized per index instance and are idempotent for an identical
 * primary-key byte sequence.</p>
 */
public final class PixelsTagIndex implements Closeable
{
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
        this.columnFamilyHandle = RocksDBFactory.getOrCreateColumnFamily(tableId, indexId, vNodeId);
        this.rocksDB = RocksDBFactory.getOpenRocksDB();
        this.writeOptions = new WriteOptions();
        this.ownsFactoryReference = true;
    }

    public synchronized void append(byte[] tag, List<byte[]> primaryKeys) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        requireNonNull(primaryKeys, "primaryKeys is null");
        byte[] merged = PixelsTagIndexCodec.append(readValue(tag), primaryKeys);
        if (columnFamilyHandle == null)
        {
            rocksDB.put(writeOptions, tag, merged);
        }
        else
        {
            rocksDB.put(columnFamilyHandle, writeOptions, tag, merged);
        }
    }

    public synchronized List<byte[]> get(byte[] tag) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        byte[] value = readValue(tag);
        return value == null ? List.of() : PixelsTagIndexCodec.decode(value);
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
