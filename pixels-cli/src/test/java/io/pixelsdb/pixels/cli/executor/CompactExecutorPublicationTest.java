/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.cli.executor;

import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.common.index.MainIndex;
import io.pixelsdb.pixels.common.metadata.domain.File;
import io.pixelsdb.pixels.common.metadata.domain.Path;
import io.pixelsdb.pixels.index.IndexProto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactExecutorPublicationTest
{
    @Test
    void mainIndexFailureDoesNotLeaveCompactedFilesPublished()
    {
        File file = new File();
        file.setName("compact.pxl");
        file.setType(File.Type.REGULAR);
        file.setPathId(7L);
        file.setNumRowGroup(1);
        Path path = new Path();
        path.setUri("file:///compact");
        CompactExecutor.CompactionResult result = new CompactExecutor.CompactionResult(
                file, path, Map.of(11L, IndexProto.RowLocation.newBuilder()
                        .setRgId(0).setRgRowOffset(0).build()));
        RecordingPublication publication = new RecordingPublication(101L);
        MainIndex mainIndex = new FailingMainIndex();

        assertThrows(MetadataException.class, () -> CompactExecutor.publishCompactionResults(
                List.of(result), mainIndex, publication));

        assertEquals(1, publication.addCalls);
        assertEquals(List.of(101L), publication.deletedFileIds);
    }

    private static final class RecordingPublication implements CompactExecutor.MetadataPublication
    {
        private final long fileId;
        private int addCalls;
        private List<Long> deletedFileIds = List.of();

        private RecordingPublication(long fileId)
        {
            this.fileId = fileId;
        }

        @Override
        public void addFiles(List<File> files)
        {
            addCalls++;
        }

        @Override
        public long getFileId(String path)
        {
            return fileId;
        }

        @Override
        public void deleteFiles(List<Long> fileIds)
        {
            deletedFileIds = List.copyOf(fileIds);
        }
    }

    private static final class FailingMainIndex implements MainIndex
    {
        @Override public long getTableId() { return 1L; }
        @Override public boolean hasCache() { return false; }
        @Override public IndexProto.RowIdBatch allocateRowIdBatch(long tableId, int numRowIds) { throw new UnsupportedOperationException(); }
        @Override public IndexProto.RowLocation getLocation(long rowId) { throw new UnsupportedOperationException(); }
        @Override public List<IndexProto.RowLocation> getLocations(List<Long> rowIds) { throw new UnsupportedOperationException(); }
        @Override public boolean putEntry(long rowId, IndexProto.RowLocation rowLocation) { return false; }
        @Override public List<Boolean> putEntries(List<IndexProto.PrimaryIndexEntry> primaryEntries) { throw new UnsupportedOperationException(); }
        @Override public boolean deleteRowIdRange(io.pixelsdb.pixels.common.index.RowIdRange rowIdRange) { throw new UnsupportedOperationException(); }
        @Override public boolean flushCache(long fileId) { throw new UnsupportedOperationException(); }
        @Override public void close() {}
        @Override public boolean closeAndRemove() { throw new UnsupportedOperationException(); }
    }
}
