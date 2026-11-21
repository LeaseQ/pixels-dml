/*
 * Copyright 2025 PixelsDB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.pixelsdb.pixels.index.rocksdb;

import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.common.exception.SinglePointIndexException;
import io.pixelsdb.pixels.common.metadata.domain.Column;
import io.pixelsdb.pixels.common.utils.ConfigFactory;
import io.pixelsdb.pixels.common.utils.IndexUtils;
import io.pixelsdb.pixels.core.TypeDescription;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @package: io.pixelsdb.pixels.index.rocksdb
 * @className: RocksDBFactory
 * @author: AntiO2
 * @date: 2025/9/8 10:33
 */
public class RocksDBFactory
{
    private static final Logger logger = LoggerFactory.getLogger(RocksDBFactory.class);
    private static final String dbPath = ConfigFactory.Instance().getProperty("index.rocksdb.data.path");
    private static final boolean multiCF = Boolean.parseBoolean(ConfigFactory.Instance().getProperty("index.rocksdb.multicf"));
    private static final boolean tableScoped = Boolean.parseBoolean(
            Optional.ofNullable(ConfigFactory.Instance().getProperty("index.rocksdb.table.scoped"))
                    .orElse("false"));
    private static final long SHARED_DB_ID = Long.MIN_VALUE;

    private static final class DbState
    {
        private final long tableId;
        private final String path;
        private final Map<String, ColumnFamilyHandle> cfHandles = new ConcurrentHashMap<>();
        private RocksDB instance;
        private int reference;

        private DbState(long tableId, String path)
        {
            this.tableId = tableId;
            this.path = path;
        }
    }

    private static final Map<Long, DbState> dbStates = new HashMap<>();
    // Kept for package-private test helpers that call createRocksDB(path) directly.
    private static final Map<String, ColumnFamilyHandle> legacyCfHandles = new ConcurrentHashMap<>();

    private static Cache blockCache;
    private static final long blockCacheCapacity = Long.parseLong(ConfigFactory.Instance().getProperty("index.rocksdb.block.cache.capacity"));
    private static final int blockCacheShardBits = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.block.cache.shard.bits"));

    private static final String defaultColumnFamily = new String(RocksDB.DEFAULT_COLUMN_FAMILY, StandardCharsets.UTF_8);
    private static final Map<Long, Integer> indexKeyLenCache = new ConcurrentHashMap<>();
    private static final Integer VARIABLE_LEN_SENTINEL = -2;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "RocksDB-Metrics-Logger");
        thread.setDaemon(true);
        return thread;
    });

    private RocksDBFactory() { }

    static RocksDB createRocksDB(String rocksDBPath) throws RocksDBException,SinglePointIndexException
    {
        return createRocksDB(rocksDBPath, legacyCfHandles);
    }

    private static RocksDB createRocksDB(String rocksDBPath,
                                         Map<String, ColumnFamilyHandle> targetCfHandles)
            throws RocksDBException,SinglePointIndexException
    {
        long openStartNanos = System.nanoTime();
        // 1. Get existing column families (returns empty list for new database)
        List<byte[]> existingColumnFamilies;
        try (Options listOptions = new Options())
        {
            existingColumnFamilies = RocksDB.listColumnFamilies(listOptions, rocksDBPath);
        } catch (RocksDBException e)
        {
            // For new database, return list containing only default column family
            existingColumnFamilies = Collections.singletonList(RocksDB.DEFAULT_COLUMN_FAMILY);
        }
        long listColumnFamiliesMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - openStartNanos);
        // 2. Ensure default column family is included
        boolean existDefaultCF = false;
        for (byte[] cf : existingColumnFamilies)
        {
            if (Arrays.equals(cf, RocksDB.DEFAULT_COLUMN_FAMILY))
            {
                existDefaultCF = true;
                break;
            }
        }
        if (!existDefaultCF)
        {
            existingColumnFamilies = new ArrayList<>(existingColumnFamilies);
            existingColumnFamilies.add(RocksDB.DEFAULT_COLUMN_FAMILY);
        }

        if (blockCache == null)
        {
            blockCache = new LRUCache(blockCacheCapacity, blockCacheShardBits);
        }

        // 3. Prepare column family descriptors
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        for(byte[] existingColumnFamily: existingColumnFamilies)
        {
            long[] ids = IndexUtils.parseTableAndIndexId(existingColumnFamily);
            Integer keyLen = null;
            if(ids != null)
            {
                long tableId = ids[0];
                long indexId = ids[1];
                try
                {
                    keyLen = getIndexKeyLen(tableId, indexId);
                } catch (MetadataException ignored)
                {

                }
            }
            descriptors.add(createCFDescriptor(existingColumnFamily, keyLen));
        }
        long descriptorPreparationMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - openStartNanos) - listColumnFamiliesMs;

        // 4. Open DB
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        int maxBackgroundFlushed = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.max.background.flushes"));
        int maxBackgroundCompactions = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.max.background.compactions"));
        int maxSubcompactions = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.max.subcompactions"));
        int maxOpenFiles = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.max.open.files"));
        String dbWriteBufferSizeValue =
                ConfigFactory.Instance().getProperty("index.rocksdb.db.write.buffer.size");
        long dbWriteBufferSize = dbWriteBufferSizeValue == null
                ? 0L : Long.parseLong(dbWriteBufferSizeValue);
        boolean enableRocksDBStats = Boolean.parseBoolean(ConfigFactory.Instance().getProperty("index.rocksdb.stats.enabled"));

        DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxBackgroundFlushes(maxBackgroundFlushed)
                .setMaxBackgroundCompactions(maxBackgroundCompactions)
                .setMaxSubcompactions(maxSubcompactions)
                .setMaxOpenFiles(maxOpenFiles);

        // A per-column-family write buffer is multiplied by the number of
        // vnodes in the multi-CF layout. Keep the historical behavior when
        // the cap is zero, but allow an isolated deployment to bound total
        // memtable memory across all column families.
        if (dbWriteBufferSize > 0)
        {
            dbOptions.setDbWriteBufferSize(dbWriteBufferSize);
        }

        if(enableRocksDBStats)
        {
            int statsInterval = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.stats.interval"));
            String statsPath = ConfigFactory.Instance().getProperty("index.rocksdb.stats.path");
            Statistics stats = new Statistics();
            dbOptions.setStatistics(stats)
                    .setStatsDumpPeriodSec(statsInterval)
                    .setDbLogDir(statsPath);

        }
        long rocksDBOpenStartNanos = System.nanoTime();
        RocksDB db = RocksDB.open(dbOptions, rocksDBPath, descriptors, handles);
        long rocksDBOpenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - rocksDBOpenStartNanos);
        long totalOpenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - openStartNanos);
        logger.info("[PIXELS_ROCKSDB_OPEN_METRIC] path={} existing_cf={} list_cf_ms={} " +
                        "descriptor_ms={} db_open_ms={} total_ms={}",
                rocksDBPath, existingColumnFamilies.size(), listColumnFamiliesMs,
                descriptorPreparationMs, rocksDBOpenMs, totalOpenMs);
        if(enableRocksDBStats)
        {
            startRocksDBLogThread(db);
        }
        // 5. Save handles for reuse
        for (int i = 0; i < descriptors.size(); i++)
        {
            String cfName = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
            targetCfHandles.putIfAbsent(cfName, handles.get(i));
        }

        return db;
    }
    private static ColumnFamilyDescriptor createCFDescriptor(byte[] name, Integer keyLen)
    {
        ConfigFactory config = ConfigFactory.Instance();

        long blockSize = Long.parseLong(config.getProperty("index.rocksdb.block.size"));
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
                .setFilterPolicy(new BloomFilter(10, false))
                .setWholeKeyFiltering(false)
                .setBlockSize(blockSize)
                .setCacheIndexAndFilterBlocks(true)
                .setPinL0FilterAndIndexBlocksInCache(true)
                .setBlockCache(blockCache);

        // ColumnFamily Options
        long writeBufferSize = Long.parseLong(config.getProperty("index.rocksdb.write.buffer.size"));
        int maxWriteBufferNumber = Integer.parseInt(config.getProperty("index.rocksdb.max.write.buffer.number"));
        int minWriteBufferNumberToMerge = Integer.parseInt(config.getProperty("index.rocksdb.min.write.buffer.number.to.merge"));

        // Compaction Options
        int level0FileNumCompactionTrigger = Integer.parseInt(config.getProperty("index.rocksdb.level0.file.num.compaction.trigger"));
        long maxBytesForLevelBase = Long.parseLong(config.getProperty("index.rocksdb.max.bytes.for.level.base"));
        int maxBytesForLevelMultiplier = Integer.parseInt(config.getProperty("index.rocksdb.max.bytes.for.level.multiplier"));
        long targetFileSizeBase = Long.parseLong(config.getProperty("index.rocksdb.target.file.size.base"));
        int targetFileSizeMultiplier = Integer.parseInt(config.getProperty("index.rocksdb.target.file.size.multiplier"));
        int fixedLengthPrefix = Integer.parseInt(config.getProperty("index.rocksdb.prefix.length"));
        if (keyLen != null)
        {
            // Prefix must only cover the logical lookup key.
            // It must not include the encoded timestamp suffix.
            fixedLengthPrefix = keyLen + (multiCF ? 0 : Long.BYTES);
        }
        CompactionStyle compactionStyle = CompactionStyle.valueOf(config.getProperty("index.rocksdb.compaction.style"));

        // Compression Options
        CompressionType compressionType = CompressionType.valueOf(config.getProperty("index.rocksdb.compression.type"));
        CompressionType bottommostCompressionType = CompressionType.valueOf(config.getProperty("index.rocksdb.bottommost.compression.type"));

        ColumnFamilyOptions cfOptions = new ColumnFamilyOptions()
                .setWriteBufferSize(writeBufferSize)
                .setMaxWriteBufferNumber(maxWriteBufferNumber)
                .setMinWriteBufferNumberToMerge(minWriteBufferNumberToMerge)
                .setMemtablePrefixBloomSizeRatio(0.1)
                .setTableFormatConfig(tableConfig)
                .setLevel0FileNumCompactionTrigger(level0FileNumCompactionTrigger)
                .setMaxBytesForLevelBase(maxBytesForLevelBase)
                .setMaxBytesForLevelMultiplier(maxBytesForLevelMultiplier)
                .setTargetFileSizeBase(targetFileSizeBase)
                .setTargetFileSizeMultiplier(targetFileSizeMultiplier)
                .setCompressionType(compressionType)
                .setBottommostCompressionType(bottommostCompressionType)
                .setCompactionStyle(compactionStyle)
                .useFixedLengthPrefixExtractor(fixedLengthPrefix);

        return new ColumnFamilyDescriptor(name, cfOptions);
    }

    /**
     * Get or create a ColumnFamily for (tableId, indexId).
     */
    public static synchronized ColumnFamilyHandle getOrCreateColumnFamily(long tableId, long indexId, int vNodeId) throws RocksDBException,SinglePointIndexException
    {
        DbState state = stateFor(tableId);
        String cfName = IndexUtils.getCFName(tableId, indexId, vNodeId, multiCF);

        // Return cached handle if exists
        if (state.cfHandles.containsKey(cfName))
        {
            // Every index wrapper owns one factory reference.  The existing
            // handle fast path used to return without acquiring that
            // reference, so closing a short-lived wrapper could close the
            // shared RocksDB while another index was still using it.
            getRocksDB(tableId);
            return state.cfHandles.get(cfName);
        }

        Integer keyLen = null;
        try
        {
            keyLen = getIndexKeyLen(tableId, indexId);
        } catch (MetadataException ignored)
        {

        }

        RocksDB db = getRocksDB(tableId);
        // getRocksDB() may have opened an existing database and populated
        // the state handles, so check again before attempting to create the family.
        ColumnFamilyHandle existingHandle = state.cfHandles.get(cfName);
        if (existingHandle != null)
        {
            return existingHandle;
        }
        ColumnFamilyDescriptor newCF = createCFDescriptor(cfName.getBytes(StandardCharsets.UTF_8), keyLen);
        ColumnFamilyHandle handle = db.createColumnFamily(newCF);
        state.cfHandles.put(cfName, handle);
        return handle;
    }

    /**
     * Create the missing vnode column families in one RocksDB call.
     *
     * <p>RocksDB stores all multi-CF primary indexes in one database. Creating
     * the families one by one makes the first grouped write pay the full CF
     * creation cost serially for every vnode. The batch API keeps the existing
     * handles and locking semantics while allowing RocksDB to perform the
     * metadata operation as one native call.</p>
     */
    public static synchronized void preCreateColumnFamilies(long tableId, long indexId,
                                                             Collection<Integer> vNodeIds)
            throws RocksDBException, SinglePointIndexException
    {
        if (!multiCF || vNodeIds == null || vNodeIds.isEmpty())
        {
            return;
        }

        // Open the database before constructing RocksDB-native descriptors so
        // the JNI library is loaded on runtimes where BloomFilter constructors
        // resolve native symbols eagerly.
        DbState state = stateFor(tableId);
        RocksDB db = getOpenRocksDB(tableId);
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        for (Integer vNodeId : new TreeSet<>(vNodeIds))
        {
            String cfName = IndexUtils.getCFName(tableId, indexId, vNodeId, true);
            if (state.cfHandles.containsKey(cfName))
            {
                continue;
            }
            Integer keyLen = null;
            try
            {
                keyLen = getIndexKeyLen(tableId, indexId);
            }
            catch (MetadataException ignored)
            {
            }
            descriptors.add(createCFDescriptor(cfName.getBytes(StandardCharsets.UTF_8), keyLen));
        }
        if (descriptors.isEmpty())
        {
            return;
        }

        List<ColumnFamilyHandle> handles = db.createColumnFamilies(descriptors);
        for (int i = 0; i < descriptors.size(); i++)
        {
            String cfName = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
            state.cfHandles.putIfAbsent(cfName, handles.get(i));
        }
    }

    protected static synchronized Map<String, ColumnFamilyHandle> getAllCfHandles()
    {
        return legacyCfHandles;
    }

    public static synchronized RocksDB getRocksDB() throws RocksDBException,SinglePointIndexException
    {
        return getRocksDB(0L);
    }

    public static synchronized RocksDB getRocksDB(long tableId)
            throws RocksDBException, SinglePointIndexException
    {
        DbState state = stateFor(tableId);
        if (state.instance == null || state.instance.isClosed())
        {
            state.instance = createRocksDB(state.path, state.cfHandles);
            state.reference = 0;
        }
        state.reference++;
        return state.instance;
    }

    /**
     * Return the already-open shared database without changing its reference
     * count. Callers must already own a factory reference.
     */
    static synchronized RocksDB getOpenRocksDB() throws RocksDBException, SinglePointIndexException
    {
        return getOpenRocksDB(0L);
    }

    static synchronized RocksDB getOpenRocksDB(long tableId)
            throws RocksDBException, SinglePointIndexException
    {
        DbState state = stateFor(tableId);
        if (state.instance == null || state.instance.isClosed())
        {
            state.instance = createRocksDB(state.path, state.cfHandles);
            state.reference = 0;
        }
        return state.instance;
    }

    public static synchronized void close()
    {
        close(0L);
    }

    public static synchronized void close(long tableId)
    {
        DbState state = dbStates.get(stateKey(tableId));
        if (state == null || state.instance == null || state.instance.isClosed())
        {
            return;
        }
        if (state.reference > 0)
        {
            state.reference--;
        }
        if (state.reference == 0)
        {
            for (ColumnFamilyHandle handle : state.cfHandles.values())
            {
                handle.close();
            }
            state.cfHandles.clear();
            state.instance.close();
            state.instance = null;
        }
    }

    static synchronized void clearCFHandles()
    {
        legacyCfHandles.clear();
        for (DbState state : dbStates.values())
        {
            state.cfHandles.clear();
        }
    }

    public static synchronized String getDbPath()
    {
        return dbPath;
    }

    public static synchronized String getDbPath(long tableId)
    {
        return statePath(tableId);
    }

    private static long stateKey(long tableId)
    {
        return tableScoped ? tableId : SHARED_DB_ID;
    }

    private static String statePath(long tableId)
    {
        if (!tableScoped)
        {
            return dbPath;
        }
        return new File(dbPath, "table-" + tableId).getPath();
    }

    private static DbState stateFor(long tableId)
    {
        long stateKey = stateKey(tableId);
        return dbStates.computeIfAbsent(stateKey, ignored -> new DbState(stateKey, statePath(tableId)));
    }

    private static Integer getIndexKeyLen(long tableId, long indexId) throws MetadataException
    {
        // Try to retrieve from cache using only indexId
        Integer cachedLen = indexKeyLenCache.get(indexId);
        if (cachedLen != null)
        {
            return cachedLen.equals(VARIABLE_LEN_SENTINEL) ? null : cachedLen;
        }

        // Cache miss: Perform the metadata lookup
        List<Column> keyColumns = IndexUtils.extractInfoFromIndex(tableId, indexId);
        TypeDescription keySchema = TypeDescription.createSchemaFromColumns(keyColumns);

        int keyLen = 0;
        Integer result = null;

        if (keySchema.getChildren() != null)
        {
            boolean isFixedLen = true;
            for (TypeDescription typeDescription : keySchema.getChildren())
            {
                int colLen = IndexUtils.keyLengthOf(typeDescription.getCategory().getExternalJavaType());
                if (colLen == -1)
                {
                    isFixedLen = false;
                    break;
                }
                keyLen += colLen;
            }

            if (isFixedLen)
            {
                result = keyLen;
            }
        }

        // Update cache (store result or sentinel)
        indexKeyLenCache.put(indexId, result == null ? VARIABLE_LEN_SENTINEL : result);

        return result;
    }

    private static void startRocksDBLogThread(RocksDB db)
    {
        int logInterval = Integer.parseInt(ConfigFactory.Instance().getProperty("index.rocksdb.log.interval"));
        List<RocksDB> dbList = Collections.singletonList(db);

        scheduler.scheduleAtFixedRate(() ->
        {
            try
            {
                // 1. Get RocksDB Native Metrics
                Map<MemoryUsageType, Long> memoryUsage = MemoryUtil.getApproximateMemoryUsageByType(dbList, null);
                long tableReaders = memoryUsage.getOrDefault(MemoryUsageType.kTableReadersTotal, 0L);
                long memTable = memoryUsage.getOrDefault(MemoryUsageType.kMemTableTotal, 0L);
                long blockCacheOnly = db.getLongProperty("rocksdb.block-cache-usage");
                long indexFilterOnly = Math.max(0, tableReaders - blockCacheOnly);
                long totalNativeBytes = tableReaders + memTable;

                // 2. Get JVM Heap Metrics
                Runtime runtime = Runtime.getRuntime();
                long heapMax = runtime.maxMemory();
                long heapCommitted = runtime.totalMemory();
                long heapUsed = heapCommitted - runtime.freeMemory();

                // 3. Format string with both RocksDB and JVM data
                // We use GiB for all units to keep the Shell script calculations simple
                // 3. Get Current Timestamp (ms)
                long timestamp = System.currentTimeMillis();
                double GiB = 1024.0 * 1024.0 * 1024.0;

                String formattedMetrics = String.format(
                        "Timestamp=%d RocksDB_Native[Total=%.4f GiB (%d Bytes), MemTable=%.4f GiB (%d Bytes), " +
                                "BlockCache=%.4f GiB (%d Bytes)]" +
                                "JVM_Heap[Used=%.4f GiB (%d Bytes), Committed=%.4f GiB (%d Bytes), Max=%.4f GiB (%d Bytes)]",
                        timestamp,
                        totalNativeBytes / GiB,
                        memTable / GiB,
                        blockCacheOnly / GiB,
                        indexFilterOnly / GiB,
                        heapUsed / GiB,
                        heapCommitted / GiB,
                        heapMax / GiB
                );

                logger.info("[RocksDB Metrics] {}", formattedMetrics);
            }
            catch (Exception e)
            {
                logger.error("Error occurred during RocksDB metrics collection", e);
            }
        }, 0, logInterval, TimeUnit.SECONDS);
    }
}
