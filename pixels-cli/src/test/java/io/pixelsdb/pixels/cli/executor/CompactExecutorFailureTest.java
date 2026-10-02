package io.pixelsdb.pixels.cli.executor;

import io.pixelsdb.pixels.common.exception.MetadataException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactExecutorFailureTest
{
    @Test
    void propagatesWorkerFailureBeforeCompactedFilesArePublished()
    {
        IOException workerFailure = new IOException("compaction writer failed");
        CompletableFuture<Void> failedTask = new CompletableFuture<>();
        failedTask.completeExceptionally(workerFailure);

        MetadataException failure = assertThrows(MetadataException.class,
                () -> CompactExecutor.throwIfAnyCompactionFailed(List.<Future<?>>of(failedTask)));

        assertSame(workerFailure, failure.getCause());
    }

    @Test
    void acceptsWhenEveryWorkerCompletedSuccessfully()
    {
        CompletableFuture<Void> successfulTask = CompletableFuture.completedFuture(null);

        assertDoesNotThrow(() ->
                CompactExecutor.throwIfAnyCompactionFailed(List.<Future<?>>of(successfulTask)));
    }
}
