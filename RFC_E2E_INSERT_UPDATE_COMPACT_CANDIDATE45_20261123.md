# RFC: Fresh INSERT -> UPDATE -> COMPACT E2E Evidence (2026-11-23)

Status: validation evidence; amended with fixes and follow-up E2E results

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

## Follow-up: single-column primary-key encoding specialization (candidate70/71)

The INSERT front-end also had a small allocation candidate in
`PrimaryKeyBytes.fromColumnVectors`: for a one-column primary key it created a
`byte[][]` and entered the general concatenation path even though the encoded
part could be returned directly. The specialization was deliberately kept
format-neutral and retained the existing invalid-ordinal and NULL checks.
It passed the focused core test (`TestPrimaryKeyBytes`, 5/5) and the connector
writer test (`PixelsInsertIndexWriterTest`, 5/5).

An isolated-runtime A/B with the same daemon state did not support accepting
the change:

```text
candidate70, specialization: CLI wall=17.874 s, Trino timeline=16.009 s,
  primary_put_ms=6078.070, primary_put_wall_ms=6770.828
  correctness: count=1,000,000, distinct_id=1,000,000,
               min_id=7,000,000,001, max_id=7,001,000,000

candidate71, baseline connector control: CLI wall=14.191 s,
  Trino timeline=12.358 s, primary_put_ms=4969.905,
  primary_put_wall_ms=5869.441
  correctness: count=1,000,000, distinct_id=1,000,000,
               min_id=7,000,000,001, max_id=7,001,000,000
```

The candidate is rejected: it showed no improvement and was slower in the
paired run. This is not evidence that the encoding loop is universally
expensive; it shows that removing one small allocation is below the noise and
not the current end-to-end bottleneck. The code and remote source were restored
to baseline; the runtime is currently using the baseline connector.

## Follow-up: RocksDB primary-write stage split and WAL upper bound (candidate72/73)

To separate Java-side batch construction from the synchronous RocksDB write,
an isolated-only timing probe was added around `RocksDBIndex.putPrimaryEntriesInternal`.
It measured `WriteBatch` construction and `rocksDB.write()` separately for each
bucket, without changing the write format or ordering. The probe was enabled
only on the fresh E2E runtime and removed before restoring the runtime.

Candidate72 kept the normal durability setting (`index.rocksdb.disable.wal=false`):

```text
candidate72: 1M INSERT, CLI wall=14.123 s
correctness: count=1,000,000, distinct_id=1,000,000,
             min_id=4,000,000,001, max_id=4,001,000,000
RocksDB timing across 132 bucket batches:
  WriteBatch construction sum = 3,845.687 ms
  rocksDB.write() sum         = 18,318.536 ms
  construction share           = 17.35% of measured bucket time
```

Candidate73 disabled WAL only as a throughput upper-bound probe; it is not a
durability candidate and was never left enabled:

```text
candidate73: 1M INSERT, CLI wall=13.172 s
correctness: count=1,000,000, distinct_id=1,000,000,
             min_id=4,000,000,001, max_id=4,001,000,000
RocksDB timing across 132 bucket batches:
  WriteBatch construction sum = 3,452.882 ms
  rocksDB.write() sum         = 16,177.708 ms
  construction share           = 17.59% of measured bucket time
```

The WAL-off control improved end-to-end wall time by about 6.7% and reduced
the measured write sum by about 11.7%, confirming WAL/synchronous write cost as
one real factor. It does not explain the full gap to the target, and disabling
WAL violates the crash-recovery contract, so candidate73 is rejected. The
dominant next direction is RocksDB write scheduling/WAL batching or the upstream
request shape, not another small primary-key encoding specialization. The
isolated runtime was restored to WAL enabled, the baseline daemon jar, and no
timing property.

Remote evidence:

```text
/home/pixels/validation-million-update/runtime/logs/
  insert-candidate72-rocksdb-timing-20261123.log
  insert-candidate73-waloff-20261123.log
  daemon-write-timing-retina-20261123.log
  daemon-waloff-retina-20261123.log
```

## Follow-up: cross-bucket single WriteBatch (candidate74)

The stage split suggested one higher-leverage experiment: the grouped route
partitioned each 200,000-entry request into bucket lists and called
`rocksDB.write()` once per bucket. Candidate74 added an opt-in daemon flag that
assembled all bucket entries into one RocksDB `WriteBatch`, retaining the same
column families and key encoding. This was tested only on the isolated runtime.

```text
candidate74: 1M INSERT, CLI wall=14.367 s
correctness: count=1,000,000, distinct_id=1,000,000,
             min_id=4,000,000,001, max_id=4,001,000,000
grouped primary_ms by 200K request:
  1330.890, 779.782, 794.041, 822.479, 856.267 ms
```

The same-runtime baseline candidate72 was 14.123s, with primary request times
of 1018.233, 577.323, 586.225, 621.538, and 623.028ms. The single-batch route
was slower in every request despite reducing the number of RocksDB write calls;
the larger batch's construction and single-commit cost outweighed that saving.
Correctness passed, but the candidate is rejected and the runtime was restored
to the baseline daemon with the feature flag absent.

## Follow-up: bounded cross-bucket batch-size matrix (candidate75--79)

Because candidate74 used one batch for all buckets, the next probe tested a
bounded merge size. The opt-in flag merged 2, 4, or 8 vnode lists per
`WriteBatch`; WAL, key encoding, grouped writer count, and the SQL generator
were otherwise unchanged. Each run passed the exact 1M-row count/distinct/range
check:

```text
candidate75, 2 buckets/batch: 13.586 s
candidate76, 2 buckets/batch: 12.112 s
candidate77, 4 buckets/batch: 13.250 s
candidate78, 8 buckets/batch: 12.810 s
candidate79, baseline control: 12.855 s
```

The daemon primary metrics explain why the apparent candidate76 result is not
accepted. Its five primary times were 781.100, 718.953, 742.526, 788.498, and
785.756ms, while the paired baseline control was 1009.076, 642.998, 606.985,
606.276, and 653.514ms. Candidate78 was effectively tied with the baseline
end-to-end and had primary times of 1093.259, 678.972, 672.867, 654.083, and
680.988ms. There is no stable reduction in the primary stage across the matrix;
the 12.112s observation is runtime variance rather than a demonstrated batch
size win. The feature code was removed and the isolated runtime restored to the
baseline daemon without the flag.

## Follow-up: clean root-layout tag-aware E2E (candidate82/83)

The first compact attempts on candidates79--81 exposed a test-fixture error:
their `paths` property explicitly named generated `v-0-ordered` and
`v-0-compact` directories. The connector then derived another layout level,
so COMPACT could not find metadata for the original directory. These runs are
rejected as path-fixture failures, not engine-performance evidence. The
standard fixture is one table-root URI plus `pk='id'` and `pk_scheme='rocksdb'`;
the connector derives the ordered and compact layouts.

Candidate83 was created with that standard root layout and used the existing
1M-row source table. It completed the full tag-aware route:

```text
INSERT:  1,000,000 rows, CLI wall=15.546 s (64.3K rows/s)
UPDATE:  1,000,000 rows, CLI wall=3.854 s (259.5K rows/s)
COMPACT: CLI wall=45.365 s
```

The COMPACT log provides the missing stage evidence:

```text
tag snapshot:        1,000,000 rows, 62 RPC,  8.457 s
physical rewrite:    1 output,             14.899 s
  non-resolve worker time:                  7.026 s
primary relocation:  1,000,000 rows, 128 RPC
publish:             1 output,              8.503 s
```

Immediate post-COMPACT validation passed:

```text
count=1,000,000
tag_1_type='U83' AND tag_1_para=83.0000: 1,000,000
tag_2_type='AAA' AND tag_2_para=3.1400: 1,000,000
tag_1_type='AAA' AND tag_1_para=3.1400: 0
id range: 4,000,000,001..4,001,000,000
```

The evidence confirms that a standard root-layout table can materialize a
1M-row tag-aware update in one compact file. It does not establish a new
INSERT optimization; the INSERT number is below the best earlier fresh-run
range and remains workload/runtime sensitive.

### Reproduced post-COMPACT UPDATE visibility issue

On candidate82, after the first successful U82 COMPACT, a second exact 1M-row
UPDATE to U83 returned `UPDATE: 1,000,000 rows` in 3.987s. A following COMPACT
resolved 1M rows but reported zero physical outputs and the table still read
U82. This is recorded as a correctness/durability follow-up, not accepted as
a performance result. Before pursuing post-COMPACT throughput, isolate why a
second update is acknowledged but is neither materialized nor visible through
the normal read path.

Correction: the candidate83 runtime was configured with only
`dml.tag.columns=tag_1_type,tag_1_para`; `tag_2_type/tag_2_para` were not
tag-aware columns in that run. The earlier tag2 observation therefore cannot
be used as evidence that a configured post-COMPACT UPDATE failed. The genuine
candidate82 symptom was later traced to stale MainIndexBuffer locations after
compaction; see the addendum below.

Remote evidence:

```text
/home/pixels/validation-million-update/runtime/logs/
  compact-candidate83-first-20261123.log
  compact-candidate82-second-20261123.log
```

## Addendum: post-COMPACT relocation repair and full-table decimal fix (2026-11-23)

### Root cause and daemon fix

Direct index and physical-file probes on candidate83 showed that the tag index
contained the new U84 tag and COMPACT reported relocation of 1,000,000 rows,
but resolving an exact key still returned the retired source file id. The
cause was an old per-file entry in `MainIndexBuffer`: inserting the new
location did not remove the old row mapping, so lookup iteration could return
the stale file first. Commit `d5ccc1cb` in `pixels-dml` makes relocation
authoritative by evicting stale point/range cache entries and replacing the
row mapping across file buffers. It also adds a `MainIndexBuffer` regression
test and was pushed to the validation branch.

The isolated daemon was rebuilt and restarted. A repair COMPACT repopulated the
current compact-file mapping; resolving the first key then returned the new
file id rather than the retired source id. This is an in-process E2E proof of
the relocation fix. Persisted overlapping SQLite ranges after a daemon restart
remain a separate durability follow-up and are not overclaimed here.

### Full-table UPDATE route exposed a second correctness bug

The next full-table UPDATE intentionally exercised the ordinary no-WHERE
`ConnectorMergeSink` fallback, not the exact primary-key route:

```text
UPDATE ... SET tag_1_type='U85', tag_1_para=85.0000
UPDATE: 1,000,000 rows
wall: 33 s
route: merge_fallback; rows=1,000,000; rpc_count=1
```

The following COMPACT correctly resolved and relocated 1M rows, but the numeric
tag materialized as `850000.0000`. The cause was in `PixelsMergeSink`: Trino
Decimal blocks store the unscaled integer (850000), and the fallback encoded
that integer as SQL text before parsing it again, effectively applying the
scale twice. Commit `34199d5` in `pixels-trino-dml` converts short and long
Decimal blocks back to scale-aware SQL text and adds a regression test for
`decimal(18,4)`.

### Corrected full-table E2E after both fixes

The connector jar containing `34199d5` was deployed only to the isolated
Trino runtime. A second full-table UPDATE and tag-aware COMPACT were then run
on the same 1M-row candidate83 table:

```text
UPDATE U86:       1,000,000 rows, wall=33 s
pre-COMPACT read: U85 / 850000.0000 (the previous materialized state)
COMPACT wall:     82 s
tag snapshot:     2 entries, 1,000,000 rows, 62 RPC, 23.165 s
physical rewrite: 1 output, worker wall=28.723 s
primary resolve:  1,000,000 rows, 62 RPC, 21.642 s
relocation:       1,000,000 rows, 128 RPC
publish:          9.559 s
```

Final SQL verification passed exactly:

```text
count(*) = 1,000,000
tag_1_type='U86' = 1,000,000
tag_1_para=86.0000 = 1,000,000
tag_1_para=860000.0000 = 0
tag_1_type='U85' = 0
tag_2_type='AAA' = 1,000,000
min(tag_1_para) = max(tag_1_para) = 86.0000
```

An independent direct Pixels-file read of the newest compact file returned
`tag_1_type=U86`, `tag_1_para=86.0000`, and `tag_2_type=AAA`, proving the
result is physically materialized rather than a Trino metadata/cache artifact.

### Runtime notes and interpretation

After the daemon restart, Retina recovered its checkpoint in `RECOVERING` and
required the existing single-node `get status -> markReady(recoveryEpoch)`
handshake before transaction port 19989 opened. The isolated Trino process
also had to be restarted with its intended 8191 HTTP port and Java 23 module
opens. These are runtime lifecycle requirements, not UPDATE failures.

This corrected run establishes a stable correctness path for a 1M-row
full-table UPDATE followed by COMPACT. It does not yet establish target-level
throughput: the measured UPDATE rate is about 30K rows/s for this merge
fallback, while COMPACT is dominated by ~21.6s primary resolution plus physical
rewrite/publish. The exact primary-key UPDATE route and the full-table merge
fallback must remain separate benchmark rows.

## Addendum: exact UPDATE resolver and shared tag-write candidates (2026-11-23)

The next exploration kept the exact primary-key route and the same 1M-row
range (`4000000001..4001000000`) fixed. The Trino runtime used
`pixels.update.primary-key-resolve-batch-size=16384` and
`pixels.update.primary-key-resolve-parallelism=32`. Every successful probe
updated two configured tag columns and returned exactly 1,000,000 rows.

### Candidate changes

The daemon resolver replaced the temporary `HashMap<IndexKey, Long>` used to
re-associate resolved row IDs with request keys by a request-position array
plus per-bucket positions. This preserves input order, missing-key behavior,
and the wire protocol while removing protobuf-key hashing and Long boxing from
the million-key response loop.

The shared tag-write path then received two local optimizations:

1. Decode the shared primary-key vector once and reuse the immutable list for
   both changed tag columns.
2. In `PixelsTagIndex.appendBatch`, reuse the incoming list when the previous
   value is empty and the request has already proved the primary keys unique;
   repeated versioned keys still take the old combine path.

### Component evidence

The daemon storage probe changed as follows on the same runtime:

```text
array resolver only:
  locationResponseMs: mostly 4..30 (previous warm runs: commonly 100..140)
shared-list candidate:
  group_ms: 0.03..0.32 ms (previous: about 130..140 ms)
  merge_ms: 151.6..341.2 ms (previous: about 340..535 ms)
  rocks_write_ms: about 49..51 ms
```

The targeted RocksDB tag-index suite passed after the changes:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### E2E decision

The exact-route E2E probes were successful and correct, but did not produce a
stable target-level throughput increase. Warm internal totals included:

```text
U103  total=2.596s  primary_resolve=0.947s
U104  total=1.809s  primary_resolve=0.724s
U105  total=1.733s  primary_resolve=0.830s
U107  total=2.161s  primary_resolve=0.830s
U109  total=1.756s  primary_resolve=0.897s
```

The best stable-looking warm result remains in the same roughly 0.55--0.65M
rows/s internal band, below the million-row/s objective. RPC and primary-key
resolution dominate the remaining variance after the tag-write copies are
removed. These changes are therefore retained as an experimental candidate
and evidence, not presented as the target-throughput breakthrough; further
blind batch tuning is paused pending a direction decision or a new resolver/
RocksDB design hypothesis.

## Addendum: consecutive post-COMPACT exact-route validation (2026-11-23)

The remote branch was fast-forwarded to the documentation revisions
`4d9f98f9`, `00a91827`, and `b2326014`. Those revisions move correctness,
full-cycle accounting, and direct partial-update baselines ahead of further
local tuning. The following runtime evidence was collected after that
fast-forward and is recorded separately from the historical numbers above.

The isolated candidate83 table was already in a compacted state. Two fresh
full-table exact-route updates were executed consecutively, each followed by
tag-aware COMPACT and a compact-only query (`ordered_path_enabled=false`,
`compact_path_enabled=true`):

```text
U110 UPDATE: 1,000,000 rows, wall=5.001s
U110 COMPACT: 1 output, total wall about 46s
  tag snapshot: 2 entries, 1,000,000 rows, 62 RPC, resolve_ms=29.332s
  physical rewrite: 37.018s
  publish: 9.836s
  compact-only result: U110 / 110.0000 / 1,000,000

U111 UPDATE: 1,000,000 rows, wall=3.933s
U111 COMPACT: 1 output, total wall about 45s
  tag snapshot: 2 entries, 1,000,000 rows, 62 RPC, resolve_ms=30.250s
  physical rewrite: 35.305s
  publish: 9.905s
  compact-only result: U111 / 111.0000 / 1,000,000
```

The row-level compaction metrics had non-zero update time in both runs
(`667.9ms` for U110 and `415.5ms` for U111), and the compact-only query saw
the newest values. This is evidence that the current relocation repair plus
the current tag-aware compactor supports consecutive post-COMPACT exact-route
cycles on the isolated runtime.

An earlier U109 run in the same long-lived exploratory table produced a
compact file that still read U86. Post-run metadata inspection showed the
old input file as retired (`fileId=46807`) and the newly published file as
regular (`fileId=54105`). Because this comparison was made after relocation
and the run mixed interrupted/old CLI experiments, it is retained as a
reproduction requiring a clean isolated cycle, not classified as a current
code regression. U110 and U111 are the clean consecutive evidence; they do
not erase the need for a future fresh-table two-cycle test.

This correctness evidence does not change the throughput conclusion. The
UPDATE SQL wall time is roughly 0.20--0.25M rows/s at the client level for
these runs, while COMPACT remains roughly 45--46s and is dominated by
primary-key resolution plus physical rewrite/publish. The earlier warm
internal exact-route measurements remain the appropriate throughput evidence;
these U110/U111 runs primarily validate repeated materialization and recovery
state, not a million-rows/s breakthrough.

## Addendum: fresh table-root two-cycle validation and COMPACT directory fix (2026-11-23)

To remove the remaining long-lived-table ambiguity, a new empty table was
created on the isolated runtime:

```text
validationfresh.e2e68_fresh_candidate84_20261123
68 columns, 1,000,000 rows, id=4000000001..4001000000
paths = one table-root URI
pk = id
pk_scheme = rocksdb
```

The first COMPACT attempt found a root-layout compatibility bug: local storage
returned a table-root/directory status, but `CompactExecutor` called
`MetadataService.getFileType` before filtering it. The resulting error was
`path metadata not found for directory URI .../e2e68_fresh_candidate84_20261123`.
The fix filters directory and non-Pixels-file statuses before the metadata
lookup. It preserves explicit ordered/compact layout behavior and makes the
documented single-root layout usable by tag-aware COMPACT.

The fixed CLI was rebuilt on cds20 and the complete flow was rerun:

```text
INSERT INTO candidate84 SELECT * FROM candidate83:
  client wall=20.132s, 49.7K rows/s
  index metric: rows=1,000,000, primary_put_wall_ms=6251.138,
    primary_rpc_count=5

UPDATE U112:
  exact MPP route, server total_ms=1730.534,
  primary_key_resolve_ms=790.238, about 0.58M rows/s
COMPACT U112:
  physical_rewrite=20.378s, resolve_ms=12.950s,
  publish_ms=9.515s, one output, total CLI about 29s
compact-only readback: U112 / 112.0000 / 1,000,000

UPDATE U113 after the first COMPACT:
  exact MPP route, server total_ms=1487.972,
  primary_key_resolve_ms=645.212, about 0.67M rows/s
COMPACT U113:
  physical_rewrite=19.475s, resolve_ms=12.826s,
  publish_ms=9.772s, one output, total CLI about 29s
compact-only readback: U113 / 113.0000 / 1,000,000
```

This is the cleanest current correctness evidence: a fresh primary-indexed
table, table-to-table INSERT, exact tag-aware UPDATE, materialization, a
second post-COMPACT UPDATE, a second materialization, and compact-only
readback. It does not meet the throughput objective. The current UPDATE
resolver is batched at the RPC boundary, but the daemon still performs one
RocksDB iterator seek per key inside each bucket. COMPACT's 12.8--13.0s
primary-key/tag-resolution stage remains the next high-value optimization
target; any resolver change must preserve the two-cycle evidence above.

## Addendum: sequential RocksDB primary-batch candidate rejected (2026-11-23/24)

The resolver hypothesis was that a compaction batch arriving in physical
primary-key order could reuse one RocksDB iterator and advance with `next()`
instead of issuing one `seek()` per key. The targeted RocksDB regression suite
passed, but the isolated E2E test rejected the optimization on both correctness
and performance grounds.

First deployment, using the existing `prefixSameAsStart=true` read option,
returned only 7,935 updated rows for a 1,000,000-row exact-range UPDATE
(U114/U116), with daemon metrics showing many `keys=16384, resolved=128`
batches. This was a correctness failure. The runtime was immediately restored
to the stable daemon jar and a stable U117 UPDATE again affected exactly
1,000,000 rows; its primary-key resolve time was 3,308.748 ms and total server
time was 4,309.859 ms.

A second, narrower candidate disabled prefix iteration and enabled RocksDB
total-order seek. It did resolve all 1,000,000 keys (U118), but the primary-key
resolve time increased to 7,162.933 ms and total server time to 8,122.219 ms.
The result is therefore a correct but materially slower implementation, not a
throughput improvement. The class-only runtime patch was rolled back to the
stable daemon backup; no candidate code remains in the source branch.

The lesson is that a request batch being bytewise sorted is insufficient to
justify a shared iterator: the configured prefix/read-mode semantics and the
actual RocksDB key stream must both be part of the proof. Future resolver work
should first add explicit hit/miss and ordering instrumentation, then compare
against the existing per-key seek path on the same exact batch before changing
the default path.

## Addendum: Trino primary-key resolver parallelism ablation (2026-11-24)

After reverting the unsafe RocksDB iterator candidate, the stable daemon path
was measured with the same fresh table, exact ID range, shared tag UPDATE, and
1,000,000-row result. The Trino resolver batch size and parallelism were
changed only through JVM properties; every run returned all 1,000,000 rows.

| Batch size | Parallelism | Run | Resolve ms | Total server ms | Approx. total rows/s |
|---:|---:|---|---:|---:|---:|
| 16,384 | 32 | U119 | 3,444.988 | 4,478.424 | 0.223M |
| 16,384 | 64 | U120 | 1,148.830 | 1,979.625 | 0.505M |
| 16,384 | 128 | U121 | 1,236.576 | 1,896.186 | 0.527M |
| 32,768 | 64 | U122 | 1,257.943 | 1,980.829 | 0.505M |

The 64-way setting is the practical choice for this machine: it cuts resolver
time by about 67% versus 32-way and reaches the lower end of the project's
throughput target while preserving exact million-row correctness. The 128-way
run did not improve resolver time and its small total-time difference is not
large enough to treat as a stable gain. Doubling the batch size also did not
help. The isolated Trino runtime was left at batch size 16,384 and parallelism
64.

This is a configuration-level throughput result, not yet a source-default
change. It should be repeated on the intended deployment shape and checked
against concurrent workload interference before making 64 the packaged
default. COMPACT remains a separate cost center; the earlier fresh two-cycle
materialization evidence remains the correctness baseline.

The best configuration was then carried through COMPACT on the same table
after U122. The final materialization recorded 62 tag-index resolve RPCs and
`resolve_ms=24.697s`, `physical_rewrite=31.996s`, and `publish_ms=7.055s`
(CLI wall about 39s). A compact-only query returned:

```text
U122 / 122.0000 / 1,000,000
```

This closes the current isolated E2E loop: table-to-table INSERT evidence,
exact million-row UPDATE at the best resolver setting, tag-aware COMPACT, and
compact-only readback of the newest tag value.
