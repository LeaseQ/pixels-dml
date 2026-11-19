/*
 * Copyright 2026 PixelsDB.
 *
 * This file is part of Pixels.
 */
package io.pixelsdb.pixels.daemon.index;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A manually selected probe for the full client -> gRPC -> IndexServer -> RocksDB path.
 * Run with -Dpixels.tag.index.probe.rows=1000000 when a million-row sample is needed.
 */
class IndexServerTagIndexRpcProbeTest
{
    private static final int PORT = 29995;

    @Test
    void appendsTheConfiguredNumberOfRowsThroughGrpc(@TempDir Path tempDir) throws Exception
    {
        configureIsolatedRocksDb(tempDir);
        int rows = Integer.getInteger("pixels.tag.index.probe.rows", 100_000);
        long tableId = 900_000L + System.nanoTime() % 100_000L;
        long indexId = 910_000L + System.nanoTime() % 100_000L;
        byte[] tag = new byte[] {7, 7, 7};

        long buildStart = System.nanoTime();
        List<com.google.protobuf.ByteString> primaryKeys = new ArrayList<>(rows);
        for (long value = 0; value < rows; value++)
        {
            primaryKeys.add(com.google.protobuf.ByteString.copyFrom(encodeLong(value)));
        }
        IndexProto.TagIndexUpdate update = IndexProto.TagIndexUpdate.newBuilder()
                .setTag(com.google.protobuf.ByteString.copyFrom(tag))
                .setTransactionTimestamp(1L)
                .addAllPrimaryKeys(primaryKeys)
                .build();
        IndexProto.AppendTagIndexEntriesRequest request =
                IndexProto.AppendTagIndexEntriesRequest.newBuilder()
                        .setTableId(tableId)
                        .setIndexId(indexId)
                        .addUpdates(update)
                        .build();
        long buildMs = elapsedMillis(buildStart);

        IndexServiceImpl service = new IndexServiceImpl();
        io.grpc.Server server = ServerBuilder.forPort(PORT)
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
            int warmupRows = Integer.getInteger("pixels.tag.index.probe.warmup.rows", 1);
            if (warmupRows > 0)
            {
                List<com.google.protobuf.ByteString> warmupKeys = new ArrayList<>(warmupRows);
                for (long value = 0; value < warmupRows; value++)
                {
                    warmupKeys.add(com.google.protobuf.ByteString.copyFrom(encodeLong(value)));
                }
                IndexProto.AppendTagIndexEntriesResponse warmupResponse =
                        IndexServiceGrpc.newBlockingStub(channel).appendTagIndexEntries(
                                IndexProto.AppendTagIndexEntriesRequest.newBuilder()
                                        .setTableId(tableId)
                                        .setIndexId(indexId)
                                        .addUpdates(IndexProto.TagIndexUpdate.newBuilder()
                                                .setTag(com.google.protobuf.ByteString.copyFrom(tag))
                                                .setTransactionTimestamp(1L)
                                                .addAllPrimaryKeys(warmupKeys)
                                                .build())
                                        .build());
                assertEquals(ErrorCode.SUCCESS, warmupResponse.getErrorCode());
            }
            long rpcStart = System.nanoTime();
            IndexProto.AppendTagIndexEntriesResponse response =
                    IndexServiceGrpc.newBlockingStub(channel).appendTagIndexEntries(request);
            long rpcMs = elapsedMillis(rpcStart);
            assertEquals(ErrorCode.SUCCESS, response.getErrorCode());
            System.out.printf("[PIXELS_TAG_INDEX_RPC_PROBE] rows=%d request_bytes=%d build_ms=%d rpc_ms=%d%n",
                    rows, request.getSerializedSize(), buildMs, rpcMs);

            long streamedKeys = 0;
            int chunks = 0;
            Iterator<IndexProto.TagIndexEntryChunk> streamed =
                    IndexServiceGrpc.newBlockingStub(channel).streamTagIndexEntries(
                            IndexProto.ListTagIndexEntriesRequest.newBuilder()
                                    .setTableId(tableId)
                                    .setIndexId(indexId)
                                    .build());
            while (streamed.hasNext())
            {
                IndexProto.TagIndexEntryChunk chunk = streamed.next();
                assertEquals(ErrorCode.SUCCESS, chunk.getErrorCode());
                streamedKeys += chunk.getPrimaryKeysCount();
                chunks++;
            }
            assertEquals(rows, streamedKeys);
            System.out.printf("[PIXELS_TAG_INDEX_STREAM_PROBE] rows=%d chunks=%d%n", streamedKeys, chunks);
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

    private static long elapsedMillis(long start)
    {
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
