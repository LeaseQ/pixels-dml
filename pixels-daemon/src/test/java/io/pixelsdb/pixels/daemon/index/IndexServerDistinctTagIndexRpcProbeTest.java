/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.daemon.index;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.pixelsdb.pixels.common.error.ErrorCode;
import io.pixelsdb.pixels.common.utils.ConfigFactory;
import io.pixelsdb.pixels.index.IndexProto;
import io.pixelsdb.pixels.index.IndexServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Manually selected probe for the real gRPC path used by Merge fallback when
 * every updated row has a different tag value.
 */
class IndexServerDistinctTagIndexRpcProbeTest
{
    private static final int PORT = 29996;
    @Test
    void appendsTheConfiguredNumberOfDistinctTagsThroughGrpc(@TempDir Path tempDir) throws Exception
    {
        configureIsolatedRocksDb(tempDir);
        int rows = Integer.getInteger("pixels.tag.index.distinct.probe.rows", 1_000_000);
        int tagBatchSize = Integer.getInteger("pixels.tag.index.distinct.probe.batch", 65_536);
        long tableId = 930_000L + System.nanoTime() % 100_000L;
        long indexId = 940_000L + System.nanoTime() % 100_000L;

        IndexServiceImpl service = new IndexServiceImpl();
        Server server = ServerBuilder.forPort(PORT)
                .maxInboundMessageSize(64 * 1024 * 1024)
                .addService(service)
                .build()
                .start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", PORT)
                .maxInboundMessageSize(64 * 1024 * 1024)
                .usePlaintext()
                .build();
        try
        {
            IndexServiceGrpc.IndexServiceBlockingStub stub = IndexServiceGrpc.newBlockingStub(channel);
            long start = System.nanoTime();
            int rpcCount = 0;
            long requestBytes = 0L;
            for (int offset = 0; offset < rows; offset += tagBatchSize)
            {
                int size = Math.min(tagBatchSize, rows - offset);
                List<IndexProto.TagIndexUpdate> updates = new ArrayList<>(size);
                for (int row = offset; row < offset + size; row++)
                {
                    updates.add(IndexProto.TagIndexUpdate.newBuilder()
                            .setTag(com.google.protobuf.ByteString.copyFrom(encodeLong(row + 17L)))
                            .addPrimaryKeys(com.google.protobuf.ByteString.copyFrom(encodeLong(row)))
                            .setTransactionTimestamp(1L)
                            .build());
                }
                IndexProto.AppendTagIndexEntriesRequest request =
                        IndexProto.AppendTagIndexEntriesRequest.newBuilder()
                                .setTableId(tableId)
                                .setIndexId(indexId)
                                .addAllUpdates(updates)
                                .build();
                requestBytes += request.getSerializedSize();
                assertEquals(ErrorCode.SUCCESS,
                        stub.appendTagIndexEntries(request).getErrorCode());
                rpcCount++;
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            System.out.printf("[PIXELS_DISTINCT_TAG_INDEX_RPC_PROBE] rows=%d rpc_count=%d " +
                            "request_bytes=%d elapsed_ms=%d%n",
                    rows, rpcCount, requestBytes, elapsedMs);
        }
        finally
        {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
            service.close();
        }
    }

    private static void configureIsolatedRocksDb(Path tempDir) throws IOException
    {
        Path config = tempDir.resolve("pixels-probe.properties");
        Files.write(config, List.of(
                "index.rocksdb.data.path=" + tempDir.resolve("rocksdb"),
                "index.server.max.inbound.message.size=67108864"));
        ConfigFactory.Instance().loadProperties(config.toString());
    }

    private static byte[] encodeLong(long value)
    {
        return new byte[] {
                (byte) (value >>> 56),
                (byte) (value >>> 48),
                (byte) (value >>> 40),
                (byte) (value >>> 32),
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }
}
