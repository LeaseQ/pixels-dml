/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.Statistics;
import org.rocksdb.TickerType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PixelsTagIndexTest
{
    private Path dbPath;
    private RocksDB db;
    private PixelsTagIndex index;
    private Options options;
    private Statistics statistics;

    @BeforeAll
    static void loadRocksDb()
    {
        RocksDB.loadLibrary();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception
    {
        dbPath = Files.createTempDirectory("pixels-tag-index-");
        statistics = new Statistics();
        options = new Options().setCreateIfMissing(true).setStatistics(statistics);
        db = RocksDB.open(options, dbPath.toString());
        index = new PixelsTagIndex(db);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        if (index != null)
        {
            index.close();
        }
        if (db != null)
        {
            db.close();
        }
        if (options != null)
        {
            options.close();
        }
        if (statistics != null)
        {
            statistics.close();
        }
        try (Stream<Path> paths = Files.walk(dbPath))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(path ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (java.io.IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Test
    void appendsPrimaryKeysForTheSameTagAndReadsThemBack() throws Exception
    {
        byte[] tag = "vip".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        index.append(tag, List.of(new byte[] {1}, new byte[] {2}));
        index.append(tag, List.of(new byte[] {2}, new byte[] {3}));

        List<byte[]> primaryKeys = index.get(tag);
        assertEquals(3, primaryKeys.size());
        assertArrayEquals(new byte[] {1}, primaryKeys.get(0));
        assertArrayEquals(new byte[] {2}, primaryKeys.get(1));
        assertArrayEquals(new byte[] {3}, primaryKeys.get(2));
    }

    @Test
    void latestTagReplacesThePreviousTagForTheSamePrimaryKey() throws Exception
    {
        byte[] primaryKey = new byte[] {1};
        byte[] oldTag = "vip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] latestTag = "gold".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        index.append(oldTag, List.of(primaryKey));
        index.append(latestTag, List.of(primaryKey));

        assertEquals(0, index.get(oldTag).size());
        assertEquals(1, index.get(latestTag).size());
        assertArrayEquals(primaryKey, index.get(latestTag).get(0));
    }

    @Test
    void listsAllTagsAndTheirPrimaryKeys() throws Exception
    {
        index.append("vip".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                List.of(new byte[] {1}, new byte[] {2}));
        index.append("gold".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                List.of(new byte[] {3}));

        List<PixelsTagIndex.Entry> entries = index.entries();

        assertEquals(2, entries.size());
        assertArrayEquals("gold".getBytes(java.nio.charset.StandardCharsets.UTF_8), entries.get(0).tag());
        assertArrayEquals(new byte[] {3}, entries.get(0).primaryKeys().get(0));
        assertArrayEquals("vip".getBytes(java.nio.charset.StandardCharsets.UTF_8), entries.get(1).tag());
        assertEquals(2, entries.get(1).primaryKeys().size());
    }

    @Test
    void reusesTheLatestTagStateAcrossBatchesWithoutReadingTheSameRecordAgain() throws Exception
    {
        byte[] tag = "gold".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] primaryKey = new byte[] {1};
        PixelsTagIndex.VersionedUpdate update = new PixelsTagIndex.VersionedUpdate(
                tag, 10L, List.of(primaryKey));

        index.appendBatch(List.of(update));
        long readsAfterFirstBatch = statistics.getTickerCount(TickerType.NUMBER_KEYS_READ);

        index.appendBatch(List.of(update));
        long readsAfterSecondBatch = statistics.getTickerCount(TickerType.NUMBER_KEYS_READ);

        assertEquals(readsAfterFirstBatch, readsAfterSecondBatch,
                "the second batch should reuse the in-memory row state");
        assertEquals(1, index.getByTagPrefix(tag).size());
    }

    @Test
    void appendsOneVersionedUpdateWithoutChangingLatestTagSemantics() throws Exception
    {
        byte[] tag = "gold".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<byte[]> primaryKeys = List.of(longKey(1L), longKey(2L), longKey(2L));

        index.appendBatch(List.of(new PixelsTagIndex.VersionedUpdate(tag, 10L, primaryKeys)));

        assertEquals(2, index.getByTagPrefix(tag).size());
    }

    @Test
    void readsDistinctVersionedTagsWithOneRocksDbMultiGet() throws Exception
    {
        List<PixelsTagIndex.VersionedUpdate> updates = new ArrayList<>();
        for (long value = 0; value < 32; value++)
        {
            updates.add(new PixelsTagIndex.VersionedUpdate(
                    longKey(value + 1000), 10L, List.of(longKey(value))));
        }

        long multiGetsBefore = statistics.getTickerCount(TickerType.NUMBER_MULTIGET_CALLS);
        index.appendBatch(updates);

        assertEquals(multiGetsBefore + 1,
                statistics.getTickerCount(TickerType.NUMBER_MULTIGET_CALLS));
    }

    private static byte[] longKey(long value)
    {
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
