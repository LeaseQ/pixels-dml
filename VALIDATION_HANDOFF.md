# Pixels Million-Row UPDATE Validation Handoff

Last updated: 2026-11-18

This document is the single source of truth for validating million-row UPDATE
performance on a fresh machine. It covers both repositories involved:

- `LeaseQ/pixels-dml` (engine, daemon, index, CLI compaction)
- `LeaseQ/pixels-trino-dml` (Trino connector, UPDATE routing)

## 1. Goal

1. Raise practical UPDATE scale from the current ~100,000 row ceiling to
   1,000,000 rows per statement.
2. Confirm the optimization direction: an exact primary-key UPDATE must take
   the single batched Tag Index RPC path instead of the row-wise merge
   fallback.
3. Locate the real bottleneck with end-to-end measurements by splitting
   Trino routing, Tag Index gRPC, RocksDB writes, and Compaction into
   separate, independently timed stages.
4. Do not replace the first-stage architecture. If evidence shows an
   architecture change is required, pause and discuss it first.

## 2. Branches and Commits

Both repositories use the same branch name:

| Repository | Branch | Tip commit |
| --- | --- | --- |
| LeaseQ/pixels-dml | `codex/million-update-validation` | `f00915e5` |
| LeaseQ/pixels-trino-dml | `codex/million-update-validation` | `614a915` |

Baseline before this work:

- pixels-dml: `6d9de65f`
- pixels-trino-dml: `7ddb2ec`

## 3. The Two UPDATE Paths

Understanding which path a statement takes is the key to every result.

### 3.1 Exact path (target)

`PixelsMetadata.applyUpdate()` -> `PixelsUpdateExecutor.executeUpdate()` ->
one batched `appendTagIndexEntries` gRPC call -> IndexServer -> RocksDB Tag
Index.

For 1,000,000 rows this path must issue exactly one RPC.

### 3.2 Merge fallback (suspected current production path)

`beginMerge()` -> `PixelsMergeSink` processes rows page by page and flushes
Tag Index writes in batches of `MAX_PENDING_TAGS`.

A statement falls back here when the predicate cannot be pushed down into a
finite primary-key domain.

### 3.3 Compaction is separate

UPDATE only mutates the Tag Index. `COMPACT` is an explicit CLI action that
rewrites physical files and repairs the primary index. Compaction time must
never be included in UPDATE timing. Mixing the two is one of the prime
suspects behind the reported 100k ceiling.

## 4. Changes Included

### 4.1 pixels-trino-dml

- Exact UPDATE limit raised to 1,000,000 rows while keeping a single Tag
  Index request.
- Merge fallback `MAX_PENDING_TAGS` raised from 16,384 to 65,536.
- New instrumentation:
  - `[PIXELS_UPDATE_ROUTE]`
  - `[PIXELS_UPDATE_METRIC]`
  - `[PIXELS_UPDATE_MERGE_METRIC]`
- `connector/pom.xml`: protobuf dependency moved ahead of the fat Spike
  handler jar so the runtime protobuf implementation is not overridden.
- Million-row probe tests for request construction and MergeSink behavior.

Key file:
`connector/src/main/java/io/pixelsdb/pixels/trino/PixelsUpdateExecutor.java`

### 4.2 pixels-dml

- Compaction now builds primary keys for all rewritten rows, resolves them
  with batched `resolvePrimaryEntries`, and repairs the primary index with
  batched `putPrimaryIndexEntries`.
- Tag Index resolve shares one snapshot; each task keeps only the relocation
  map for its own source rows instead of copying the full map.
- Versioned batched Tag Index RPCs and RocksDB codec changes.
- New config:
  `index.server.max.inbound.message.size=67108864` (64 MiB, a 1M-row request
  is roughly 10 MiB).
- New instrumentation:
  - `[PIXELS_TAG_INDEX_METRIC]`
  - `[PIXELS_COMPACTION_RESOLVE_METRIC]`
  - `[PIXELS_COMPACTION_INDEX_METRIC]`
- Million-row RPC and RocksDB probe tests.

Key files:

- `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/PixelsTagCompactor.java`
- `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/CompactExecutor.java`
- `pixels-daemon/src/main/java/io/pixelsdb/pixels/daemon/index/IndexServiceImpl.java`
- `pixels-index/pixels-index-rocksdb/src/main/java/io/pixelsdb/pixels/index/rocksdb/PixelsTagIndex.java`

## 5. Evidence So Far

These are component-level probes, not real E2E results.

- Focused tests: CLI package 16/16, Trino connector 43/43, RocksDB Tag
  Index tests 9/9; `git diff --check` clean in both repos.
- 1M-row exact request:
  - request size ~10 MB
  - request construction ~84 ms
  - client-side single gRPC write ~147 ms
  - server decode ~14 ms
  - RocksDB storage ~61 ms
- Distinct-tag fallback (1M rows, 16 batches):
  - local MergeSink probe ~814-830 ms
  - real IndexServer/RocksDB probe ~2.35 s

Conclusion: a single million-row Tag Index write is not the bottleneck. The
100k ceiling is most likely caused by routing or measurement methodology.

### 5.1 Synthetic INSERT E2E evidence on cds20

The isolated runtime on `cds20` was then used to instrument the synthetic
UNNEST INSERT path. A successful 1M-row, single-task run showed:

- 100 page-sink writer flushes;
- about 0.3-0.6 s in `PixelsWriter.addRowBatch`;
- about 1.3-1.5 s building the primary-index batches;
- exactly 128 primary-index RPCs, one per vnode;
- about 109 s serial primary-index RPC wall time; and
- about 0.4 s in the final main-index flush.

This identifies the primary-index service/RocksDB path as the dominant
connector-side cost for this INSERT shape. A four-thread client fan-out was
also tested and regressed to about 160.5 s end-to-end; a sixteen-thread
fan-out regressed to about 145.8 s. Both are rejected and were not retained.
The current branch keeps serial publication and adds low-overhead counters in
the Trino sink. A warm repeat on the same table took 9.027 s for 1M rows:
the primary RPC wall interval was 5.018 s, while file writes were 0.146 s and
the main-index flush was 0.386 s. The daemon log attributes the cold penalty to
serial creation of vnode 0..127 column families under the global
`SinglePointIndexFactory` lock. The next experiment should therefore prewarm
those vnodes and then evaluate a server-side grouped primary-index write or a
RocksDB write-path optimization, not more client fan-out. A temporary
four-thread warm-table run reduced primary RPC wall time to 2.050 s but only
improved SQL wall time to 8.855 s; it was removed and the deployed connector
was restored to serial publication.

Known unrelated test issues:

- `TestRetinaTrace` requires a machine-specific path
  (`/home/gengdy/data/index/index.load.trace`).
- `TestRocksDBIndex.testPutEntries` assertion failure under current config.
- Two daemon probes pollute each other in one JVM via the static
  `RocksDBFactory` and a deleted `@TempDir`; each probe passes standalone.

## 6. Bottleneck Hypotheses (ranked)

1. The real SQL never reaches `executeUpdate()` because the predicate is not
   pushed down as a finite primary-key domain, so it runs the merge fallback.
2. Compaction time is counted inside UPDATE time.
3. The runtime loads stale connector/common/daemon jars, or the new 64 MiB
   gRPC setting is missing from the deployed `PIXELS_HOME/etc`.
4. Inherent serial/small-batch cost of the fallback path itself. This can
   only be fixed by getting statements back onto the exact path.

## 7. Validation Steps on the New Machine

### 7.1 Fetch code

```bash
git fetch origin codex/million-update-validation
git checkout codex/million-update-validation
# pixels-dml tip must be f00915e5
# pixels-trino-dml tip must be 819ec41
```

### 7.2 Build

```bash
# pixels-dml
mvn -T 3 clean install -DskipTests

# pixels-trino-dml
cd connector
mvn clean package -DskipTests
```

### 7.3 Deploy in isolation

- Use a dedicated `PIXELS_HOME` and run `./install.sh`; do not overwrite an
  existing deployment.
- Confirm `etc/pixels.properties` contains
  `index.server.max.inbound.message.size=67108864`.
- Verify the Trino plugin directory contains the freshly built connector jar
  and matching `pixels-common` (check timestamps/sizes; stale jars are a
  recurring failure mode).
- Restart Metadata service, IndexServer, and Trino.

### 7.4 Prepare data

- Create an isolated test table and load at least 1,000,000 rows.
- Keep primary-key index and Tag Index configuration identical to the
  production-like setup.

### 7.5 Run three controlled UPDATEs

Time each statement independently:

1. Exact UPDATE, 100,000 rows (push-down-able primary-key predicate).
2. Exact UPDATE, 1,000,000 rows (same predicate shape).
3. Non-exact UPDATE, 1,000,000 rows (deliberately triggers fallback, used as
   the control group).

### 7.6 Check routing in Trino logs after every statement

Success shape:

```text
[PIXELS_UPDATE_ROUTE] ... exact_eligible=true
[PIXELS_UPDATE_METRIC] ... rows=1000000 rpc_count=1
```

Fallback shape:

```text
begin_merge path=merge_fallback
[PIXELS_UPDATE_MERGE_METRIC]
```

Interpretation:

- Any `[PIXELS_UPDATE_MERGE_METRIC]` means the statement did not use the
  exact path.
- `rpc_count > 1` means batching did not take effect or the deployed jar is
  not the new build.

### 7.7 Compaction and visibility

- Run `COMPACT` separately; never include it in UPDATE timing.
- Record `[PIXELS_COMPACTION_RESOLVE_METRIC]` and
  `[PIXELS_COMPACTION_INDEX_METRIC]`.
- Use SELECT to verify final visibility and data correctness.

### 7.8 Decision matrix

| Observation | Conclusion | Next action |
| --- | --- | --- |
| Exact 1M UPDATE is fast but end-to-end is slow | Compaction dominates | Optimize compaction path |
| Exact never triggers | Planner/predicate push-down problem | Fix routing eligibility |
| Exact triggers, `rpc_count=1`, still slow | IndexServer or RocksDB bound | Split decode vs storage via `[PIXELS_TAG_INDEX_METRIC]` |
| Fallback 1M matches the old 100k symptom | Confirms wrong path was measured | Force exact predicate and remeasure |

## 8. What Is Still Missing

The exact UPDATE/COMPACT/restart evidence is recorded in
`RFC_MILLION_UPDATE_INSERT_DURABILITY.md`. The synthetic INSERT route now has
real E2E stage measurements on `10.77.110.36`, but it is still below the
throughput target because the 128 primary-index RPCs dominate. The remaining
work is to implement and benchmark a server-side optimization while retaining
the tagging-first correctness order, then repeat the full INSERT -> UPDATE ->
COMPACT -> restart acceptance flow on a clean isolated runtime.

The primary business-shaped workload is now the 68-column tagging benchmark in
`RFC_68COL_TAG_BENCHMARK.md`. It defines the 1M -> 10M -> 100M -> 1B scale
ladder, separates cold load from warm INSERT, and requires tag visibility and
restart checks after UPDATE. TPC-H is not part of this validation track.
