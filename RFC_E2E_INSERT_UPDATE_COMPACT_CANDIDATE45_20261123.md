# RFC: Fresh INSERT -> UPDATE -> COMPACT E2E Evidence (2026-11-23)

Status: validation evidence; no new production code change

## Scope

This report records a fresh 68-column, 1M-row table-to-table INSERT followed by
an exact-key tag-aware UPDATE and COMPACT on the isolated `cds20` runtime:

```text
host: 10.77.110.36
runtime: /home/pixels/validation-million-update/runtime/e2e-fresh-20261122
schema: validationfresh
source: e2e68_fresh_candidate9_20261123
target: e2e68_fresh_candidate45_20261123
```

## Follow-up: packed grouped-primary wire probe (candidate57/58/59)

The packed probe added an opt-in wire representation for grouped INSERT
primary-index entries. It kept the existing grouped bucket partitioning,
RocksDB WriteBatch/WAL path, MainIndex update, and response semantics; only
the RPC payload representation changed. The isolated daemon was restarted
with JDK 23, recovered checkpoint `recovery_cds20_60032.bin`, completed the
existing `get status -> markReady(recoveryEpoch)` handshake, and the exact
1M-row source table was reused for each fresh target.

All three candidates passed the exact correctness check:

```text
count=1,000,000, distinct_id=1,000,000,
min_id=7,000,000,001, max_id=7,001,000,000
```

The first packed run (candidate57) was slower at 10.645 s (93.9K rows/s).
A same-runtime baseline (candidate58) took 9.233 s (108.3K rows/s), while a
second packed run (candidate59) took 8.590 s (116.4K rows/s). The corresponding
`primary_put_wall_ms` sums were 78.997 s, 55.581 s, and 42.188 s. The spread
shows that the packed payload does not produce a stable end-to-end gain; the
dominant variation remains in the grouped primary/RocksDB writer finish path,
not in the wire bytes alone. Candidate59 is close to, but not materially
better than, the accepted candidate56 baseline (8.627 s / 115.9K rows/s).

The packed implementation is therefore rejected as a default optimization.
The direct next optimization would need to avoid reconstructing a full
`PrimaryIndexEntry` protobuf list after decoding, or change the RocksDB/index
write path itself. That is a larger core-path change and needs a separate
design/profiling pass; this probe does not justify merging it.

Remote artifacts:

```text
logs/insert-candidate57-indexed-32writers-packed-primary-20261123.log
logs/insert-candidate58-indexed-32writers-baseline-after-packed-20261123.log
logs/insert-candidate59-indexed-32writers-packed-primary-after-baseline-20261123.log
logs/console-packed-primary-20261123.log
logs/console-baseline-primary-20261123.log
```

## Follow-up: JFR bottleneck probe and MainIndexBuffer reserve (candidate60-66)

The first JFR probe was attached to Trino and the daemon worker. It showed the
worker mostly idle in epoll; the IndexService is hosted by the Retina process,
so the probe was corrected and repeated against Trino plus Retina.

The corrected candidate61 recording covered the INSERT time window and passed
the exact 1M-row check at 6.754 s. Retina execution samples were concentrated
in the existing protobuf and index path: `PrimaryIndexEntry`/request parsing,
`IndexServiceImpl.putPrimaryIndexEntries`, `SqliteMainIndex.putEntries`, and
`MainIndexBuffer.insert`. Native samples also included 32
`RocksDB.write0` calls. Allocation samples included SQLite main-index writes,
`MainIndexBuffer.insert`, `RocksDBIndex.putPrimaryEntriesInternal`, and
`RocksDBIndex.toKeyBuffer`. This confirms that the bottleneck is multi-stage;
the wire representation alone is not a sufficient explanation.

As a low-risk follow-up, candidate62-64 added a reserve operation for a new
same-file INSERT batch so the main-index row map would not grow from the
default HashMap capacity. All candidates passed the exact check:

```text
candidate62: 10.984 s (91.0K rows/s)
candidate63:  6.284 s (159.1K rows/s)
candidate64:  6.401 s (156.2K rows/s)
```

After restoring the old jar, the same two-run warm/cold comparison was:

```text
candidate65: 10.705 s (93.4K rows/s)
candidate66:  6.531 s (153.1K rows/s)
```

The warm difference is only about 3% and is smaller than the observed
cold-start/runtime spread. The internal `primary_put_wall` and main-index
flush metrics likewise do not show a stable improvement. The reserve change
is therefore rejected and the isolated runtime is restored to the old jar and
connector baseline.

Remote artifacts:

```text
logs/insert-candidate60-jfr-baseline-20261123.log
logs/insert-candidate61-jfr-retina-baseline-20261123.log
logs/insert-candidate62-mainindex-reserve-20261123.log
logs/insert-candidate63-mainindex-reserve-repeat-20261123.log
logs/insert-candidate64-mainindex-reserve-repeat2-20261123.log
logs/insert-candidate65-old-baseline-repeat-20261123.log
logs/insert-candidate66-old-baseline-hot-20261123.log
logs/jfr-retina-candidate61.jfr
logs/jfr-trino-candidate61.jfr
```

The next code-level optimization must target a direct protobuf-to-index or
bulk main-index path with correctness and WAL ordering preserved; another
small container-capacity tweak is not justified by this evidence.

The main runtime was not touched. The target used `pk='id'`,
`pk_scheme='rocksdb'`, the grouped primary-index route, and the no-copy
compaction CLI from `8dcf2467`.

## Configuration and correctness premise

- Trino INSERT: 32 fixed writers, `task_scale_writers_enabled=false`.
- Grouped primary writes: enabled; daemon grouped primary write pool: 32.
- Trino UPDATE: primary-key resolve batch size 65,536, parallelism 16.
- COMPACT: tag-aware, `-n no -c 8`.
- Final correctness tuple after COMPACT:

  ```text
  count=1000000
  distinct_id=1000000
  min_id=7000000001
  max_id=7001000000
  tag_1_type='U46' count=1000000
  tag_1_para=46.0000 count=1000000
  tag_2_type IS NULL count=1000000
  ```

The tag update was therefore materialized into the physical compacted columns,
while an untouched tag column remained NULL.

## Measurements

All rates below are SQL shell wall rates unless marked otherwise.

| Stage | Result | Rate / evidence | Status |
|---|---:|---:|---|
| INSERT, 16 writers, candidate42 | 5.909 s | 169.2K rows/s | correct |
| INSERT, 32 writers, candidate43 | 5.171 s | 193.4K rows/s | correct |
| INSERT, 32 writers, candidate45 | 9.035 s | 110.7K rows/s | correct; high variance |
| UPDATE before COMPACT, candidate45 | 6.138 s | 162.9K rows/s | exact MPP route |
| COMPACT, candidate45 | 51.493 s | 1 output | correct |
| UPDATE after COMPACT, candidate45 | 4.173 s | 239.6K rows/s | exact MPP route |

The candidate45 COMPACT stage metrics were:

```text
tag snapshot: 1,000,000 rows, 62 RPC, 10.416 s
physical rewrite: 1 output, worker wall 16.041 s
primary relocation: 1,000,000 rows, 128 RPC
publish: 8.840 s
CLI wall: 51.493 s
```

Connector UPDATE metrics for the two comparable candidate45 runs:

```text
before COMPACT: primary_key_resolve_ms=1449.417,
                request_build_ms=218.857, rpc_ms=427.794,
                total_ms=2166.798
after COMPACT:  primary_key_resolve_ms=1281.344,
                request_build_ms=198.402, rpc_ms=421.361,
                total_ms=1951.017
```

The compacted table reduced SQL wall time by about 32% and increased UPDATE
rate from about 163K to 240K rows/s. This is evidence that the post-INSERT
fragment layout is a material UPDATE factor, not merely a measurement artifact.

## INSERT route diagnosis

The daemon emitted `PIXELS_PRIMARY_INDEX_GROUPED_METRIC` with
`grouped_threads=32`. Each INSERT file used one grouped primary-index RPC; the
connector publish stage was small (about 0.3 s for candidate45). The grouped
primary stage was roughly 1.6 s per concurrent request, so the remaining SQL
wall is not explained by metadata publication alone.

The historical 315K rows/s INSERT point was not reproduced in this fresh run.
The current evidence supports a conservative INSERT baseline of roughly
110--193K rows/s on this runtime, not a target-level claim.

## Configuration lesson

An experiment temporarily changed `pixels.insert.index.flush.parallelism` from
4 to 8. That property is not active on the grouped-primary path because the
writer sends the grouped RPC before the bucket-flush branch. The slower
candidate44 result after that restart is therefore not valid evidence that
flush parallelism 8 is worse; it also mixed runtime restart/cache state.

The isolated Trino configuration was restored to flush parallelism 4 and the
UPDATE resolve settings were explicitly persisted in `jvm.config`. Future
experiments must verify the effective JVM command line before accepting a
measurement; one candidate45 UPDATE accidentally ran with the default serial
resolve setting and reached only 86.5K rows/s, so it is rejected.

## Conclusion and next experiments

No code change is accepted from this round. The no-copy relocation-map change
from `8dcf2467` remains the latest code optimization.

The next safe experiments are:

1. Stabilize the INSERT benchmark with identical warm-up and runtime state,
   then separate source scan/encoding, grouped primary writes, and Trino
   finishing time using the existing page-sink and query timeline metrics.
2. Test grouped daemon write-pool sizing as a single variable, restarting all
   isolated daemon roles and verifying `grouped_threads` in the daemon log.
3. For UPDATE, compare resolve parallelism 16 versus 32 only after checking the
   effective command line, and retain the compacted-table comparison.

No result in this report establishes the stretch goal near 1M rows/s. The
current stable UPDATE evidence is in the lower hundreds of thousands after
COMPACT, while INSERT still needs a reproducible improvement before code is
changed.

## Follow-up: grouped primary writer pool 64 threads (candidate46/47)

To isolate daemon-side grouped primary-index write-pool sizing, all three
isolated daemon roles were restarted with
`-Dpixels.index.grouped.primary.write.threads=64`. Trino remained on the
validated p16 UPDATE configuration and the INSERT shape stayed at 32 writers.
Both 1M-row table-to-table INSERTs passed the basic correctness check:

```text
candidate46: 8.765 s, 114,090 rows/s,
             count=1,000,000, distinct_id=1,000,000,
             min_id=7,000,000,001, max_id=7,001,000,000
candidate47: 6.176 s, 161,917 rows/s,
             count=1,000,000, distinct_id=1,000,000,
             min_id=7,000,000,001, max_id=7,001,000,000
```

The two 64-thread runs varied substantially and neither exceeded the earlier
32-writer candidate43 result of 193,386 rows/s (5.171 s). Therefore 64 daemon
threads is not accepted as a stable optimization. The isolated daemon configs
were restored to 32 threads after the experiment; no source code change was
made.

This result also narrows the next INSERT investigation: increasing the grouped
daemon pool alone is insufficient. The next useful step is to hold the runtime
warm and instrument the Trino page-sink/source encoding and grouped-primary
request scheduling stages, then test only the stage shown to dominate.

## Follow-up: fixed-width primary-key encoding probe (candidate50/51, rejected)

The indexed INSERT path encoded every fixed-width primary-key cell through
`ByteBuffer.allocate(...).put...().array()`. A local candidate replaced only
that vector-to-byte conversion with equivalent big-endian byte-array writes and
added signed `BIGINT` boundary coverage. The focused `pixels-core` test passed
(5 tests, 0 failures), and the candidate connector built and loaded in the
isolated Trino.

The candidate was tested on the same materialized 1M-row source and explicit
`pk='id'` table metadata. Both arms passed the exact count/distinct/range check:

```text
candidate50, first run: 9.192 s CLI wall, 108.8K rows/s
  Trino elapsed: 7.17 s
  sink sums across 32 writers: write_ms=12.800 s, index_ms=16.398 s

candidate51, second run: 6.173 s CLI wall, 162.0K rows/s
  Trino elapsed: 4.19 s
  sink sums across 32 writers: write_ms=1.314 s, index_ms=2.248 s
```

The second arm did not beat the accepted 32-writer result of 193.4K rows/s,
and the first arm showed a large cold-start tail. The change therefore does
not establish a stable end-to-end improvement; both the local worktree and the
isolated runtime were restored to the previous implementation. This probe is
rejected, not committed as code.

During the same follow-up, a planned 24-writer arm was rejected by Trino 466
before reading a source row: `task_max_writer_count must be a power of 2: 24`.
It produced no data and must not be compared with historical 24-writer
measurements from a different runtime/configuration.

The evidence further narrows the INSERT direction: fixed-width primary-key
allocation is not the dominant stable end-to-end lever. The next probe should
stay at the query/source scheduling and Exchange/task-finishing layer, with
the existing sink and grouped-RPC metrics retained for attribution.

## Query operator split and 64-writer recheck (candidate52/53)

The restored connector was used for a fresh 32-writer baseline (candidate52).
The query API supplied an operator-level split in addition to the connector
metrics:

```text
candidate52: CLI wall 9.563 s, Trino elapsed 7.59 s, correct 1M-row range
TableScanOperator:    getOutputWall=8.60 s, CPU=2.13 s, 1,000,000 rows
TableWriterOperator:  addInputWall=27.66 s, CPU=13.26 s, finishWall=56.82 s
stage-1 Exchange:      getOutputWall=4.09 s, blockedWall=46.11 s
metadata publish:      0.387 s
```

The operator times are sums across drivers, not end-to-end wall time. They
nevertheless identify the active shape: page-sink/table-writer processing and
Exchange backpressure dominate the query-level work; metadata publication is
small. This also explains why cold and warm runs vary sharply even when the
SQL is identical.

To test whether more writer pipelines relieve that pressure, candidate53 used
the next legal Trino writer count, 64, with the daemon back at 32 grouped
threads:

```text
candidate53: CLI wall 7.021 s, Trino elapsed 5.06 s, 142,429 rows/s
correctness: count=1,000,000, distinct_id=1,000,000,
             min_id=7,000,000,001, max_id=7,001,000,000
```

This did not beat the accepted 32-writer result of 193,386 rows/s. Combined
with the earlier 16/32 comparison and the rejected 64-daemon-thread test,
writer-count expansion is not accepted as the next optimization. Future INSERT
work should target the page-sink conversion/encoding and Exchange scheduling
with stage-level evidence, rather than increasing concurrency again.

The isolated runtime remains on the restored old connector, 32 daemon grouped
threads, and p16 UPDATE resolver settings.

## Follow-up: PageSink stage split and grouped-primary negative control (candidate54/55)

Candidate54 added temporary timing around the Trino PageSink conversion loop.
The instrumentation was deployed only to the isolated runtime and then
removed. The 1M-row INSERT used the same 32-writer shape and passed the exact
count/distinct/range check:

```text
candidate54: CLI wall=8.023 s, Trino elapsed=6.22 s
correctness: count=1,000,000, distinct_id=1,000,000,
             min_id=7,000,000,001, max_id=7,001,000,000
PageSink conversion sum across 32 sinks: 4.308 s
Pixels writer write sum:                    18.621 s
Pixels index append sum:                    14.042 s
primary_put sum / wall sum:                 46.410 s / 47.530 s
main-index flush sum:                        3.221 s
TableWriter finishWall sum:                 56.82 s
```

These timings are sums across drivers, not end-to-end wall time. They rule out
the PageSink cell-conversion loop as the dominant stable bottleneck. The
primary-index write and writer finish path remain the stronger candidates.

Candidate55 then disabled the connector's grouped-primary route while keeping
the 32-writer query and `flush.parallelism=4`. This is a valid correctness
control, but it is a clear performance regression:

```text
candidate55: CLI wall=18.107 s, Trino elapsed=16.21 s
correctness: count=1,000,000, distinct_id=1,000,000,
             min_id=7,000,000,001, max_id=7,001,000,000
primary RPCs: 128 per sink (32 sinks)
primary_put sum / wall sum: 1,352.861 s / 343.604 s
```

The grouped-primary route is therefore retained. The experiment also explains
why `flush.parallelism=4` did not affect candidate54: when grouped writes are
enabled and no staged bucket flush occurs, one grouped request is sent before
the per-vnode flush branch. No source optimization is accepted from these two
probes; the isolated Trino was restored to grouped-primary=true with the full
runtime environment.

## Remote artifacts

```text
logs/insert-candidate42-indexed-16writers-20261123.log
logs/insert-candidate43-indexed-32writers-20261123.log
logs/insert-candidate44-indexed-32writers-flush8-20261123.log
logs/insert-candidate45-indexed-32writers-flush4-grouped-20261123.log
logs/update-candidate45-after-insert-p16-20261123.log
logs/compact-candidate45-full-e2e-no-copy-20261123.log
logs/update-candidate45-after-compact-p16-20261123.log
logs/insert-candidate46-indexed-32writers-grouped64-20261123.log
logs/insert-candidate47-indexed-32writers-grouped64-20261123.log
logs/retina-grouped64-mark-ready-19990-20261123.log
logs/retina-grouped32-restore-mark-ready-20261123.log
logs/test-pk-byte-encoding-20261123.log
logs/install-pk-byte-encoding-20261123.log
logs/build-connector-pk-byte-encoding-20261123.log
logs/insert-candidate50-indexed-32writers-pk-encoding-20261123.log
logs/insert-candidate51-indexed-32writers-pk-encoding-20261123.log
logs/console-pk-byte-encoding-20261123.log
logs/console-restore-old-connector-20261123.log
logs/insert-candidate52-indexed-32writers-baseline-20261123.log
logs/insert-candidate53-indexed-64writers-baseline-20261123.log
logs/query-candidate52-summary-20261123.txt
logs/query-candidate52-operators-20261123.txt
logs/build-sink-stage-instrumentation-20261123.log
logs/insert-candidate54-indexed-32writers-sink-stages-20261123.log
logs/insert-candidate55-indexed-32writers-grouped-false-20261123.log
```

## Follow-up: direct packed grouped-primary write (candidate67/68/69)

The earlier packed-wire probe was rejected because the daemon decoded the
payload back into the full protobuf entry list before entering the grouped
RocksDB path. This follow-up kept the wire format opt-in, but added a direct
`PackedPrimaryIndexEntries` representation and a `SinglePointIndex` hook so the
RocksDB write can consume key/row coordinates without reconstructing protobuf
entries. Canonical entries are still materialized once for the SQLite main
index, and the cache is updated after a successful RocksDB write when enabled.
The default route remains unchanged unless
`-Dpixels.index.grouped.primary.write.packed=true` is set.

Candidate67 initially failed before data validation because the remote Maven
repository mixed a stale `pixels-index-main-sqlite` artifact (calling the
rejected `MainIndexBuffer.reserve(long,int)` API) with the current common jar.
The source had no such call. Reinstalling `pixels-index-main-sqlite` before
building daemon removed the ABI mismatch; the rebuilt fat jar was checked with
`javap` before redeployment.

The fixed candidate completed two full 1M-row INSERTs on the isolated runtime:

```text
candidate68: CLI wall=16.605 s, Trino timeline=14.728 s
            primary_put_ms=5086.714, primary_put_wall_ms=5810.691
            correctness: count=1,000,000, distinct_id=1,000,000,
                         min_id=7,000,000,001, max_id=7,001,000,000

candidate69: CLI wall=13.049 s, Trino timeline=11.218 s
            primary_put_ms=4517.940, primary_put_wall_ms=5466.075
            correctness: count=1,000,000, distinct_id=1,000,000,
                         min_id=7,000,000,001, max_id=7,001,000,000
```

The daemon grouped metric reported `packed=true` for all five 200,000-entry
requests in both runs. Candidate69's five `primary_ms` values were
663.949, 615.971, 589.177, 622.049, and 654.809 ms; the direct route is
therefore active and correct. It did not establish a stable improvement over
the existing warm baseline (roughly 6--7 s for this workload); the observed
end-to-end throughput was only about 60--77 K rows/s. The candidate is rejected
as a production optimization for now. The isolated runtime was restored to the
baseline daemon/connector, with the packed logs and rollback artifacts kept on
cds20 for later profiling.

Additional evidence:

```text
/home/pixels/validation-million-update/runtime/e2e-fresh-20261122/
  pixels-home/logs/direct-packed-fixed-retina.log
  trino-data/var/log/server.log
/home/pixels/validation-million-update/runtime/
  connector-direct-packed-fixed-20261123/
  daemon-full-before-direct-packed-20261123.jar
```
