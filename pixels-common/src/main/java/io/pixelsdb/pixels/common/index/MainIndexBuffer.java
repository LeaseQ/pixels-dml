/*
 * Copyright 2025 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.common.index;

import com.google.common.collect.ImmutableList;
import io.pixelsdb.pixels.common.exception.MainIndexException;
import io.pixelsdb.pixels.index.IndexProto;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * This is the index buffer for a main index to accelerate main index writes.
 * It is to be used inside the main index implementations and protected by the concurrency control in the main index,
 * thus it is not necessary to be thread-safe.
 * @author hank
 * @create 2025-07-17
 */
public class MainIndexBuffer implements Closeable
{
    /**
     * Issue #1150:
     * If the number of files in this buffer is over this threshold, synchronous cache population is enabled.
     * 6-8 are tested to be good settings. This threshold avoids redundant cache population when the number of files
     * is small (i.e., the buffer can be used as a cache and provide good lookup performance).
     */
    private static final int CACHE_POP_ENABLE_THRESHOLD = 6;
    /**
     * fileId -> {tableRowId -> rowLocation}.
     */
    private final Map<Long, Map<Long, IndexProto.RowLocation>> indexBuffer;
    /**
     * Reverse lookup for buffered row locations.  INSERT, UPDATE, and COMPACT
     * all share the relocation-safe insert path; scanning every file buffer to
     * find an older row location makes that path grow with the number of open
     * files.  Keep the existing per-file maps for flush/layout behavior, but
     * use this map to locate a prior row in constant time.
     */
    private final Map<Long, Long> rowIdToFileId;
    private final MainIndexCache indexCache;
    private boolean populateCache = false;

    public static final class FlushSnapshot
    {
        private final long fileId;
        private final int entryCount;
        private final List<RowIdRange> rowIdRanges;

        private FlushSnapshot(long fileId, int entryCount, List<RowIdRange> rowIdRanges)
        {
            this.fileId = fileId;
            this.entryCount = entryCount;
            this.rowIdRanges = Collections.unmodifiableList(new ArrayList<>(rowIdRanges));
        }

        public long getFileId()
        {
            return fileId;
        }

        public int getEntryCount()
        {
            return entryCount;
        }

        public List<RowIdRange> getRowIdRanges()
        {
            return rowIdRanges;
        }

        public boolean isEmpty()
        {
            return entryCount == 0;
        }
    }

    /**
     * Create a main index buffer and bind the main index cache to it.
     * Entries put into this buffer will also be put into the cache.
     * @param indexCache the main index cache to bind
     */
    public MainIndexBuffer(MainIndexCache indexCache)
    {
        this.indexCache = requireNonNull(indexCache, "indexCache is null");
        this.indexBuffer = new HashMap<>();
        this.rowIdToFileId = new HashMap<>();
    }

    /**
     * Insert or replace a main-index entry in this buffer.
     *
     * <p>A compaction relocates an existing row id to a new file.  Keeping the
     * old row id in a different per-file buffer makes lookup order decide which
     * location wins (and can return a retired file).  Remove the previous
     * buffered location before installing the new one so relocation is
     * authoritative even when the cache has not been populated.</p>
     *
     * @param rowId the table row id of the entry
     * @param location the row location of the entry
     * @return true if the new location is buffered successfully
     */
    public boolean insert(long rowId, IndexProto.RowLocation location)
    {
        Map<Long, IndexProto.RowLocation> fileBuffer = this.indexBuffer.get(location.getFileId());
        if (fileBuffer == null)
        {
            if (this.indexBuffer.size() > CACHE_POP_ENABLE_THRESHOLD)
            {
                this.populateCache = true;
            }
            // Issue #1115: use HashMap for better performance and do post-sorting in flush().
            fileBuffer = new HashMap<>();
            this.indexBuffer.put(location.getFileId(), fileBuffer);
        }

        Long previousFileId = this.rowIdToFileId.get(rowId);
        if (previousFileId != null && previousFileId != location.getFileId())
        {
            Map<Long, IndexProto.RowLocation> previousFileBuffer = this.indexBuffer.get(previousFileId);
            if (previousFileBuffer != null && previousFileBuffer.remove(rowId) != null &&
                    previousFileBuffer.isEmpty())
            {
                this.indexBuffer.remove(previousFileId);
            }
        }

        fileBuffer.put(rowId, location);
        this.rowIdToFileId.put(rowId, location.getFileId());
        // A lookup may have admitted an older persistent range into the cache.
        // A point entry must win over that range, while avoiding population of
        // every relocated row when cache population is disabled.
        this.indexCache.evict(rowId);
        this.indexCache.evictRangeContaining(rowId);
        if (this.populateCache)
        {
            this.indexCache.admit(rowId, location);
        }
        return true;
    }

    /**
     * Insert a row that is guaranteed to have been freshly allocated for this
     * table.  INSERT obtains row ids from {@code RowIdAllocator}, so there is
     * no existing buffered location to relocate and no persistent range cache
     * entry to invalidate.  Keeping this contract separate is important:
     * UPDATE and COMPACT must continue to use {@link #insert(long,
     * IndexProto.RowLocation)}, which removes an older location from another
     * file buffer.
     *
     * @param rowId a newly allocated table row id
     * @param location the new row location
     * @return true if the row was added; false if this file already contains it
     */
    public boolean insertNew(long rowId, IndexProto.RowLocation location)
    {
        Map<Long, IndexProto.RowLocation> fileBuffer = this.indexBuffer.get(location.getFileId());
        if (fileBuffer == null)
        {
            if (this.indexBuffer.size() > CACHE_POP_ENABLE_THRESHOLD)
            {
                this.populateCache = true;
            }
            fileBuffer = new HashMap<>();
            this.indexBuffer.put(location.getFileId(), fileBuffer);
        }
        if (fileBuffer.putIfAbsent(rowId, location) != null)
        {
            return false;
        }
        this.rowIdToFileId.put(rowId, location.getFileId());
        // Newly allocated row ids cannot overlap an older persistent range.
        // Deliberately avoid per-row cache eviction/admission on this hot path;
        // lookup still finds the entry in the active file buffer.
        return true;
    }

    protected IndexProto.RowLocation lookup(long fileId, long rowId) throws MainIndexException
    {
        Map<Long, IndexProto.RowLocation> fileBuffer = this.indexBuffer.get(fileId);
        if (fileBuffer == null)
        {
            return null;
        }
        IndexProto.RowLocation location = fileBuffer.get(rowId);
        if (location == null)
        {
            location = this.indexCache.lookup(rowId);
        }
        return location;
    }

    /**
     * @param rowId the row id of the table
     * @return the buffered or cached row location, or null if not found
     */
    public IndexProto.RowLocation lookup(long rowId) throws MainIndexException
    {
        IndexProto.RowLocation location = this.indexCache.lookup(rowId);
        if (location == null)
        {
            for (Map.Entry<Long, Map<Long, IndexProto.RowLocation>> entry : this.indexBuffer.entrySet())
            {
                long fileId = entry.getKey();
                location = entry.getValue().get(rowId);
                if (location != null)
                {
                    checkArgument(fileId == location.getFileId());
                    break;
                }
            }
        }
        return location;
    }

    /**
     * Build a stable snapshot of the (row id -> row location) mappings of the given file id.
     * This method must not mutate the buffer or cache; callers should only discard the buffered
     * entries after the snapshot has been durably committed.
     * @param fileId the given file id to flush
     * @return the row id range snapshot to be persisted into the storage
     * @throws MainIndexException
     */
    public FlushSnapshot snapshotForFlush(long fileId) throws MainIndexException
    {
        Map<Long, IndexProto.RowLocation> fileBuffer = this.indexBuffer.get(fileId);
        if (fileBuffer == null)
        {
            return new FlushSnapshot(fileId, 0, Collections.emptyList());
        }
        ImmutableList.Builder<RowIdRange> ranges = ImmutableList.builder();
        RowIdRange.Builder currRangeBuilder = new RowIdRange.Builder();
        boolean first = true, last = false;
        long prevRowId = Long.MIN_VALUE;
        int prevRgId = Integer.MIN_VALUE;
        int prevRgRowOffset = Integer.MIN_VALUE;
        /*
         * Issue #1115: do post-sorting, build a row id array and sorted it in ascending order.
         * This consumes less memory and is much faster than building a tree map from fileBuffer.
         */
        Long[] rowIds = new Long[fileBuffer.size()];
        rowIds = fileBuffer.keySet().toArray(rowIds);
        List<Long> sortedRowIds = Arrays.asList(rowIds);
        Collections.sort(sortedRowIds);
        for (long rowId : sortedRowIds)
        {
            IndexProto.RowLocation location = fileBuffer.get(rowId);
            checkArgument(fileId == location.getFileId());
            int rgId = location.getRgId();
            int rgRowOffset = location.getRgRowOffset();
            if (rowId != prevRowId + 1 || rgId != prevRgId || rgRowOffset != prevRgRowOffset + 1)
            {
                // occurs a new row group or a new range in the row group
                if (!first)
                {
                    // finish constructing the current row id range and add it to the ranges
                    currRangeBuilder.setRowIdEnd(prevRowId + 1);
                    currRangeBuilder.setRgRowOffsetEnd(prevRgRowOffset + 1);
                    ranges.add(currRangeBuilder.build());
                }
                // start constructing a new row id range
                first = false;
                last = true;
                currRangeBuilder.setRowIdStart(rowId);
                currRangeBuilder.setFileId(fileId);
                currRangeBuilder.setRgId(rgId);
                currRangeBuilder.setRgRowOffsetStart(rgRowOffset);
                prevRgId = rgId;
            }
            prevRowId = rowId;
            prevRgRowOffset = rgRowOffset;
        }
        // add the last range
        if (last)
        {
            currRangeBuilder.setRowIdEnd(prevRowId + 1);
            currRangeBuilder.setRgRowOffsetEnd(prevRgRowOffset + 1);
            ranges.add(currRangeBuilder.build());
        }
        // release the flushed file index buffer
        if(fileBuffer.size() != rowIds.length)
        {
            throw new MainIndexException("FileBuffer changed while building flush snapshot");
        }
        return new FlushSnapshot(fileId, rowIds.length, ranges.build());
    }

    /**
     * Discard a flush snapshot after the backing store has durably committed it.
     * @param snapshot the committed snapshot
     * @throws MainIndexException if the buffer no longer matches the committed snapshot
     */
    public void discardFlushed(FlushSnapshot snapshot) throws MainIndexException
    {
        if (snapshot.isEmpty())
        {
            return;
        }
        Map<Long, IndexProto.RowLocation> fileBuffer = this.indexBuffer.get(snapshot.getFileId());
        if (fileBuffer == null || fileBuffer.size() != snapshot.getEntryCount())
        {
            throw new MainIndexException("FileBuffer changed before committed flush discard");
        }
        for (Long rowId : fileBuffer.keySet())
        {
            this.rowIdToFileId.remove(rowId);
        }
        fileBuffer.clear();
        this.indexBuffer.remove(snapshot.getFileId());
        if (this.indexBuffer.size() <= CACHE_POP_ENABLE_THRESHOLD)
        {
            this.populateCache = false;
            this.indexCache.evictAllEntries();
        }
    }

    public List<Long> cachedFileIds()
    {
        return new ArrayList<>(this.indexBuffer.keySet());
    }

    @Override
    public void close() throws IOException
    {
        this.indexBuffer.clear();
        this.rowIdToFileId.clear();
        this.indexCache.close();
    }
}
