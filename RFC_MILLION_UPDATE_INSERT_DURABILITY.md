# RFC: Million-Row UPDATE/INSERT Validation and INSERT Durability

Status: validation branch, active

## Objective and non-negotiable order

The original goal is unchanged: **support tagging first, then optimize**.
Tag Index marking, primary-index mapping, visibility publication, COMPACT, and
restart recovery must remain correct before throughput claims are accepted.

The throughput target is at least several hundred thousand rows/s for the
practical UPDATE and INSERT routes, with a stretch target near one million
rows/s. Results below are end-to-end SQL wall-clock rates unless explicitly
labelled connector-internal.

The work was run only in the isolated `cds20` validation runtime:

- Trino: `127.0.0.1:8091`
- Metadata: `127.0.0.1:18988`
- Transaction: `127.0.0.1:18989`
- Retina: `cds20:18990`
- Runtime root: `/home/pixels/validation-million-update/runtime`

The pre-existing deployment on Trino 8090 and `/home/pixels/opt/leaseq-dml-env`
was not modified.

## Historical premise and corrections

The relevant optimization history consistently assumes tagging support as a
correctness prerequisite: constant/update handling (`def1a71`), applying tag
updates during COMPACT (`46eff83e`), routing DML tags through IndexServer
(`5b81b53`), exact-primary-key routing (`b2b3bba`), batching (`4bc89bb`), and
assignment serialization (`95fd72f`). Those changes are useful evidence, not
axioms; this RFC corrects two implementation assumptions found during E2E:

1. A completed INSERT cannot be treated as durable merely because the physical
   file exists. The metadata row must be `REGULAR`, and its row-id hull must be
   persisted before visibility is published.
2. A checkpoint body is not safe merely because the etcd pointer exists. The
   body path must remain valid across a restart, including a same-timestamp
   republish after a process restart.

Related standard design patterns consulted during this work include Hudi's
index/write separation, Iceberg row-level deletion files, Paimon's primary-key
table compaction, and Delta Lake deletion vectors. They all make the same
trade-off visible: the fast write path needs a durable row/key mapping, while
garbage collection and compaction can be asynchronous.

## Baseline and current evidence on cds20

| Route | 1M-row result | Rate | Status |
|---|---:|---:|---|
| Exact-key UPDATE | 2.7-3.7 s SQL wall | 271k-362k rows/s | Meets minimum target |
| Table-to-table INSERT | about 2.44 s | about 410k rows/s | Historical claim; not reproduced |
| Synthetic `UNNEST` INSERT, concurrency 1 | 33.5 s on clean run | about 29.8k rows/s | Below target |
| Synthetic `UNNEST` INSERT, concurrency 4 | 77.830 s on validation8 | 12,848 rows/s | Correct but below target |
| COMPACT | about 48-50.5 s | not the write target | Correctness gate |

The two INSERT rates are not interchangeable. Table-to-table INSERT reaches
the lower target; synthetic UNNEST is dominated by Trino row generation,
fragmentation, and concurrent index-flush behaviour. The default synthetic
concurrency also reproduced `INDEX_FLUSH_MAIN_INDEX_FAIL` (error 10614), while
the explicit concurrency-4 run completed successfully.

The retained instrumented logs do not contain a matching 2.44 s INSERT
measurement. They do contain a serial warm 1M INSERT with
`primary_put_wall_ms=5017.614` ms and a four-thread experiment with
`primary_put_wall_ms=2050.439` ms; the latter still had a summed
`primary_put_ms=7794.409` ms and did not produce a 2.44 s end-to-end result.
Until the exact SQL, schema, index mode, and complete wall-clock log for the
2.44 s claim are recovered, that number is historical evidence rather than a
reproducible acceptance baseline.

The successful exact-key UPDATE route is confirmed in route logs as
`executor=MPP exact_eligible=true`, with one primary-key domain and one RPC.
The connector-side UPDATE request was roughly 10 MB and spent about 1.3-1.6 s
in the RPC path; this is an internal metric, not the end-to-end claim.

### Synthetic INSERT bottleneck isolation

The initial synthetic INSERT result was instrumented on a clean, single-task
run rather than inferred from the SQL wall time. For a 1M-row INSERT the page
sink reported 100 writer flushes, about 0.3-0.6 s in `PixelsWriter.addRowBatch`,
and about 1.3-1.5 s building the primary-index batches. The index writer then
made exactly 128 primary-index RPCs, one per vnode. The serial primary-index
publication measured about 109 s of wall time; the main-index flush was only
about 0.4 s. This makes the primary-index service, not Pixels file encoding,
the dominant connector-side cost for this cold-table route.

The cold cost was then separated from the warm write path. Reusing a table
whose 128 vnode indexes had already been opened, a second 1M-row INSERT took
9.027 s end to end (about 111k rows/s); the sink measured 5.018 s for primary
RPC wall time, 1.015 s for key/batch construction, 0.146 s for file writes,
and 0.386 s for the main-index flush. The daemon log shows the first-use
serialization explicitly: `SinglePointIndexFactory` creates vnode 0 through
127 under its global lock, and each RocksDB column-family creation takes about
1.2 s. The earlier 109 s is therefore primarily cold vnode/column-family
initialization, not the steady-state cost of writing 1M primary entries.

This distinction changes the optimization order: pre-create or asynchronously
warm the vnode indexes before the first INSERT, then optimize the remaining
warm primary-index write path. Reducing vnode count or changing the storage
layout is not being applied as an unreviewed production change.

Two exploratory client-side concurrency changes were deliberately rejected:
publishing the 128 vnode RPCs with a four-thread pool took about 154.2 s in the
primary RPC wall interval and 160.5 s end-to-end; a sixteen-thread pool took
about 139.7 s in the primary RPC wall interval and 145.8 s end-to-end. The
sum of per-RPC times also grew substantially under concurrency. The result is
consistent with contention/queueing in the single IndexServer/RocksDB writer;
client fan-out is not a valid optimization for this deployment and is not
part of the pushed implementation.

For completeness, a temporary four-thread experiment on the already-warm
table reduced the primary RPC wall interval from 5.018 s to 2.050 s, but the
SQL wall time moved only from 9.027 s to 8.855 s. Because the cold-table
experiments regressed badly and the warm end-to-end gain was marginal, the
parallel implementation was removed after measurement. The pushed connector
remains serial and deterministic.

JFR samples still show visible Trino cost in UNNEST projection/page merge and
HTTP page transport. The connector counters establish that cold vnode
initialization dominates the first INSERT, while the warm run still spends
about half its time in primary-index RPCs. The next optimization should
therefore be server-side: prewarm vnode indexes and then reduce vnode
write/flush overhead or introduce a carefully designed grouped primary-index
write, while preserving the tagging and crash-safe publication order. A
clean-runtime repeat is required before claiming any new throughput number.

The isolated validation harness also hit Java heap exhaustion after repeatedly
creating test tables and writer buffers. Raising the isolated Retina heap to
32 GiB restored service and recovery. This is recorded as harness hygiene and
not as a correctness or throughput result.

## Changes in this RFC

### INSERT durability and row-id hull

The Trino fragment is versioned to `pixels-insert-v2` and carries
`minRowId`/`maxRowId`. `PixelsInsertIndexWriter` records the allocated range;
`PixelsMetadata.finishInsert` persists it together with the file's `REGULAR`
state. Legacy v1 fragments remain readable with a conservative `0..0` hull.

### Java 11 compatibility

The isolated Retina runtime uses Java 11. Test and tag-index sources that used
`Stream.toList()` were converted to `Collectors.toList()`. The previous
runtime failure was a `NoSuchMethodError` before physical COMPACT began.

### COMPACT row-id hull and metadata finalization

Compaction now derives the compact file hull from the source/relocated row IDs.
`PixelsTagCompactor` closes the writer before reading `getNumRowGroup()`;
otherwise the final row group is not finalized and metadata publishes
`numRowGroup=0`.

Validation8 metadata after COMPACT:

```text
fileId=1239, type=REGULAR, numRowGroup=1, minRowId=0, maxRowId=999999
path=validation8/.../v-0-compact/
```

The COMPACT stage metrics recorded approximately 62 tag-index resolve RPCs in
12.855 s, 128 relocation RPCs for 1M rows, about 14.77 s physical rewrite, and
about 9.1 s publication. The next optimization target remains avoiding the
duplicate Tag Index and primary-index resolution work.

### Recovery checkpoint same-path rewrite

`RecoveryCheckpoint.generate()` writes a body whose filename includes the
applied timestamp, then CAS-publishes the pointer and deletes the displaced
body. After a process restart, the in-memory last-applied timestamp resets, so
republishing the same timestamp can legitimately produce the same body path.
The old cleanup deleted that path after publishing it, leaving an etcd pointer
to a missing file. Cleanup now skips deletion when `displacedPath == bodyPath`.
A regression test covers this exact case.

## Full E2E evidence

The validation8 table was inserted, updated so all 1M rows carried tag value
9, compacted, and then recovered through the isolated Retina checkpoint path.
The final post-restart query returned:

```text
count=1000000, min(id)=1, max(id)=1000000, count_if(tag_1_type=9)=1000000
```

The recovery sequence intentionally exercised both failure diagnosis and the
fixed path. The first restart exposed the old same-path cleanup bug and left a
dangling pointer. After the pointer was repaired with a valid native bitmap
fixture, the new daemon recovered the compact file. A second run then
republished the same timestamp to the same body path, confirmed the body still
existed, restarted Retina again, and returned the same 1M/1M/1M/1M result.

Recovery logs on both successful restarts reported:

```text
Recovery complete: ... baselineFiles=1, retired=0, protected=0, warns=0
```

The native visibility bitmap was probed rather than guessed: for a 1,000,000
row group the runtime requires 15,628 `long` words. A simple
`(recordNum + 63) / 64` allocation is therefore invalid for this Retina tile
configuration.

The recovered service remains `RECOVERING` until its normal replay/ready
handshake completes. On this isolated single-node validation setup, the
handshake was completed with `RetinaService.markReady(recoveryEpoch)` before
the Trino readback. This is a lifecycle gap to automate separately; it is not
used to claim that recovery can skip replay in production.

## Verification protocol

1. Build the Trino connector and daemon on cds20 with the runtime's Java
   versions.
2. Run fragment, page-sink, row-id tracking, and checkpoint unit tests.
3. On an isolated validation table, run INSERT, count/min/max, exact-key
   UPDATE, COMPACT, and tag verification.
4. Inspect route logs and stage metrics; separate SQL wall rate from internal
   connector rate.
5. Restart only the isolated Retina, complete the replay/ready handshake, and
   repeat count/min/max/tag verification.
6. Republish a checkpoint at the same applied timestamp and repeat the
   restart test.
7. Run JFR or equivalent CPU/IO profiling around successful COMPACT and the
   synthetic INSERT path before changing the next major design direction.

## Tests and artifacts

- `TestRecoveryCheckpoint`: 30 tests passed on cds20/Java 11.
- Java 11 test-source compatibility fixes are included in the validation
  branch.
- Isolated daemon full-jar SHA256:
  `1db22f61f8f6128b0f96c7460619d33d7cdd7c378dcad18032dac2c0def35338`.
- Detailed commit bodies and this RFC are required for future pushes.

## Follow-up exploration on 2026-11-20

The warm exact UPDATE path was further optimized with a guarded
distinct-column batch path.  A million-row update of one tag pair reached
`1.607 s` connector metric time, approximately `622,400 rows/s`, with one
IndexServer RPC.  The same path retains the old latest-timestamp fallback when
the request repeats a column name.  The lower several-hundred-thousand target
is met for warm exact UPDATE; the stretch target near one million rows/s is not
met yet.  Cold TagIndex handle initialization remains a separate one-time cost.

The next compaction probe found a protocol ceiling: listing all TagIndex
entries in one unary response produced about 100 MB and failed at the default
64 MiB gRPC limit.  The isolated runtime could read it after raising the
inbound limit to 256 MiB, but the durable fix is server streaming or paging.
Commit `3c3f8f99` adds a compatibility-preserving streaming RPC with bounded
primary-key chunks; commit `55aa2028` adds automatic fallback to the old unary
RPC during rolling upgrades.  These source changes are pushed but are not yet
deployed into the isolated runtime, so they are not counted as accepted E2E
performance evidence.

The same measurements show a second protocol opportunity: the two-column
million-row UPDATE request was `20,000,065` bytes because the primary-key list
is repeated for each changed column.  A shared-primary-key batch is a promising
follow-up for reducing both RPC bytes and server-side decoding/allocation.
The connector branch now contains a width probe (`pixels-trino-dml` commit
`8c170da`) that measures 1/2/4/8 assignment requests before changing the wire
contract; its output is intentionally not treated as a result until it runs in
the same Java/Protobuf environment as the E2E connector.

That same Java 11/Protobuf runtime micro-probe has now quantified the wire
growth for one million 8-byte primary keys:

```text
assignments=1  request_bytes=10000018
assignments=2  request_bytes=20000032
assignments=4  request_bytes=40000060
assignments=8  request_bytes=80000116
```

The growth is effectively one full primary-key vector per assignment.  Eight
columns already exceed the default 64 MiB gRPC limit, so the shared-key design
is not merely a micro-optimization; it is also required for wider exact UPDATE
statements to remain representable without raising the global message limit.

### Shared-primary-key RPC follow-up

The next implementation keeps `AppendTagIndexEntries` unchanged and adds
`AppendSharedTagIndexEntries`.  One shared update carries the primary-key
vector once plus multiple `(columnName, tag)` values.  The IndexServer expands
that representation into the existing `PixelsTagIndex.VersionedUpdate` list,
so tag ordering, timestamp semantics, compaction input, and old clients stay
unchanged.  Only the exact UPDATE connector path uses the new RPC; MERGE and
rolling-upgrade fallback paths retain the original RPC.

The connector probe now reports both encodings.  With one million 8-byte keys,
the expected shared representation is approximately constant in the number of
assignments while the old representation grows linearly.  The implementation
is source-complete in the validation branches.  The deployment and direct
IndexServer correctness evidence are recorded below; SQL table-level UPDATE
throughput remains a separate acceptance item.

### cds20 shared-RPC deployment evidence

The shared-RPC source was then built and deployed into the isolated
`validation-million-update` runtime on cds20.  The normal reactor build was
blocked only by the host's missing `cmake` executable while entering the
unrelated `pixels-retina` native module; compiling/installing `pixels-common`
and compiling/packaging `pixels-daemon` independently succeeded.  The Trino
connector built with the host's Java 23 toolchain, and the selected 12 tests
(including the million-row request probe and executor tests) passed.

A direct gRPC probe against the newly deployed IndexServer then appended one
million 8-byte primary keys with two shared tag values:

```text
[SHARED_RPC_E2E] rows=1000000 request_bytes=10000064 rpc_ms=22412.056 response_code=0
[SHARED_RPC_PREFIX_VERIFY] type_code=0 type_keys=1000000 para_code=0 para_keys=1000000
```

This is accepted as protocol/server E2E evidence: connector-side encoding,
gRPC transport, IndexServer decode, RocksDB write, and tag-key readback all
worked with the shared representation.  The SQL table-level UPDATE benchmark
is still pending on this restarted runtime because the pre-existing validation
tables currently have empty regular-file visibility after recovery; that state
must be repaired or a fresh SQL table created before reporting end-to-end
Trino UPDATE throughput for this commit.

### Exact shared-key uniqueness proof optimization

The next probe isolated a large part of the remaining warm multi-column cost.
The old eight-column shared request, using one million 8-byte keys, took about
`9.4 s` on the client and reported `storageMs=9412.7` on the server.  Reusing a
single validated key vector reduced storage to about `1.19 s`, but still spent
about `2.4 s` validating uniqueness.  The final implementation carries an
optional `primaryKeysAreUnique` proof on the shared RPC.  The exact primary-key
UPDATE connector path sets it; older callers leave it unset and keep the
server-side validation path.

The proof route was measured on the isolated cds20 runtime after a warm-up:

```text
[SHARED_RPC_8COL_PROOF] rows=1000000 request_bytes=10000220 rpc_ms=2247.961263 response_code=0
decodeMs=320.773282 storageMs=1125.255674 serviceMs=1497.741148
```

That is approximately `445k rows/s` by client RPC wall time and `668k rows/s`
by server service time.  Relative to the comparable eight-column warm baseline,
the measured client path is about four times faster.  This meets the lower
several-hundred-thousand target for the warm shared protocol path, but does not
claim the stretch goal of one million rows/s.  Duplicate-key or legacy callers
still use the compatibility validation path.

The implementation is recorded by pixels-dml commits `9046a45`, `9440ec45`,
`2339bd16`, and `2f97bb32`, with the connector proof field in pixels-trino-dml
commit `85de23c`.  The connector plugin was loaded by the isolated Trino on
port `8091`; a SQL table-level throughput result is still not accepted because
the recovered pre-existing tables currently expose zero rows.  The next
validation must repair that visibility or create a fresh SQL source/target and
then run the exact UPDATE through Trino, followed by correctness and restart
checks.

### Real 68-column SQL E2E on cds20 (2026-11-20)

The supplied data-set specification was converted into a clean 68-column table
with one million rows: `id` is the unique sequential primary key, the first
eight columns are populated, and the 60 tag columns start as NULL.  The source
table is `pixels.e2e68_main_20261121_b`; the target is
`pixels.e2e68_target_20261121`.  The target DDL stores all `tag_N_type`
columns followed by all `tag_N_para` columns, so table-to-table INSERT tests
must use that target order explicitly.

The first cold LOAD/INSERT attempt initialized 128 primary-index vnodes
serially and exceeded the transaction window (errors 10203/10213).  After the
vnode warm-up, ten 100K LOAD chunks completed in 6.46--7.85 seconds each, with
one retry of the first chunk completing in 10.42 seconds.  The source table
then verified as one million rows with `id` 1--1,000,000.

The real SQL UPDATE chain was successful:

```text
UPDATE ... SET tag_1_type='AAA', tag_1_para=3.1400 WHERE id BETWEEN 1 AND 1000000
SQL wall: 6.387 s
[PIXELS_UPDATE_METRIC] rows=1000000 mode=shared metadata_ms=0.000
  encoding_ms=66.857 request_build_ms=247.193 rpc_count=1
  rpc_ms=3900.024 total_ms=4216.905
```

The connector-side UPDATE route is therefore about 237K rows/s and the full
SQL wall rate is about 157K rows/s.  COMPACT then resolved 1,000,000 tagged
rows, published the rewritten file, and the tag predicate returned exactly
1,000,000 rows with `tag_1_para=3.1400`; the same result remained after an
isolated Trino restart.

For table-to-table INSERT, the accepted warm one-million-row baseline was
19.035 seconds, or about 52.5K rows/s SQL wall.  An opt-in bounded parallel
flush of independent vnode batches (`-Dpixels.insert.index.flush.parallelism=4`)
reduced the primary-index wall segment from 7.310 seconds to 2.985--3.658
seconds and reduced SQL wall to 15.8--16.3 seconds, about 61--63K rows/s.
Parallelism 8 was slightly worse (16.083 seconds), so 4 is the current
experimental setting; it does not yet meet the INSERT target.

A ten-million-row INSERT was initially reported as 143.602 seconds, but that
measurement was rejected: the physical file and primary index contained all
10,000,000 rows while the ordered scan hard-coded every file split to one row
group and returned only 5,060,000 rows.  The root cause was fixed in
`PixelsSplitManager`: ordered splits now read each file's actual metadata
`numRowGroup`, cached per query.  A protected ten-million-row rerun completed
in 158.198 seconds, and after the split fix the same range returned exactly
10,000,000 distinct rows (`20,000,001`--`30,000,000`).  This is about 63K
rows/s SQL wall, not hundreds of thousands.

The INSERT experiment therefore changes the conclusion: UPDATE is already in
the lower hundreds-of-thousands range on the exact shared-key SQL path, while
INSERT remains in the tens-of-thousands range for one-million-row SQL wall
tests and about 63K rows/s for the corrected ten-million-row run.  The next
INSERT work should target writer/task parallelism and the SQLite main-index
flush path; simply increasing vnode RPC concurrency is not sufficient.

### Grouped primary-index experiment (2026-11-21, not yet accepted)

The server metrics identify the dominant cost as the aggregate SinglePoint
RocksDB work: an eight-writer 1M-row run spent about 89.4 seconds in the sum of
the 1,024 vnode RPCs, while the MainIndex stage was about 8.5 seconds.  A
server-side experiment was therefore added behind two opt-in JVM properties:

```text
-Dpixels.insert.index.grouped.primary.write=true
-Dpixels.index.grouped.primary.write.threads=32
```

The connector sends all pending vnode entries for one file in one grouped RPC;
the daemon repartitions those entries by bucket, writes independent RocksDB
buckets in a bounded 32-thread pool, and writes the MainIndex batch once.  The
default behavior remains unchanged.  Because the daemon's default inbound
Index RPC limit is 64 MiB, the connector now also caps an opt-in grouped RPC at
200,000 entries by default, configurable with
`pixels.insert.index.grouped.primary.write.max.entries`; this keeps larger
10M/writer tests from converting a performance optimization into a protocol
failure.

The first isolated run after deploying the connector used eight Trino writers
and one million rows.  It completed with exact correctness
(`1,000,000`, IDs `100,000,001`--`101,000,000`, all IDs distinct), and the
connector reported `primary_rpc_count=1` for each of the eight files.  However,
the daemon still emitted the legacy `PIXELS_PRIMARY_INDEX_METRIC` with
`vnode=-1`, rather than the new grouped metric.  This proves only that the
client reduced the RPC count; it is not evidence that the server-side grouped
RocksDB path ran.  The SQL wall was 15.008 seconds (about 66.6K rows/s), slower
than the accepted 7.980-second eight-writer baseline, so this result is
explicitly rejected as an optimization result.

The follow-up after restarting the isolated coordinator overlapped with the
service reconnection and ended in a gRPC GOAWAY/internal error; it is discarded
and its partially attempted ID range is not used as evidence.  The remaining
validation requirement is to restart every isolated daemon role that can host
IndexService, confirm `[PIXELS_PRIMARY_INDEX_GROUPED_METRIC]`, then rerun fresh
1M and 10M ranges and verify count/min/max/distinct IDs.  Until those metrics
are present, grouped primary writes remain experimental and are not a default
or accepted INSERT optimization.

### Clean 68-column INSERT -> UPDATE -> COMPACT E2E on cds20 (2026-11-21)

The isolated runtime was rebuilt with the grouped-primary experiment enabled
and a fresh table hierarchy.  A source table with the full 68-column schema was
filled to exactly 1,000,000 rows (`id` 1--1,000,000, all IDs distinct).  After
the one-time source vnode initialization, nine additional 100K source INSERTs
completed in 3.26--3.49 seconds each.  The first cold 100K source INSERT took
669.616 seconds and returned error 10203, but the rows and grouped index entries
were visible; this is recorded as a transaction/publication defect, not a
successful client transaction.

The first valid indexed target used an incorrect path shape and is retained only
as a negative control: its COMPACT failed after tag-index resolution because
metadata could not find the ordered directory.  A second target was created
with a single root `paths` URI, letting the connector register its compact and
ordered directories.  It had `table_id=620`, `index_id=610`, and a real `pk=id`
primary index.  The complete supported-tag chain was:

```text
table-to-table INSERT, 1,000,000 rows:
  SQL wall = 389.143 s; rows = 1,000,000; ids = 400000001--401000000
  [PIXELS_PRIMARY_INDEX_GROUPED_METRIC]
  grouped_threads=32; cold primary stage ~=385 s

UPDATE tag_1_type='AAA', tag_1_para=3.1400:
  SQL wall = 6.036 s; rows = 1,000,000; one shared Tag Index RPC
  before COMPACT, physical tag columns correctly remained NULL

tag-aware COMPACT -c 8:
  SQL/CLI wall = 53.529 s
  tag resolve: 1,000,000 rows, 62 RPCs, 12.814 s
  physical rewrite: 18.338 s; relocation: 1,000,000 rows, 128 RPCs
  metadata publish: 9.083 s

after COMPACT:
  physical scan: 1,000,000 rows, 1,000,000 rows with tag_1_type='AAA',
  tag_1_para min=max=3.1400
  tag predicate: 1,000,000 rows, ids 400000001--401000000
```

The clean target therefore proves the intended semantic chain: UPDATE writes the
Tag Index, COMPACT materializes both tag columns, and the tag predicate sees the
same million rows.  The warm INSERT path was also measured on a separate clean
indexed target: a second 1M batch completed in 6.654 seconds with exact 2M-row
post-checks, about 150K rows/s SQL wall; grouped primary writes used one RPC per
file and the daemon primary-put wall was about 2.95 seconds.  This is a useful
warm-path result, but it is not yet a production acceptance result because the
cold INSERT returned commit error 10213 after its data/index entries were
visible.  This error is `TRANS_LEASE_EXPIRED`: the transaction lease is fixed
at 300,000 ms while the cold vnode initialization takes about 385 seconds.
The warm 6.654-second batch did commit successfully.  The connector previously
had `extendTransLease()` available but did not call it for active Trino
transactions; the long-query detector only considered its separate 1,800-second
offload threshold.

The connector now checks and extends the lease for every active, non-offloaded
query during the detector's five-second polling cycle.  After deploying this
change to the isolated Trino, a fresh indexed target (`table_id=621`,
`index_id=611`) ran the same cold 1M-row INSERT in 409.300 seconds and
successfully committed.  The final check returned exactly 1,000,000 rows with
IDs `500000001`--`501000000`, all distinct.  This closes the transaction-status
gap for cold INSERT; it does not reduce the vnode initialization cost itself.

### Rejected RocksDB batch-CF cold-path experiment (2026-11-21)

To test whether the remaining cold cost was one RocksDB metadata RPC per hash
bucket, the isolated daemon was temporarily changed to call
`RocksDB.createColumnFamilies()` for all missing 128 bucket column families on
the first vnode.  A fresh 68-column table (`table_id=720`, `index_id=709`) was
loaded with 1,000,000 generated rows using eight Trino writers.  The run
committed successfully (`commit_ms=5.143`) and the final check was exact:
`1,000,000` rows, IDs `700000001`--`701000000`, all distinct, and all
`tag_1_type='AAA'`.

The performance result is rejected.  The first vnode incurred approximately
52 seconds for the batch CF operation, and the remaining vnode instances still
appeared serially at roughly 3.5--3.7 seconds each.  The end-to-end wall was
514.601 seconds, so the change did not remove the dominant
`SinglePointIndexFactory`/per-vnode initialization cost.  The temporary batch
CF code and configuration were reverted after the run.  This experiment also
separates two hypotheses: RocksDB CF creation may contribute to the first-vnode
spike, but the sustained 128-vnode serial initialization is elsewhere and must
be profiled before another implementation change.

## Acceptance criteria and remaining work

- [x] INSERT files publish as `REGULAR` with a valid row-id hull.
- [x] A committed 1M-row table survives COMPACT and two Retina restarts.
- [x] Exact-key 1M-row UPDATE reaches the hundreds-of-thousands rows/s range.
- [ ] Table-to-table INSERT reaches the lower hundreds-of-thousands rows/s
  range; the corrected 1M/10M SQL wall results are about 61--63K rows/s.
- [x] A multi-row-group 10M INSERT remains fully query-visible after the
  ordered-split row-group fix.
- [ ] Scale INSERT across multiple Trino writers/tasks and profile the SQLite
  main-index flush path; current vnode RPC parallelism is only an experimental
  1M-row improvement.
- [ ] Automate the recovered Retina replay/READY handshake for the validation
  harness.
- [ ] Profile and reduce duplicate Tag Index/primary-index resolution during
  COMPACT; the stage evidence identifies this as the leading next optimization.
- [x] Build/deploy the shared-primary-key RPC, compare old/new request bytes and
  warm UPDATE wall time, and verify multi-column tag correctness at the direct
  IndexServer boundary.
- [x] Keep the Trino transaction lease alive through cold vnode initialization;
  a fresh 409-second indexed INSERT now commits successfully instead of
  returning `TRANS_LEASE_EXPIRED` 10213.
- [x] Test and reject one-shot RocksDB CF creation as the primary cold-path fix;
  it preserved exact correctness but measured 514.601 seconds and left the
  per-vnode serial initialization pattern intact.
- [ ] Run the same shared-key uniqueness-proof route through a populated Trino
  SQL table and accept its table-level UPDATE throughput only after data
  visibility, correctness, and restart checks pass.

### Correctly deployed lease-renewal E2E and rejected lock-scope experiment (2026-11-21)

The first post-fix cold INSERT reruns were invalid as deployment tests: the
isolated Trino process had not been restarted with the active plugin/runtime
combination, and one restart attempt used a shared launcher.  The isolated
process was then restarted directly with its own `runtime/trino` classpath,
`runtime/trino-data`, and `runtime/pixels` configuration.  A temporary
diagnostic build showed the expected lease heartbeat in the actual process:
transaction `180020` was renewed every five seconds throughout a 100,000-row
cold write and committed successfully.  The diagnostic logging was removed
after confirmation.

A fresh indexed 68-column table (`table_id=1023`, `index_id=1012`) was then
loaded with 1,000,000 generated rows and eight Trino writers.  The accepted
E2E result was:

```text
SQL wall = 555 s (Trino timeline = 552.494 s)
rows = 1,000,000; ids = 4000000001--4001000000; distinct ids = 1,000,000
publish total = 150.973 ms; commit_ms = 7.845
primary grouped stage ~=548 s; grouped_threads=32
```

The transaction was renewed continuously across the five-minute lease window,
committed successfully, and the post-check returned the exact row count and
ID range.  This is the first clean million-row cold INSERT result with both
lease evidence and a successful commit after the earlier false-success
(`10203`/`10213`) cases.  It confirms that the remaining cold-path bottleneck is
per-vnode/index initialization, not transaction publication.

The second exploratory daemon change that removed the factory-wide vnode/CF
locks was not accepted.  It produced a correct 1M-row result but measured
about 555 seconds versus the prior 513--536 second cold runs, with no evidence
of end-to-end improvement.  The source and isolated runtime are restored to
the pre-experiment lock scope; further optimization should be driven by a
profile of the RocksDB/vnode initialization path rather than another
lock-scope guess.

### JFR-confirmed vnode CF bottleneck and accepted batch-CF optimization (2026-11-21)

The next profiling pass isolated the cold-path cost rather than inferring it
from SQL wall time. A 100,000-row generated 68-column INSERT on the baseline
runtime took 628 seconds in the Trino recording. Trino spent most samples in
blocking/waiting paths; JFR recorded no file-read events and only one 15.4 MB
output file. The isolated Retina recording showed native samples in
`org.rocksdb.RocksDB.createColumnFamily`, while the daemon log showed 128
`SinglePointIndex` creations for table `1121`/index `1110`, approximately one
every 4.6 seconds. The grouped primary metric was:

```text
entries=100000 grouped_threads=32 primary_ms=592370.680426
main_index_init_ms=0.055546 main_index_ms=122.378388
```

This identifies serial per-vnode RocksDB column-family creation as the cold
INSERT bottleneck. The accepted fix keeps the existing synchronized handle
cache and write path, but calls RocksDB's native
`createColumnFamilies(List<ColumnFamilyDescriptor>)` once for all vnode IDs
present in the grouped request before submitting the write futures. The
database is opened before constructing BloomFilter descriptors so the JNI
library is initialized on the deployed runtime.

The isolated A/B results are:

| Run | Rows | SQL wall | grouped primary | Correctness |
| --- | ---: | ---: | ---: | --- |
| baseline generated cold INSERT | 1,000,000 | 555 s | about 548 s | exact 1M check |
| batch-CF generated cold INSERT, table `1220`/index `1209` | 100,000 | 44 s | 39.026 s | 100,000; IDs 1--100,000; distinct 100,000 |
| batch-CF table-to-table cold INSERT, table `1320`/index `1309` | 100,000 | 10 s | 6.016 s | 100,000; IDs 1--100,000; distinct 100,000 |
| batch-CF generated cold INSERT, table `1321`/index `1310` | 1,000,000 | 23 s | 7.922 s | exact 1M; IDs 1--1,000,000; distinct 1M |

The million-row result is approximately 43.5K rows/s at SQL wall and the
grouped primary stage is approximately 126K rows/s. This is about a 24x
end-to-end improvement over the earlier 555-second cold result, but the
end-to-end target of several hundred thousand rows/s is not yet met. The
remaining gap is no longer vnode/index initialization; follow-up profiling
should focus on Pixels page-sink/file generation, publish batching, and the
Trino-side write pipeline. The experiment was correctness-checked before
acceptance and remains isolated until the connector/runtime deployment plan is
reviewed.

The optimized million-row table also accepted a 100,000-row exact-key
`UPDATE` without a lease or commit error. Its tag readback remained unchanged
before compaction, which is expected for the versioned update path. A follow-up
CLI COMPACT probe was not accepted as a visibility result because the synthetic
table's ordered-path metadata was absent after the isolated recovery reset;
existing UPDATE/tag correctness claims therefore remain based on the previously
validated tables and compact flow.

### Rejected grouped-writer concurrency and WAL probes (2026-11-21)

The accepted batch-CF run used a 32-thread daemon pool. Two isolated fresh
table probes tested whether changing that pool size or disabling RocksDB WAL
could close the remaining INSERT gap. The tests used the same generated 68-
column 1M-row SQL shape and verified the exact row count and distinct primary
keys where the query completed.

The concurrency result was not monotonic:

| Daemon grouped writer threads | Grouped primary | SQL wall | Decision |
| ---: | ---: | ---: | --- |
| 32, warm baseline | 7.922 s | 23 s | current best |
| 64 | 52.867 s | about 66 s | rejected; severe contention |
| 16 | 47.545 s | about 66 s | rejected; no improvement |

The first 16/64 measurements after a full daemon restart also included the
one-time RocksDB reopen cost; a later warm 32-thread run returned to the
8--10-second primary-index range. This warm-up effect is why the thread-size
probes are retained as directional evidence rather than a strict steady-state
benchmark. The important result is that increasing the pool beyond the
validated 32 threads is harmful on this host, consistent with RocksDB/CF
contention rather than a lack of Java executor parallelism.

An opt-in `index.rocksdb.disable.wal` switch was added for an isolated probe,
with the default remaining `false`. After the restart/open cost was excluded,
the second WAL-off 1M run recorded:

```text
grouped_threads=32 primary_ms=8900.253
main_index_ms=539.224 total_ms=9439.585
primary_put_wall_ms=10650.670
```

This is not faster than the default-WAL 1M runs (`primary_put_wall_ms` about
10.3 seconds), so disabling WAL is rejected: it adds crash-recovery risk
without an observed throughput benefit. The isolated runtime configuration was
restored to `index.rocksdb.disable.wal=false`; the switch remains only as a
documented benchmark knob for future controlled experiments.

### Rejected larger Trino sink batch probe (2026-11-21)

To test whether PageSink conversion and writer flush overhead explained the
remaining INSERT wall time, the isolated Trino runtime was restarted with
`row.batch.size=50000` instead of the validated 10,000-row default. A fresh
1M-row generated 68-column INSERT remained correct and produced:

```text
query elapsed = 24.90 s
flush_count = 20
write_ms = 2253.335
index_ms = 1324.295
primary_put_wall_ms = 12380.156
```

The comparable 10,000-row run had 100 flushes, about 20.76 s query elapsed,
`write_ms=1935.363`, `index_ms=1032.381`, and
`primary_put_wall_ms=10338.425`. Reducing flush count therefore did not reduce
the dominant cost and slightly worsened this run; the default batch size was
restored. The next INSERT work should remain focused on the Trino TableWriter /
Exchange backpressure and the grouped RocksDB write path, with per-stage
profiling rather than a larger PageSink batch as the next experiment.

### JFR-guided conversion allocation reductions (2026-11-21)

Runtime-level profiling of a fresh 1M-row, 68-column generated INSERT showed
that `PixelsPageSink.appendValue` and value conversion were material CPU and
allocation hotspots. The initial JFR recording attributed 38.26% of sampled
allocation pressure to `byte[]`; it also showed `ByteBuffer`/decimal conversion
and `PixelsPageSink.appendValue` on the hot path. Two low-risk changes were
therefore implemented and pushed:

* short decimals are written as their already-unscaled `long` value, and long
  decimals use the Trino `Int128` high/low words directly, avoiding temporary
  8/16-byte arrays and `ByteBuffer` wrappers;
* heap-backed Trino `Slice` values for VARCHAR/CHAR/BINARY/VARBINARY are retained
  by reference in `BinaryColumnVector`; non-heap slices use the original copy
  fallback.

The direct-decimal run on table `profile_batch_decimal_direct_20261121_1459`
completed correctly:

```text
query elapsed = 22.81 s, query CPU = 8.67 s
TableWriter add wall/cpu = 9.22 s / 7.60 s
sink write_ms = 1980.328, index_ms = 1124.141
primary_put_wall_ms = 12338.489
count/min/max/distinct(id) = 1000000/1/1000000/1000000
```

The zero-copy Slice run on table `profile_batch_slice_zero_copy_20261121_1505`
also completed correctly:

```text
query elapsed = 24.02 s, query CPU = 9.07 s
TableWriter add wall/cpu = 9.15 s / 6.95 s
sink write_ms = 2532.891, index_ms = 1743.096
primary_put_wall_ms = 12190.602
count/min/max/distinct(id) = 1000000/1/1000000/1000000
```

The zero-copy JFR recording reduced sampled `byte[]` allocation pressure from
38.26% to 17.75%, and reduced `PixelsPageSink.appendValue` from 11.44% of
execution samples to 0.69%. The single-run SQL wall time did not yet improve
because Exchange/backpressure and grouped primary-index time remain variable;
these changes are retained as allocation-pressure improvements, not claimed as
the final throughput breakthrough. The next bottleneck work should target the
Pixels encoding path (`RunLenIntEncoder`, `StringColumnWriter`, dictionary
construction) and the primary-index RPC/write stage. The validated
`row.batch.size=10000`, grouped writer pool of 32, and WAL default remain
unchanged.

### RLE-aware PageSink batching (2026-11-21)

The generated tagged dataset contains a timestamp constant and 30 constant tag
type columns, which Trino represents as `RunLengthEncodedBlock`. After the
zero-copy change, JFR still showed repeated `ShortTimestampType.getLong` work.
The PageSink now detects RLE blocks per page chunk, decodes the single value
once, and fills the Pixels vector with the cached primitive, decimal, or Slice
value. Non-RLE blocks retain the normal row conversion path.

The fresh table `profile_batch_rle_20261121_1512` passed the exact INSERT
check:

```text
query elapsed = 22.42 s, query CPU = 6.53 s
TableWriter add wall/cpu = 6.57 s / 4.41 s
sink write_ms = 2583.234, index_ms = 1713.791
primary_put_wall_ms = 12945.940
count/min/max/distinct(id) = 1000000/1/1000000/1000000
```

Compared with the direct-decimal plus zero-copy run, TableWriter CPU fell from
6.95 s to 4.41 s and query CPU fell from 9.07 s to 6.53 s. SQL wall time is
now about 22.4 s, or roughly 44.6K rows/s for this 1M-row end-to-end INSERT.
The remaining dominant segment is grouped primary-index write (about 12.95 s);
the RLE optimization is accepted and does not change the validated batch size,
32-thread grouped writer, WAL setting, or tag schema.

As a route regression probe, the same table accepted a 100,000-row shared-key
UPDATE with no lease/commit error:

```text
UPDATE route metric: encoding_ms=40.965 request_build_ms=78.655
rpc_ms=7791.320 total_ms=7914.605
```

The pre-compaction read still showed the original visible tag values, which is
expected for this versioned update path and is not counted as post-compaction
tag correctness. Existing compacted UPDATE evidence remains the correctness
baseline for the update route.

### Primary bucket-hash allocation probe (2026-11-21/22)

JFR on the grouped primary-index service showed repeated work in
`IndexUtils.getBucketIdFromByteBuffer`: the old implementation converted each
key to a temporary byte array and Guava `HashCode` object for SHA-256 bucket
selection. Commit `0421124e` replaces those allocations with a thread-local
key buffer, `MessageDigest`, and digest buffer. Commit `db72b9e1` corrects the
byte order after verification that Guava `HashCode.asInt()` is little-endian;
the bucket assignment is therefore unchanged.

The change was deployed only to the isolated runtime. On an indexed 68-column
table (`profile_bucket_hash_pk_20261122`, table 1822, index 1809), using the
same 1M-row synthetic shape for each run, the observed grouped metrics were:

```text
cold first 1M: SQL wall 72.473 s
  primary_ms=61476.399, main_index_ms=781.534, total_ms=62258.068
warm next 1M: SQL wall 11.723 s
  primary_ms=2181.589, main_index_ms=519.156, total_ms=2700.859
warm next 1M: SQL wall 11.153 s
  primary_ms=2053.225, main_index_ms=478.767, total_ms=2532.034
post-run correctness: count/min/max/distinct(id)=3000000/1/3000000/3000000
```

This is useful evidence that the warm grouped primary path can reach about
90K rows/s for this synthetic value shape, but it is not a causal A/B
acceptance result: the old-jar comparison was blocked by an independent
recovery issue. After a prior recovery, the checkpoint pointer remained in the
isolated etcd namespace while its body had been removed, so the fail-closed
Retina startup correctly refused to boot. The stale isolated pointer was
removed, `retina.recovery.bootstrap.regular=true` was used, and visibility for
the three catalog-regular files of this probe was rebuilt through the existing
`addVisibility` RPC. The recovery behavior must be fixed and separately
validated before using restart recovery as a performance acceptance gate.

The bucket-hash optimization remains a low-risk retained probe, not a claim
that the full 68-column INSERT target has been reached. The accepted broad
baseline remains the real 68-column table-to-table INSERT at roughly
52--63K rows/s, while the exact shared-key UPDATE route remains roughly
157--166K rows/s end-to-end SQL wall throughput.
