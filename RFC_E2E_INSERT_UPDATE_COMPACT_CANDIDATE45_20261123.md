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

## Remote artifacts

```text
logs/insert-candidate42-indexed-16writers-20261123.log
logs/insert-candidate43-indexed-32writers-20261123.log
logs/insert-candidate44-indexed-32writers-flush8-20261123.log
logs/insert-candidate45-indexed-32writers-flush4-grouped-20261123.log
logs/update-candidate45-after-insert-p16-20261123.log
logs/compact-candidate45-full-e2e-no-copy-20261123.log
logs/update-candidate45-after-compact-p16-20261123.log
```
