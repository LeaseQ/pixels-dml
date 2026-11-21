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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final int VALUE_CACHE_MAX_ENTRIES = 8192;
    private static final long VALUE_CACHE_MAX_BYTES = 128L * 1024 * 1024;
    private static final byte VERSIONED_KEY_MARKER = 1;
    private static final int VERSIONED_KEY_HEADER_SIZE = 1 + Integer.BYTES;
    private static final int TRANSACTION_TIMESTAMP_SIZE = Long.BYTES;
    /**
     * Tag records use a dedicated virtual-node namespace so they cannot be
     * decoded as primary-index records in the primary index column family.
     */
    public static final int TAG_VNODE_ID = -1;

    public static final class VersionedUpdate
    {
        private final byte[] tag;
        private final long transactionTimestamp;
        private final List<byte[]> primaryKeys;
        private final boolean primaryKeysKnownUnique;

        public VersionedUpdate(byte[] tag, long transactionTimestamp, List<byte[]> primaryKeys)
        {
            this(tag, transactionTimestamp, primaryKeys, false);
        }

        /**
         * Creates an update with an optional uniqueness proof for its primary-key list.
         * The proof is only used to skip redundant per-column deduplication when a
         * shared-key request has already validated the vector once.
         */
        public VersionedUpdate(byte[] tag, long transactionTimestamp, List<byte[]> primaryKeys,
                boolean primaryKeysKnownUnique)
        {
            this.tag = requireNonNull(tag, "tag is null").clone();
            this.transactionTimestamp = transactionTimestamp;
            this.primaryKeys = List.copyOf(requireNonNull(primaryKeys, "primaryKeys is null"));
            this.primaryKeysKnownUnique = primaryKeysKnownUnique;
        }

        public byte[] tag() { return tag.clone(); }
        public long transactionTimestamp() { return transactionTimestamp; }
        public List<byte[]> primaryKeys() { return primaryKeys; }
        public boolean primaryKeysKnownUnique() { return primaryKeysKnownUnique; }
    }

    private static final class VersionedKey
    {
        private final byte[] tag;
        private final long timestamp;

        private VersionedKey(byte[] tag, long timestamp)
        {
            this.tag = tag.clone();
            this.timestamp = timestamp;
        }

        private byte[] tag() { return tag; }
        private long timestamp() { return timestamp; }

        @Override
        public int hashCode() { return 31 * Arrays.hashCode(tag) + Long.hashCode(timestamp); }

        @Override
        public boolean equals(Object other)
        {
            if (!(other instanceof VersionedKey)) {
                return false;
            }
            VersionedKey key = (VersionedKey) other;
            return timestamp == key.timestamp && Arrays.equals(tag, key.tag);
        }
    }

    private static final class LatestValue
    {
        private final byte[] tag;
        private final long timestamp;

        private LatestValue(byte[] tag, long timestamp)
        {
            this.tag = tag.clone();
            this.timestamp = timestamp;
        }

        private byte[] tag() { return tag; }
        private long timestamp() { return timestamp; }
    }

    /** A primary key has one latest value per tag column, not one per table. */
    private static final class PrimaryColumnKey
    {
        private final ByteBuffer primaryKey;
        private final String columnName;

        private PrimaryColumnKey(byte[] primaryKey, String columnName)
        {
            this.primaryKey = ByteBuffer.wrap(primaryKey.clone());
            this.columnName = columnName;
        }

        private byte[] primaryKey()
        {
            return primaryKey.array().clone();
        }

        @Override
        public boolean equals(Object other)
        {
            if (!(other instanceof PrimaryColumnKey))
            {
                return false;
            }
            PrimaryColumnKey that = (PrimaryColumnKey) other;
            return primaryKey.equals(that.primaryKey) && columnName.equals(that.columnName);
        }

        @Override
        public int hashCode()
        {
            return 31 * primaryKey.hashCode() + columnName.hashCode();
        }
    }

    private static final class VersionedRecord
    {
        private final byte[] physicalKey;
        private final VersionedKey logicalKey;
        private final List<byte[]> primaryKeys;

        private VersionedRecord(byte[] physicalKey, VersionedKey logicalKey, List<byte[]> primaryKeys)
        {
            this.physicalKey = physicalKey.clone();
            this.logicalKey = logicalKey;
            this.primaryKeys = primaryKeys;
        }
    }

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
    private final long tableId;
    /**
     * Values already read or written by this long-lived index instance.  The
     * IndexServer keeps one instance per table/index, so repeated UPDATE
     * batches do not have to read the same versioned record from RocksDB.
     */
    private final Map<ByteBuffer, List<byte[]>> valueCache =
            new LinkedHashMap<>(128, 0.75f, true);
    private long valueCacheBytes;

    /**
     * Constructor used by unit tests or callers that own the RocksDB handle.
     */
    PixelsTagIndex(RocksDB rocksDB)
    {
        this.tableId = 0L;
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
        this.tableId = tableId;
        this.columnFamilyHandle = RocksDBFactory.getOrCreateColumnFamily(tableId, indexId, TAG_VNODE_ID);
        this.rocksDB = RocksDBFactory.getOpenRocksDB(tableId);
        this.writeOptions = new WriteOptions();
        this.ownsFactoryReference = true;
    }

    public synchronized void append(byte[] tag, List<byte[]> primaryKeys) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        requireNonNull(primaryKeys, "primaryKeys is null");
        appendLegacy(tag, primaryKeys);
    }

    /** Append one transaction version without sharing the legacy tag key. */
    public synchronized void append(byte[] tag, long transactionTimestamp,
            List<byte[]> primaryKeys) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        requireNonNull(primaryKeys, "primaryKeys is null");
        if (transactionTimestamp <= 0) {
            appendLegacy(tag, primaryKeys);
            return;
        }
        appendBatch(List.of(new VersionedUpdate(tag, transactionTimestamp, primaryKeys)));
    }

    /** Merge one or more transaction updates in a single RocksDB write. */
    public synchronized void appendBatch(List<VersionedUpdate> updates) throws RocksDBException
    {
        requireNonNull(updates, "updates is null");
        if (updates.isEmpty()) {
            return;
        }
        if (updates.size() == 1)
        {
            appendSingleVersionedUpdate(updates.get(0));
            return;
        }
        long batchStartNanos = System.nanoTime();
        Set<String> columns = new HashSet<>();
        boolean distinctColumns = true;
        for (VersionedUpdate update : updates) {
            requireNonNull(update, "update is null");
            if (update.transactionTimestamp() <= 0) {
                throw new IllegalArgumentException("batched tag updates require a positive transaction timestamp");
            }
            distinctColumns &= columns.add(TagIndexKeyCodec.columnName(update.tag()));
        }
        long dedupNanos = System.nanoTime() - batchStartNanos;
        long groupStartNanos = System.nanoTime();
        Map<VersionedKey, List<byte[]>> grouped = new LinkedHashMap<>();
        Map<VersionedKey, Boolean> knownUniqueGroups = new LinkedHashMap<>();
        int primaryKeyCount;
        if (distinctColumns)
        {
            // UPDATE normally sends one VersionedUpdate per changed column.
            // When columns are distinct, updates cannot compete for the same
            // (primary key, column) slot, so avoid allocating one
            // PrimaryColumnKey for every row and preserve the request lists
            // directly for the later codec merge.
            primaryKeyCount = 0;
            for (VersionedUpdate update : updates)
            {
                VersionedKey versionedKey = new VersionedKey(update.tag(), update.transactionTimestamp());
                List<byte[]> primaryKeys = grouped.computeIfAbsent(versionedKey, ignored -> new ArrayList<>());
                primaryKeys.addAll(update.primaryKeys());
                knownUniqueGroups.put(versionedKey, update.primaryKeysKnownUnique());
                primaryKeyCount += update.primaryKeys().size();
            }
        }
        else
        {
            Map<PrimaryColumnKey, VersionedKey> latestByPrimaryKey = new LinkedHashMap<>();
            for (VersionedUpdate update : updates) {
                VersionedKey versionedKey = new VersionedKey(update.tag(), update.transactionTimestamp());
                String columnName = TagIndexKeyCodec.columnName(update.tag());
                for (byte[] primaryKey : update.primaryKeys()) {
                    PrimaryColumnKey key = new PrimaryColumnKey(primaryKey, columnName);
                    VersionedKey previous = latestByPrimaryKey.get(key);
                    if (previous == null || versionedKey.timestamp() >= previous.timestamp()) {
                        latestByPrimaryKey.put(key, versionedKey);
                    }
                }
            }
            primaryKeyCount = latestByPrimaryKey.size();
            for (Map.Entry<PrimaryColumnKey, VersionedKey> entry : latestByPrimaryKey.entrySet()) {
                grouped.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                        .add(entry.getKey().primaryKey());
            }
        }
        long groupNanos = System.nanoTime() - groupStartNanos;
        try (WriteBatch batch = new WriteBatch()) {
            long keyReadStartNanos = System.nanoTime();
            List<VersionedKey> versionedKeys = new ArrayList<>(grouped.keySet());
            List<byte[]> indexKeys = new ArrayList<>(versionedKeys.size());
            for (VersionedKey versionedKey : versionedKeys)
            {
                indexKeys.add(createVersionedKey(versionedKey.tag(), versionedKey.timestamp()));
            }
            List<List<byte[]>> existingValues = readPrimaryKeysCached(indexKeys);
            long keyReadNanos = System.nanoTime() - keyReadStartNanos;
            long mergeStartNanos = System.nanoTime();
            Map<ByteBuffer, List<byte[]>> updatedValues = new LinkedHashMap<>();
            boolean changed = false;
            for (int i = 0; i < versionedKeys.size(); i++) {
                VersionedKey versionedKey = versionedKeys.get(i);
                byte[] indexKey = indexKeys.get(i);
                List<byte[]> existing = existingValues.get(i);
                List<byte[]> incoming = grouped.get(versionedKey);
                List<byte[]> merged = existing.isEmpty() &&
                        Boolean.TRUE.equals(knownUniqueGroups.get(versionedKey))
                        ? new ArrayList<>(incoming)
                        : PixelsTagIndexCodec.append(existing, incoming);
                if (merged.size() != existing.size())
                {
                    put(batch, indexKey, PixelsTagIndexCodec.encode(merged));
                    changed = true;
                }
                updatedValues.put(ByteBuffer.wrap(indexKey.clone()), merged);
            }
            long mergeNanos = System.nanoTime() - mergeStartNanos;
            long writeStartNanos = System.nanoTime();
            if (changed)
            {
                rocksDB.write(writeOptions, batch);
            }
            long writeNanos = System.nanoTime() - writeStartNanos;
            long cacheStartNanos = System.nanoTime();
            for (Map.Entry<ByteBuffer, List<byte[]>> entry : updatedValues.entrySet())
            {
                cachePut(entry.getKey().array(), entry.getValue());
            }
            long cacheNanos = System.nanoTime() - cacheStartNanos;
            System.out.println("[PIXELS_TAG_INDEX_STORAGE_METRIC] updates=" + updates.size() +
                    " primaryKeys=" + primaryKeyCount +
                    " groups=" + grouped.size() +
                    " dedup_ms=" + dedupNanos / 1_000_000.0 +
                    " group_ms=" + groupNanos / 1_000_000.0 +
                    " key_read_ms=" + keyReadNanos / 1_000_000.0 +
                    " merge_ms=" + mergeNanos / 1_000_000.0 +
                    " rocks_write_ms=" + writeNanos / 1_000_000.0 +
                    " cache_ms=" + cacheNanos / 1_000_000.0);
        }
    }

    private void appendSingleVersionedUpdate(VersionedUpdate update) throws RocksDBException
    {
        requireNonNull(update, "update is null");
        if (update.transactionTimestamp() <= 0)
        {
            throw new IllegalArgumentException("batched tag updates require a positive transaction timestamp");
        }
        byte[] indexKey = createVersionedKey(update.tag(), update.transactionTimestamp());
        List<byte[]> existing = readPrimaryKeysCached(indexKey);
        List<byte[]> merged = PixelsTagIndexCodec.append(existing, update.primaryKeys());
        if (merged.size() == existing.size())
        {
            return;
        }
        try (WriteBatch batch = new WriteBatch())
        {
            put(batch, indexKey, PixelsTagIndexCodec.encode(merged));
            rocksDB.write(writeOptions, batch);
        }
        cachePut(indexKey, merged);
    }

    /**
     * Removes versions that are older than the transaction low watermark.
     * The caller must provide a watermark for which no transaction at or
     * below it can still be active or arrive later.
     */
    public synchronized long compactVersions(long safeTimestamp) throws RocksDBException
    {
        if (safeTimestamp <= 0) {
            return 0;
        }
        Map<ByteBuffer, VersionedRecord> records = new LinkedHashMap<>();
        Map<PrimaryColumnKey, List<VersionedKey>> versions = new LinkedHashMap<>();
        try (RocksIterator iterator = columnFamilyHandle == null
                ? rocksDB.newIterator() : rocksDB.newIterator(columnFamilyHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                VersionedKey key = decodeVersionedKey(iterator.key());
                List<byte[]> primaryKeys = PixelsTagIndexCodec.decode(iterator.value());
                records.put(ByteBuffer.wrap(iterator.key()),
                        new VersionedRecord(iterator.key(), key, primaryKeys));
                String columnName = TagIndexKeyCodec.columnName(key.tag());
                for (byte[] primaryKey : primaryKeys) {
                    versions.computeIfAbsent(new PrimaryColumnKey(primaryKey, columnName), ignored -> new ArrayList<>())
                            .add(key);
                }
                iterator.next();
            }
        }
        Map<VersionedKey, Set<PrimaryColumnKey>> retained = new LinkedHashMap<>();
        for (Map.Entry<PrimaryColumnKey, List<VersionedKey>> entry : versions.entrySet()) {
            VersionedKey latest = null;
            for (VersionedKey version : entry.getValue()) {
                if (version.timestamp() <= safeTimestamp &&
                        (latest == null || version.timestamp() >= latest.timestamp())) {
                    latest = version;
                }
            }
            if (latest != null) {
                retained.computeIfAbsent(latest, ignored -> new HashSet<>()).add(entry.getKey());
            }
            for (VersionedKey version : entry.getValue()) {
                if (version.timestamp() > safeTimestamp) {
                    retained.computeIfAbsent(version, ignored -> new HashSet<>()).add(entry.getKey());
                }
            }
        }
        long removed = 0;
        try (WriteBatch batch = new WriteBatch()) {
            for (VersionedRecord record : records.values()) {
                Set<PrimaryColumnKey> keep = retained.get(record.logicalKey);
                String columnName = TagIndexKeyCodec.columnName(record.logicalKey.tag());
                List<byte[]> remaining = record.primaryKeys.stream()
                        .filter(primaryKey -> keep != null &&
                                keep.contains(new PrimaryColumnKey(primaryKey, columnName)))
                        .collect(Collectors.toList());
                removed += record.primaryKeys.size() - remaining.size();
                if (remaining.isEmpty()) {
                    delete(batch, record.physicalKey);
                }
                else if (remaining.size() != record.primaryKeys.size()) {
                    put(batch, record.physicalKey,
                            PixelsTagIndexCodec.encode(remaining));
                }
            }
            if (removed > 0) {
                rocksDB.write(writeOptions, batch);
                clearCache();
            }
        }
        return removed;
    }

    private void appendLegacy(byte[] tag, List<byte[]> primaryKeys) throws RocksDBException
    {
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
            byte[] value = PixelsTagIndexCodec.append(readValue(tag), primaryKeys);
            put(batch, tag, value);
            rocksDB.write(writeOptions, batch);
            clearCache();
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

    public synchronized List<byte[]> get(String columnName, byte[] tag) throws RocksDBException
    {
        requireNonNull(columnName, "columnName is null");
        return get(TagIndexKeyCodec.encode(columnName, tag));
    }

    /** Read the latest tag value for each primary key under one logical prefix. */
    public synchronized List<byte[]> getByTagPrefix(byte[] tag) throws RocksDBException
    {
        requireNonNull(tag, "tag is null");
        List<byte[]> result = new ArrayList<>();
        for (Entry entry : entries()) {
            if (startsWith(entry.tag(), tag)) {
                result.addAll(entry.primaryKeys());
            }
        }
        return result;
    }

    public synchronized List<byte[]> getByTagPrefix(String columnName, byte[] tag) throws RocksDBException
    {
        requireNonNull(columnName, "columnName is null");
        return getByTagPrefix(TagIndexKeyCodec.prefix(columnName, tag));
    }

    /**
     * Returns a stable snapshot of every tag entry in this column family.
     * The returned byte arrays are defensive copies and can be used after the
     * iterator has been closed.
     */
    public synchronized List<Entry> entries()
    {
        LinkedHashMap<PrimaryColumnKey, LatestValue> latestByPrimaryKey = new LinkedHashMap<>();
        try (RocksIterator iterator = columnFamilyHandle == null
                ? rocksDB.newIterator()
                : rocksDB.newIterator(columnFamilyHandle))
        {
            iterator.seekToFirst();
            while (iterator.isValid())
            {
                VersionedKey versionedKey = decodeVersionedKey(iterator.key());
                for (byte[] primaryKey : PixelsTagIndexCodec.decode(iterator.value())) {
                    PrimaryColumnKey key = new PrimaryColumnKey(primaryKey,
                            TagIndexKeyCodec.columnName(versionedKey.tag()));
                    LatestValue previous = latestByPrimaryKey.get(key);
                    if (previous == null || versionedKey.timestamp() >= previous.timestamp()) {
                        latestByPrimaryKey.put(key,
                                new LatestValue(versionedKey.tag(), versionedKey.timestamp()));
                    }
                }
                iterator.next();
            }
        }
        LinkedHashMap<ByteBuffer, TagEntryBuilder> grouped = new LinkedHashMap<>();
        for (Map.Entry<PrimaryColumnKey, LatestValue> entry : latestByPrimaryKey.entrySet()) {
            LatestValue value = entry.getValue();
            grouped.computeIfAbsent(ByteBuffer.wrap(value.tag()),
                            ignored -> new TagEntryBuilder(value.tag()))
                    .add(entry.getKey().primaryKey());
        }
        return grouped.values().stream().map(TagEntryBuilder::build).collect(Collectors.toList());
    }

    private static final class TagEntryBuilder
    {
        private final byte[] tag;
        private final LinkedHashMap<ByteBuffer, byte[]> primaryKeys = new LinkedHashMap<>();

        private TagEntryBuilder(byte[] tag)
        {
            this.tag = tag;
        }

        private void add(byte[] primaryKey)
        {
            primaryKeys.putIfAbsent(ByteBuffer.wrap(primaryKey), primaryKey.clone());
        }

        private Entry build()
        {
            return new Entry(tag, new ArrayList<>(primaryKeys.values()));
        }
    }

    private byte[] readValue(byte[] tag) throws RocksDBException
    {
        if (columnFamilyHandle == null)
        {
            return rocksDB.get(tag);
        }
        return rocksDB.get(columnFamilyHandle, tag);
    }

    private List<byte[]> readPrimaryKeysCached(byte[] key) throws RocksDBException
    {
        ByteBuffer cacheKey = ByteBuffer.wrap(key);
        if (valueCache.containsKey(cacheKey))
        {
            return valueCache.get(cacheKey);
        }
        byte[] value = readValue(key);
        List<byte[]> primaryKeys = value == null ? List.of() : PixelsTagIndexCodec.decode(value);
        cachePut(key, primaryKeys);
        return primaryKeys;
    }

    private List<List<byte[]>> readPrimaryKeysCached(List<byte[]> keys) throws RocksDBException
    {
        List<List<byte[]>> result = new ArrayList<>(Collections.nCopies(keys.size(), null));
        List<Integer> missingIndexes = new ArrayList<>();
        List<byte[]> missingKeys = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++)
        {
            byte[] key = keys.get(i);
            ByteBuffer cacheKey = ByteBuffer.wrap(key);
            if (valueCache.containsKey(cacheKey))
            {
                result.set(i, valueCache.get(cacheKey));
            }
            else
            {
                missingIndexes.add(i);
                missingKeys.add(key);
            }
        }
        if (missingKeys.isEmpty())
        {
            return result;
        }

        List<byte[]> values = columnFamilyHandle == null
                ? rocksDB.multiGetAsList(missingKeys)
                : rocksDB.multiGetAsList(
                        Collections.nCopies(missingKeys.size(), columnFamilyHandle), missingKeys);
        for (int i = 0; i < missingIndexes.size(); i++)
        {
            byte[] value = values.get(i);
            List<byte[]> primaryKeys = value == null ? List.of() : PixelsTagIndexCodec.decode(value);
            int resultIndex = missingIndexes.get(i);
            result.set(resultIndex, primaryKeys);
            cachePut(keys.get(resultIndex), primaryKeys);
        }
        return result;
    }

    private void cachePut(byte[] key, List<byte[]> value)
    {
        ByteBuffer cacheKey = ByteBuffer.wrap(key.clone());
        List<byte[]> previous = valueCache.remove(cacheKey);
        if (previous != null)
        {
            valueCacheBytes -= estimateValueBytes(previous);
        }

        long weight = estimateValueBytes(value);
        if (weight > VALUE_CACHE_MAX_BYTES)
        {
            return;
        }
        valueCache.put(cacheKey, value);
        valueCacheBytes += weight;
        while (valueCacheBytes > VALUE_CACHE_MAX_BYTES || valueCache.size() > VALUE_CACHE_MAX_ENTRIES)
        {
            java.util.Iterator<Map.Entry<ByteBuffer, List<byte[]>>> iterator =
                    valueCache.entrySet().iterator();
            if (!iterator.hasNext())
            {
                break;
            }
            Map.Entry<ByteBuffer, List<byte[]>> eldest = iterator.next();
            valueCacheBytes -= estimateValueBytes(eldest.getValue());
            iterator.remove();
        }
    }

    private static long estimateValueBytes(List<byte[]> value)
    {
        long bytes = 64L;
        for (byte[] primaryKey : value)
        {
            bytes += 16L + primaryKey.length;
            if (bytes > VALUE_CACHE_MAX_BYTES)
            {
                return VALUE_CACHE_MAX_BYTES + 1;
            }
        }
        return bytes;
    }

    private void clearCache()
    {
        valueCache.clear();
        valueCacheBytes = 0L;
    }

    private static byte[] createVersionedKeyPrefix(byte[] tag)
    {
        ByteBuffer buffer = ByteBuffer.allocate(VERSIONED_KEY_HEADER_SIZE + tag.length);
        buffer.put(VERSIONED_KEY_MARKER).putInt(tag.length).put(tag);
        return buffer.array();
    }

    private static byte[] createVersionedKey(byte[] tag, long transactionTimestamp)
    {
        ByteBuffer buffer = ByteBuffer.allocate(
                VERSIONED_KEY_HEADER_SIZE + tag.length + TRANSACTION_TIMESTAMP_SIZE);
        buffer.put(createVersionedKeyPrefix(tag)).putLong(transactionTimestamp);
        return buffer.array();
    }

    private static VersionedKey decodeVersionedKey(byte[] key)
    {
        if (key.length < VERSIONED_KEY_HEADER_SIZE + TRANSACTION_TIMESTAMP_SIZE ||
                key[0] != VERSIONED_KEY_MARKER) {
            return new VersionedKey(key, 0L);
        }
        int tagLength = ByteBuffer.wrap(key, 1, Integer.BYTES).getInt();
        if (tagLength < 0 || key.length != VERSIONED_KEY_HEADER_SIZE + tagLength +
                TRANSACTION_TIMESTAMP_SIZE) {
            return new VersionedKey(key, 0L);
        }
        byte[] tag = Arrays.copyOfRange(key, VERSIONED_KEY_HEADER_SIZE,
                VERSIONED_KEY_HEADER_SIZE + tagLength);
        long timestamp = ByteBuffer.wrap(key, VERSIONED_KEY_HEADER_SIZE + tagLength,
                TRANSACTION_TIMESTAMP_SIZE).getLong();
        return new VersionedKey(tag, timestamp);
    }

    private static byte[] decodeLogicalTag(byte[] key)
    {
        if (key.length < VERSIONED_KEY_HEADER_SIZE + TRANSACTION_TIMESTAMP_SIZE ||
                key[0] != VERSIONED_KEY_MARKER) {
            return key;
        }
        int tagLength = ByteBuffer.wrap(key, 1, Integer.BYTES).getInt();
        if (tagLength < 0 || key.length != VERSIONED_KEY_HEADER_SIZE + tagLength +
                TRANSACTION_TIMESTAMP_SIZE) {
            return key;
        }
        return Arrays.copyOfRange(key, VERSIONED_KEY_HEADER_SIZE,
                VERSIONED_KEY_HEADER_SIZE + tagLength);
    }

    private static boolean startsWith(byte[] key, byte[] prefix)
    {
        if (key.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException
    {
        clearCache();
        writeOptions.close();
        if (ownsFactoryReference)
        {
            RocksDBFactory.close(tableId);
        }
    }
}
