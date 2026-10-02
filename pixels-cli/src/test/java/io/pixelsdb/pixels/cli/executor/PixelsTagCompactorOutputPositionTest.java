/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PixelsTagCompactorOutputPositionTest
{
    @Test
    void advancesAcrossOutputRowGroups()
    {
        PixelsTagCompactor.OutputRowPosition position = new PixelsTagCompactor.OutputRowPosition();

        assertEquals(location(7, 0, 0), position.location(7, 0));
        position.advance(3, false);
        assertEquals(location(7, 0, 3), position.location(7, 0));
        position.advance(2, true);
        assertEquals(location(7, 1, 0), position.location(7, 0));
    }

    private static IndexProto.RowLocation location(long fileId, int rowGroup, int rowOffset)
    {
        return IndexProto.RowLocation.newBuilder()
                .setFileId(fileId)
                .setRgId(rowGroup)
                .setRgRowOffset(rowOffset)
                .build();
    }
}
