/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Resolves durable tag-index row ids to source-file row locations. */
final class PixelsTagCompactionUpdateMap
{
    private PixelsTagCompactionUpdateMap() {}

    static Map<Location, byte[]> build(
            List<Entry> entries,
            Map<Long, Location> rowLocations)
    {
        requireNonNull(entries, "entries is null");
        requireNonNull(rowLocations, "rowLocations is null");
        Map<Location, byte[]> updates = new LinkedHashMap<>();
        for (Entry entry : entries)
        {
            byte[] tag = entry.tag.clone();
            for (byte[] encodedRowId : entry.rowIds)
            {
                if (encodedRowId.length != Long.BYTES)
                {
                    throw new IllegalArgumentException(
                            "tag index row id must be an 8-byte long, got " + encodedRowId.length + " bytes");
                }
                long rowId = ByteBuffer.wrap(encodedRowId).getLong();
                Location location = rowLocations.get(rowId);
                if (location == null)
                {
                    throw new IllegalArgumentException("tag index row id has no main-index location: " + rowId);
                }
                byte[] previousTag = updates.putIfAbsent(location, tag.clone());
                if (previousTag != null && !Arrays.equals(previousTag, tag))
                {
                    throw new IllegalArgumentException(
                            "multiple tag values resolve to the same physical row: " + location);
                }
            }
        }
        return Collections.unmodifiableMap(updates);
    }

    static final class Entry
    {
        private final byte[] tag;
        private final List<byte[]> rowIds;

        Entry(byte[] tag, List<byte[]> rowIds)
        {
            this.tag = requireNonNull(tag, "tag is null").clone();
            this.rowIds = List.copyOf(requireNonNull(rowIds, "rowIds is null"));
        }
    }

    static final class Location
    {
        private final long fileId;
        private final int rowGroupId;
        private final int rowOffset;

        Location(long fileId, int rowGroupId, int rowOffset)
        {
            this.fileId = fileId;
            this.rowGroupId = rowGroupId;
            this.rowOffset = rowOffset;
        }

        long fileId()
        {
            return fileId;
        }

        int rowGroupId()
        {
            return rowGroupId;
        }

        int rowOffset()
        {
            return rowOffset;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Location))
            {
                return false;
            }
            Location that = (Location) other;
            return fileId == that.fileId && rowGroupId == that.rowGroupId && rowOffset == that.rowOffset;
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(new long[] {fileId, rowGroupId, rowOffset});
        }

        @Override
        public String toString()
        {
            return "Location{" + fileId + ":" + rowGroupId + ":" + rowOffset + "}";
        }
    }
}
