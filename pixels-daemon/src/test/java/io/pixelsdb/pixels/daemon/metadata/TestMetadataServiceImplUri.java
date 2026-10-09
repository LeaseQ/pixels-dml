/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.daemon.metadata;

import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.daemon.MetadataProto;
import io.pixelsdb.pixels.daemon.metadata.dao.PathDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestMetadataServiceImplUri
{
    @Test
    void parseFileUriPreservesFileSchemeAndNormalizesPath()
            throws MetadataException
    {
        MetadataServiceImpl.FilePathParts normalParts = MetadataServiceImpl.parseFilePathUri(
                "file:///tmp/pixels/ordered/data.pxl");
        assertEquals("file:///tmp/pixels/ordered", normalParts.getDirectoryUri());
        assertEquals("data.pxl", normalParts.getFileName());

        MetadataServiceImpl.FilePathParts parts = MetadataServiceImpl.parseFilePathUri(
                "FILE:///tmp/pixels/ordered/../ordered/data.pxl");

        assertEquals("file:///tmp/pixels/ordered", parts.getDirectoryUri());
        assertEquals("data.pxl", parts.getFileName());
    }

    @Test
    void resolvePathToleratesLegacyTrailingSlash()
            throws MetadataException
    {
        RecordingPathDao pathDao = new RecordingPathDao();
        MetadataProto.Path path = MetadataProto.Path.newBuilder().setId(7).build();
        pathDao.paths.put("file:///tmp/pixels/ordered/", path);

        MetadataProto.Path resolved = MetadataServiceImpl.resolvePath(
                pathDao, "file:///tmp/pixels/ordered/");

        assertEquals(7, resolved.getId());
        assertEquals(Arrays.asList("file:///tmp/pixels/ordered", "file:///tmp/pixels/ordered/"),
                pathDao.lookups);
    }

    @Test
    void missingPathProducesMetadataErrorInsteadOfNullPointerException()
    {
        MetadataException exception = assertThrows(MetadataException.class,
                () -> MetadataServiceImpl.resolvePath(
                        new RecordingPathDao(), "file:///tmp/pixels/missing/"));

        assertEquals("path metadata not found for directory URI 'file:///tmp/pixels/missing'",
                exception.getMessage());
    }

    private static final class RecordingPathDao extends PathDao
    {
        private final Map<String, MetadataProto.Path> paths = new HashMap<>();
        private final List<String> lookups = new ArrayList<>();

        @Override
        public MetadataProto.Path getById(long id)
        {
            return null;
        }

        @Override
        public MetadataProto.Path getByPathUri(String pathUri)
        {
            lookups.add(pathUri);
            return paths.get(pathUri);
        }

        @Override
        public List<MetadataProto.Path> getAllByLayoutId(long layoutId)
        {
            return Collections.emptyList();
        }

        @Override
        public List<MetadataProto.Path> getAllByRangeId(long rangeId)
        {
            return Collections.emptyList();
        }

        @Override
        public boolean exists(MetadataProto.Path path)
        {
            return false;
        }

        @Override
        public long insert(MetadataProto.Path path)
        {
            return 0;
        }

        @Override
        public boolean update(MetadataProto.Path path)
        {
            return false;
        }

        @Override
        public boolean deleteByIds(List<Long> ids)
        {
            return false;
        }
    }
}
