/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Manually selected probe for the compaction row-location mapping stage. */
class PixelsTagCompactorRowLocationMillionRowProbeTest
{
    @Test
    void buildsTheConfiguredNumberOfRowLocations()
    {
        int rows = Integer.getInteger("pixels.compaction.row.location.probe.rows", 100_000);
        List<IndexProto.ResolvePrimaryEntry> resolvedEntries = new ArrayList<>(rows);
        for (long rowId = 0; rowId < rows; rowId++)
        {
            resolvedEntries.add(IndexProto.ResolvePrimaryEntry.newBuilder()
                    .setRowId(rowId)
                    .setRowLocation(IndexProto.RowLocation.newBuilder()
                            .setFileId(11)
                            .setRgId((int) (rowId / 1_000))
                            .setRgRowOffset((int) (rowId % 1_000))
                            .build())
                    .build());
        }

        long start = System.nanoTime();
        Map<Long, PixelsTagCompactionUpdateMap.Location> locations =
                PixelsTagCompactor.buildRowLocationsByRowId(resolvedEntries);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(rows, locations.size());
        System.out.printf("[PIXELS_COMPACTION_ROW_LOCATION_PROBE] rows=%d build_ms=%d%n",
                rows, elapsedMs);
    }
}
