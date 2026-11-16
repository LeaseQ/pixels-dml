/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.index.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PixelsTagIndexMillionRowProbe
{
    private static final int ROWS = 1_000_000;

    @Test
    void appendsAndReadsOneMillionPrimaryKeys(@TempDir Path dbPath) throws Exception
    {
        RocksDB.loadLibrary();
        byte[] tag = new byte[] {7, 7, 7};
        List<byte[]> primaryKeys = new ArrayList<>(ROWS);
        for (long value = 0; value < ROWS; value++)
        {
            primaryKeys.add(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        }

        long beforeUsed = usedMemory();
        long start = System.nanoTime();
        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB db = RocksDB.open(options, dbPath.toString());
             PixelsTagIndex index = new PixelsTagIndex(db))
        {
            index.appendBatch(List.of(new PixelsTagIndex.VersionedUpdate(tag, 1L, primaryKeys)));
            long appendMs = (System.nanoTime() - start) / 1_000_000;

            long readStart = System.nanoTime();
            assertEquals(ROWS, index.getByTagPrefix(tag).size());
            long readMs = (System.nanoTime() - readStart) / 1_000_000;
            long afterUsed = usedMemory();
            System.out.printf("[PIXELS_TAG_INDEX_PROBE] rows=%d append_ms=%d read_ms=%d heap_delta_mb=%.1f%n",
                    ROWS, appendMs, readMs, (afterUsed - beforeUsed) / (1024.0 * 1024.0));
        }
    }

    private static long usedMemory()
    {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
