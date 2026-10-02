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

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
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

    @BeforeAll
    static void loadRocksDb()
    {
        RocksDB.loadLibrary();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception
    {
        dbPath = Files.createTempDirectory("pixels-tag-index-");
        db = RocksDB.open(new Options().setCreateIfMissing(true), dbPath.toString());
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
}
