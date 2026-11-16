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
package io.pixelsdb.pixels.daemon.index;

import io.grpc.ServerBuilder;
import io.pixelsdb.pixels.common.server.Server;
import io.pixelsdb.pixels.common.utils.ConfigFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * @author hank
 * @create 2025-02-19
 */
public class IndexServer implements Server
{
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 64 * 1024 * 1024;
    private static final Logger log = LogManager.getLogger(IndexServer.class);
    private volatile boolean running = false;
    private final IndexServiceImpl indexService;
    private final io.grpc.Server rpcServer;

    public IndexServer(int port)
    {
        checkArgument(port > 0 && port <= 65535, "illegal rpc port");
        int maxInboundMessageSize = getMaxInboundMessageSize();
        checkArgument(maxInboundMessageSize > 0, "illegal index server inbound message size");
        this.indexService = new IndexServiceImpl();
        this.rpcServer = ServerBuilder.forPort(port)
                .maxInboundMessageSize(maxInboundMessageSize)
                .addService(indexService).build();
    }

    private static int getMaxInboundMessageSize()
    {
        String configured = ConfigFactory.Instance().getProperty(
                "index.server.max.inbound.message.size");
        return configured == null ? DEFAULT_MAX_INBOUND_MESSAGE_SIZE : Integer.parseInt(configured);
    }

    @Override
    public boolean isRunning()
    {
        return this.running;
    }

    @Override
    public void shutdown()
    {
        this.running = false;
        try
        {
            this.rpcServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e)
        {
            log.error("Interrupted when shutdown index server", e);
        }
        this.indexService.close();
    }

    @Override
    public void run()
    {
        try
        {
            this.rpcServer.start();
            this.running = true;
            this.rpcServer.awaitTermination();
        } catch (IOException e)
        {
            log.error("I/O error when running", e);
        } catch (InterruptedException e)
        {
            log.error("Interrupted when running", e);
        } finally
        {
            this.shutdown();
        }
    }
}
