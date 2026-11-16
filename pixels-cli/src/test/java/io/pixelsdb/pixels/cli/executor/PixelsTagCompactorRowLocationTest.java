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

class PixelsTagCompactorRowLocationTest
{
    @Test
    void buildsDirectRowIdToSourceLocationIndex()
    {
        List<IndexProto.ResolvePrimaryEntry> resolvedEntries = new ArrayList<>();
        for (long rowId = 0; rowId < 100_000; rowId++)
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

        Map<Long, PixelsTagCompactionUpdateMap.Location> locations =
                PixelsTagCompactor.buildRowLocationsByRowId(resolvedEntries);

        assertEquals(100_000, locations.size());
        assertEquals(new PixelsTagCompactionUpdateMap.Location(11, 0, 0), locations.get(0L));
        assertEquals(new PixelsTagCompactionUpdateMap.Location(11, 50, 123), locations.get(50_123L));
        assertEquals(new PixelsTagCompactionUpdateMap.Location(11, 99, 999), locations.get(99_999L));
    }
}
