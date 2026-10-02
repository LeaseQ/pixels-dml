package io.pixelsdb.pixels.cli.executor;

import com.google.protobuf.ByteString;
import io.pixelsdb.pixels.common.utils.IndexUtils;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PixelsTagCompactorIndexRoutingTest
{
    @Test
    void looksUpPrimaryKeyInItsIndexBucket() throws Exception
    {
        ByteString primaryKey = ByteString.copyFromUtf8("tagged-row");
        for (int suffix = 0; IndexUtils.getBucketIdFromByteBuffer(primaryKey) == 0; suffix++)
        {
            primaryKey = ByteString.copyFromUtf8("tagged-row-" + suffix);
        }
        ByteString encodedKey = primaryKey;
        int expectedBucket = IndexUtils.getBucketIdFromByteBuffer(encodedKey);
        assertNotEquals(0, expectedBucket);
        AtomicInteger observedBucket = new AtomicInteger(-1);

        long rowId = PixelsTagCompactor.lookupPrimaryRowId(5L, 6L, encodedKey,
                (lookupKey, bucketId) -> {
                    assertEquals(5L, lookupKey.getTableId());
                    assertEquals(6L, lookupKey.getIndexId());
                    assertEquals(encodedKey, lookupKey.getKey());
                    assertEquals(Long.MAX_VALUE, lookupKey.getTimestamp());
                    observedBucket.set(bucketId);
                    return 42L;
                });

        assertEquals(42L, rowId);
        assertEquals(expectedBucket, observedBucket.get());
    }
}
