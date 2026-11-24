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

### Stable warm path profiling and the cold reopen split (2026-11-22)

The same isolated 68-column table was profiled with JFR on the Retina/index
service while running grouped 1M-row INSERTs. This separates a daemon restart
effect from the steady-state write path:

```text
after daemon restart:
  primary_ms=59632.567, main_index_ms=746.302, total_ms=60378.990
  JFR included RocksDB.open/createRocksDB/getOpenRocksDB

stable warm run 1:
  SQL wall=10.296 s, primary_ms=2172.276,
  main_index_ms=507.538, daemon total_ms=2679.884

stable warm run 2:
  SQL wall=13.208 s, primary_ms=2162.692,
  main_index_ms=447.017, daemon total_ms=2609.742
```

The stable JFR contained 133 execution samples and no `RocksDB.open` samples.
Its dominant sampled frames were protobuf/gRPC decode and request handling
(`PrimaryIndexEntry.parsePartialFrom`, `putPrimaryIndexEntries`, and
`putGroupedPrimaryEntries`), followed by MainIndex buffer/SQLite flush. This
means the 60-second post-restart event is a one-time service-level reopen of
the existing multi-column-family RocksDB, not a per-row INSERT cost. A future
startup-prewarm option is therefore a correctness-preserving latency fix, but
it should not be counted as a steady-state throughput improvement.

The Trino-side metrics explain the remaining end-to-end variance. In the two
warm runs, PageSink data writes were 1.661 s and 2.205 s, index-writer metrics
were approximately 4.083 s and 4.122 s including RPC/wall aggregation, and
publish/commit stayed below 50 ms. Query elapsed time was 10.296 s and
13.208 s, so the difference was outside the daemon's stable primary path,
mainly source-scan/task-finishing scheduling. The two observed end-to-end
rates were about 97.1K and 75.7K rows/s. Correctness after the second run was
`count/min/max/distinct(id)=6000000/1/6000000/6000000`.

This changes the optimization priority: do not spend another iteration on
primary bucket hashing or MainIndex before measuring the source scan and task
finishing path. The next high-value experiment is a controlled INSERT with
the same 68-column shape but a materialized/local source, then an explicit
comparison of source-read time, PageSink write time, and task-finishing time.
Only after that comparison should a packed primary-index RPC be considered;
the current JFR evidence suggests protobuf allocation is a secondary warm-path
cost, not the cause of the original plateau.

### Trino writer-count sweep (2026-11-22)

Using the same isolated table and the same 1M-row self-source shape, the
writer count was changed only through per-query session properties
(`task_min_writer_count`, `task_max_writer_count`, and
`task_scale_writers_enabled=false`). Trino's query timeline, rather than the
CLI startup-inclusive wall clock, is the comparable measurement:

```text
writers  elapsed   rate      fragments  publish
4         13.208 s  75.7K/s  1           43.7 ms
8          9.789 s 102.2K/s  8          119.7 ms
16         9.290 s 107.6K/s 16          182.3 ms
32         8.774 s 114.0K/s 32          300.9 ms
64         8.842 s 113.1K/s 64          468.8 ms
```

The 32-writer run is the best point in this sweep: it clears the 100K-row/s
end-to-end target on the isolated 68-column workload without the extra
fragment/publish cost of 64 writers. The 64-writer result is a useful negative
control: more writer concurrency does not improve the stable primary-index
service, and its metadata cost continues to rise. After the sweep,
`count/min/max/distinct(id)=10000000/1/10000000/10000000`.

This is a per-query validation result, not yet a global default change. Before
making writer=32 a deployment default, repeat it on the normal target/source
pair and check read-side effects from the higher fragment count. The current
accepted UPDATE baseline remains 157--166K rows/s; the INSERT route now has a
repeatable isolated warm result of about 114K rows/s at the selected point.

### Negative probes and Trino-side JFR (2026-11-22)

Two additional isolated probes were run and deliberately not retained as
optimizations:

1. A `DictionaryBlock` fast path for one-entry dictionaries compiled cleanly
   and passed compilation of the new PageSink test, but did not improve the
   warm 68-column INSERT. After connector restart, the first comparable run
   was 13.593 s and the next warm run was 10.331 s; the accepted writer=32
   baseline is 8.774 s. The connector was restored to the previous jar.
2. Increasing `row.batch.size` from 10,000 to 20,000 reduced flush count per
   writer but made the query slower: Trino elapsed was 12.844 s versus 8.774 s
   at 10,000. The 10,000 setting was restored. This workload's wide decimal
   and string columns do not benefit from the larger row batch.

A 2,300-sample JFR of the stable INSERT on the restored connector makes the
next priority explicit. The hottest roots were:

```text
PixelsRecordReaderImpl.readBatch       800 samples in sampled stacks
PixelsFilePageSource.getNextPage       638
ScanFilterAndProjectOperator...process 516
StringColumnReader.readSelected        324
RunLenIntEncoder.encode/write          301/292
StringColumnWriter.write/newPixel      220/205
PixelsPageSink.appendValue              110
PixelsInsertIndexWriter.appendBatch     30
```

The counts are JFR stack-sample counts, not exclusive CPU percentages, but the
ordering is sufficient to reject another primary-index-only iteration. The
dominant cost is the table-to-table source read plus string/decimal decoding
and output encoding. The next implementation candidates are therefore a
reader-to-writer vector path that preserves tag semantics, reducing repeated
variable-width materialization, and a controlled source-scan benchmark that
separates read CPU from writer CPU. Any such change must retain the hidden
timestamp/visibility filtering and the existing tag index publication rules.

### LazyBlock unwrapping probe (2026-11-22)

The Trino 466 SPI exposes `LazyBlock.getLoadedBlock()`. A candidate PageSink
change resolved lazy blocks once before checking RLE/dictionary fast paths, and
the connector plus a dedicated lazy-single-entry dictionary test compiled
successfully on cds20's JDK 23. It was not accepted after the live probe:
the comparable-looking runs were 11.952 s and 10.302 s, versus the earlier
8.774 s writer=32 baseline.

This is not a valid causal rejection because the source table had grown from
approximately 9M to 16M rows between the baseline and the probe. The query's
`WHERE id <= 1M` is not proven to prune all unrelated files, so source-scan
work changed with the table size. The candidate connector was restored and no
code commit was made. A fair follow-up requires a fixed, materialized 1M-row
source table and a fresh target for both A/B arms.

### Fixed-source E2E A/B and grouped-primary saturation probe (2026-11-22)

The fair follow-up was established in the isolated runtime. A materialized
source table, `bench68_v2.e2e_fixed_source_20261122`, contains exactly
1,000,000 rows with `id` 1--1,000,000 and 1,000,000 distinct IDs. Two fresh
68-column indexed targets were then used so that the source file set did not
grow between the A/B arms.

The restored connector, 32 writers, and the validated 10,000-row batch size
gave this query timeline on the fresh target:

```text
Query 20261121_181526_00032_n2gap
elapsed=12.843 s, running=2.563 s, finishing=10.061 s
rate=77.9K rows/s
correctness=1,000,000/1/1,000,000/1,000,000
```

The candidate that unwraps `LazyBlock` and handles a one-entry
`DictionaryBlock` was then built on cds20's JDK 23 and deployed only to the
isolated Trino. It passed compilation, but two fixed-source runs were slower:

```text
after connector restart: 17.822 s = 56.1K rows/s
same connector, no restart: 14.348 s = 69.7K rows/s
```

Both candidate targets passed the row-count, ID-range, ID-distinctness, and
tag-value checks. The candidate was rolled back; no LazyBlock code is retained.
The second run is sufficient to reject this fast path for the current wide
table-to-table INSERT, without relying on the earlier invalid comparison
against a source table that had grown from 9M to 16M rows.

The fixed-source baseline also exposed a more important concurrency shape.
The client sends one grouped primary-index RPC per Trino writer. Each grouped
RPC is split again by the daemon into up to 32 RocksDB bucket tasks. With 32
client writers, the daemon log showed 32 concurrent grouped requests whose
server-side primary-index time accumulated to approximately 9--10 seconds,
while the associated main-index work was usually tens of milliseconds after
initialization. A same-target append with the restored connector remained
13.375 seconds (`74.8K rows/s`) and showed the same grouped-primary pattern.
This points to a client-writer versus daemon-grouped-thread oversubscription
hypothesis, not a PageSink dictionary conversion bottleneck.

An attempted 8/16-writer follow-up was not accepted as data: the isolated
Retina metadata namespace had a stale writer-buffer condition while creating
a new target, and the resulting validation queries were canceled after they
failed to produce complete INSERT metrics. The runtime was left on the
restored connector. The next valid experiment should reuse one clean target
or reset only the isolated Retina metadata state, then sweep client writers
against the daemon grouped thread count while recording both query timeline
and `[PIXELS_PRIMARY_INDEX_GROUPED_METRIC]`.

## Clean fixed-source rerun and recovery failure (2026-11-22)

The previous fixed tables were not reusable after a Retina restart. Recovery
correctly removed their stale visibility entries because the catalog files
were `TEMPORARY_INGEST`; the remaining physical files were zero bytes. A new
source and target were therefore created in the isolated runtime. The source
contains the same 68-column shape, one million rows, and `id=1..1,000,000`.

The first clean target intentionally had no primary index. Its table-to-table
INSERT took 7.390 s in the Trino timeline, about 135K rows/s for the SQL
operation (the surrounding CLI wall included client startup). A second clean
target was created with the real `pk=id`, `pk_scheme=rocksdb` required by the
UPDATE route. Its results separate the cold and warm primary-index costs:

```text
pk target, first 1M INSERT:
  SQL wall = 141.886 s; about 7.0K rows/s
  file write = 1.677 s
  insert-index metric: primary_put_wall_ms=134032
  daemon grouped-primary metric: primary_ms=130938; main_index_ms=756

same pk target, second 1M INSERT (ids 1,000,001..2,000,000):
  SQL wall = 13.134 s; about 76.1K rows/s
  file write = 2.281 s
  insert-index metric: primary_put_wall_ms=4666
  daemon grouped-primary metric: primary_ms=2371; main_index_ms=572

same target, warm exact UPDATE of tag_2 on 1M rows:
  SQL wall = 1.321 s; about 757K rows/s
  one shared UPDATE RPC; connector metric total = 1.173 s
```

The cold INSERT is therefore dominated by first-time RocksDB primary-index
column-family/bucket initialization, not by the 68-column PageSink encoding.
After warm-up, INSERT is still below the several-hundred-thousand target,
while warm exact UPDATE is above it. The accepted next optimization target is
the cold-index initialization and the warm table-to-table read/encode path;
the rejected `LazyBlock` fast path remains rejected.

A 100,000-row single-root-path semantic probe completed the supported tag
chain before restart:

```text
UPDATE 100,000 rows -> tag-aware COMPACT -c 8
tag resolve = 100,000 rows, 7 RPCs
physical rewrite = 100,000 rows
primary relocation = 100,000 rows, 128 RPCs
post-COMPACT count = 100,000
post-COMPACT tag count = 100,000, tag_1_para = 3.1400
```

The subsequent isolated Retina restart exposed a separate durability blocker.
The recovery pointer still referenced
`recovery_cds20_0.bin`, but the body had been deleted, so fail-closed recovery
aborted rather than treating the deployment as fresh. An existing isolated
backup body was restored only to bring the validation daemon back; after that
stale checkpoint was applied, the new compact file was classified as
`RETIRED` and the probe read zero rows. Therefore the pre-restart compact
result is valid semantic evidence, but no post-restart persistence claim is
accepted for this rerun. The finding is recorded as: recovery checkpoint
pointer/body retention must be fixed and a current checkpoint must be
published before restart persistence can be re-accepted.

## RocksDB background-flush A/B (2026-11-22)

The isolated runtime was then tested with only its Retina configuration
changed from `max.background.flushes=2, max.background.compactions=4` to
`32,16`. A new one-million-row fixed source and a new `pk=id` target were
used; the original connector and the grouped client path were unchanged.

```text
default 2/4, first 1M pk INSERT:
  SQL wall = 141.886 s; about 7.0K rows/s
  primary_put_wall_ms = 134032

experimental 32/16, first 1M pk INSERT:
  SQL wall = 84.108 s; about 11.9K rows/s
  primary_put_wall_ms = 75030
  daemon grouped-primary primary_ms = 72562; main_index_ms = 699
  file write_ms = 2237
  Retina RSS observed during the run ~= 72 GB
```

The higher background concurrency gives a real 40.7% cold-index wall-time
improvement, but it is not an acceptance configuration: the absolute rate is
still far below the target and memory amplification is substantial. The
runtime was restored to the default 2/4 values and Retina was restarted and
marked READY. This experiment supports a lower-resource initialization design
(for example bounded vnode/CF creation and staged prewarming), not simply
raising all RocksDB thread counts.

A smaller follow-up with `max.background.flushes=8, max.background.compactions=8`
was intentionally run on a new 100,000-row pk target. It was worse, not
better:

```text
default 2/4, earlier 100K pk probe: primary_put_wall_ms ~= 9921
experimental 8/8, new 100K pk probe: primary_put_wall_ms = 66921
```

The Retina RSS was again about 72 GB during the experiment. This rejects a
simple monotonic thread-count tuning rule. The isolated runtime was restored
to 2/4 and restarted/marked READY; neither experimental setting is retained.

## Bucket-count and write-buffer probes (2026-11-22)

The next isolation step tested whether the cold primary-index cost was mainly
the number of RocksDB column families. Two fresh narrow source/target pairs
were used with 100,000 unique `id` values and 32 Trino writers. The source was
written in the same ten 10K-row fragments for both arms; only the isolated
Retina `index.bucket.num` setting changed:

```text
bucket.num=16:  grouped-primary ~=63.8 s; E2E ~=66.9 s
bucket.num=128: grouped-primary ~=63.5 s; E2E ~=66.8 s
```

Both targets passed `count/min/max/distinct(id)=100000/1/100000/100000`.
The difference is below measurement noise, so reducing the bucket count is
rejected as a stand-alone optimization. The absolute cold time was also
state-sensitive: a later new 128-bucket target on the same runtime completed
in about 10.3 s while the process was in a lower-pressure state. This means a
single cold run must not be used to claim a stable bucket improvement.

The write-buffer size was then varied with the default background settings
(`flushes=2`, `compactions=4`) and fresh narrow 100K targets. The results show
the memory/performance trade-off, not an accepted deployment setting:

```text
64 MiB: primary ~=10.3 s in the low-pressure run; RSS later reached ~=77 GiB
  during the cold-index probes (other 64 MiB runs reached 35--63 s)
16 MiB: primary ~=40.5 s; E2E ~=43.9 s; RSS ~=38 GiB
  8 MiB: primary ~=35.4 s; E2E ~=38.8 s; RSS ~=31 GiB
```

The smaller buffers reduce resident memory but do not improve the stable cold
throughput; they remain far below the target rate. RocksDB logs showed no
write-stall counter increase during the high-RSS run. Together with the RSS
change, this supports a multi-column-family memtable/arena amplification
hypothesis rather than a simple L0-stall explanation. The runtime was restored
to `index.bucket.num=128`, `write.buffer.size=67108864`, and background
`2/4`, then restarted and marked READY. None of these probe settings is
retained.

## Database-level RocksDB write-buffer cap probe (2026-11-22)

RocksDB exposes a database-level `db_write_buffer_size` specifically for
sharing a memtable budget across many column families. The validation branch
now exposes this as `index.rocksdb.db.write.buffer.size`, disabled by default
(`0`) so existing deployments retain their behavior. The isolated daemon was
run with the new option set to 16 GiB while the per-CF setting remained 64 MiB.
The generated RocksDB `OPTIONS` file confirmed
`db_write_buffer_size=17179869184`, so this was a real cap probe rather than a
configuration-only test.

On a fresh narrow 100K target, the cap arm produced:

```text
grouped-primary ~=71.3 s
E2E wall ~=74.5 s
RSS ~=79 GiB
count/min/max/distinct(id)=100000/1/100000/100000
```

It was slower than the uncapped arms and did not prevent the observed RSS
growth. This rejects the 16 GiB database-level cap as a throughput fix for the
current multi-CF implementation. The code-level knob is retained as an
optional memory-protection/diagnostic control, but it is not enabled in the
runtime and is not an acceptance configuration. The isolated runtime was
restored to the original daemon jar and the default 64 MiB/2/4 RocksDB
settings, then restarted and marked READY.

## Clean RocksDB namespace and wide-source construction probe (2026-11-22)

The preceding cold-index probes all reused a shared RocksDB directory that had
accumulated roughly 8,000 column families from historical experiments.  To
separate the workload from that state, the isolated Retina was restarted with
the same original daemon jar and default RocksDB tuning, but with a new empty
`index.rocksdb.data.path`.

The clean namespace materially changed the narrow indexed INSERT baseline:

```text
fresh narrow 100K pk target: E2E = 4.989 s
fresh narrow 1M pk target:   E2E = 5.857 s ~=170.7K rows/s
correctness for 1M target:   count/min/max/distinct = 1000000/1/1000000/1000000
Retina RSS after 1M target:  ~=28.5 GiB
```

This is the strongest evidence so far that the historical shared-CF state is a
first-order bottleneck: the same isolated machine previously ranged from tens
of seconds to more than two minutes for cold indexed probes, with RSS near
70--80 GiB.  It does not yet prove that a new directory is a production-safe
fix.  In particular, the current recovery checkpoint is older than these
tables; after restart, post-checkpoint rows are not durable and must not be
counted as a successful persistence result.

The corresponding 68-column source construction was deliberately not accepted
as a wide-table baseline in this run.  One malformed `--file` invocation and a
duplicate trailing semicolon produced 2M rows instead of 1M.  Several retries
also exposed a Trino-side construction artifact: large `UNION ALL` source
inserts fan out to thousands of writers and can leave one final driver in a
long tail after all Pixels page sinks have completed.  Those queries were
cancelled and excluded.  A future wide benchmark must first verify an exact
1M-row unique source, then measure only the target table-to-table INSERT; source
generation time and writer-tail failures must be reported separately.

The clean namespace therefore becomes the preferred isolated baseline for the
next experiment, while the shared-CF namespace and the malformed wide-source
arms remain diagnostic/rejected results.

## Single-table RocksDB namespace probe (2026-11-22)

To isolate the remaining per-table cost, the validation runtime was moved to a
brand-new RocksDB directory and a new source/target pair was created after the
restart.  The source was checked before the target write:

```text
source count/min/max/distinct = 1000000/1/1000000/1000000
```

The target used `pk=id` and passed the exact-value check after each batch:

```text
cold 1M target: 9.916 s ~=100.8K rows/s
  grouped primary_ms=2997.231; main_index_ms=539.218
warm 1M target (ids 1000001..2000000): 7.813 s ~=128.0K rows/s
  grouped primary_ms=2255.387; main_index_ms=520.714
final count/min/max/distinct/value-check =
  2000000/1/2000000/2000000/2000000
Retina RSS after the two batches ~=33.2 GiB
```

The corresponding RocksDB column-family counts were 8,299 in the historical
shared directory, 513 in the multi-table clean probe, and 257 in this
single-table directory.  The single-table arm is therefore a stronger causal
control than the earlier clean-directory result: removing historical CFs
improves cold INSERT by roughly an order of magnitude, but does not by itself
reach the million-row target.  The next optimization should reduce the
per-CF/per-vnode memory and publication cost, or reduce the number of CFs held
open, while preserving tag-aware UPDATE and recovery semantics.

## RocksDB max-write-buffer-count probe (2026-11-22)

The single-table baseline used `index.rocksdb.max.write.buffer.number=3`.
Because each vnode is a column family, an arm with the same 64 MiB per-CF
buffer but `max.write.buffer.number=1` was tested in a new RocksDB directory.
The source again passed the exact 1M-row check and the indexed target passed
the exact-value check:

```text
max_write_buffer_number=1:
  E2E = 11.837 s ~=84.5K rows/s
  grouped primary_ms=3354.738; main_index_ms=786.811
  RSS ~=28.5 GiB
  count/min/max/distinct/value-check = 1000000/1/1000000/1000000/1000000

single-table default max_write_buffer_number=3:
  E2E = 9.916 s ~=100.8K rows/s
  RSS ~=31--33 GiB in the corresponding cold run
```

The count-1 arm reduces memory by roughly 2--3 GiB but is about 19% slower,
so it is rejected as a throughput optimization.  It may remain useful as a
resource-constrained diagnostic setting, but is not enabled.  A count-2 arm
was not assigned a throughput number: the isolated Retina did not reach READY
within roughly four minutes while replaying the stale checkpoint and spawned
about 1,800 recovery-related threads.  That arm was aborted as environment
interference, not interpreted as a RocksDB result.  The runtime was restored
to the single-table path with the default count 3 and marked READY.

## RocksDB open-cost instrumentation (2026-11-23)

The diagnostic commit `7fe532cc` was compiled with the remote JDK 23 Maven
environment and temporarily injected ahead of the original daemon jar on the
isolated Retina classpath.  A one-row INSERT into a temporary pk table forced
the lazy first index open.  The new metric reported:

```text
existing_cf=257
list_cf_ms=196
descriptor_ms=134
db_open_ms=17713
total_ms=18047
```

This is direct evidence that the first index operation pays about 17.7 seconds
inside `RocksDB.open` even after the historical 8,299-CF directory has been
replaced by a single-table 257-CF namespace.  Column-family enumeration and
descriptor preparation are comparatively small.  The result strengthens the
case for a table-scoped database or a lifecycle that does not open every
historical vnode CF at once; thread-count and write-buffer tuning cannot remove
this native open cost.  The override jar was removed after the probe and the
runtime was restored to the original full jar, so the metric change is
observability only and is not part of the running benchmark configuration.

## Opt-in table-scoped RocksDB state probe (2026-11-23)

The next small optimization isolates RocksDB column families by table. The
historical implementation opens one shared database for every primary and tag
index. This makes the first write to a new table pay the native open cost for
all historical column families and couples unrelated tables' memory and
lifecycle. The change adds:

```properties
index.rocksdb.table.scoped=false
```

The default is deliberately `false`, preserving shared-database behavior. With
the opt-in enabled, each table uses
`<index.rocksdb.data.path>/table-<tableId>`, and primary/tag index handles are
owned by that table's RocksDB state. `RocksDBIndex` and `PixelsTagIndex` pass
the table ID through all open/close paths. The module compiled successfully on
cds20 with the remote JDK 23 Maven build.

The fixed probe used a 68-column source with 10,000 generated rows and two
independent primary-key targets. Both passed:

```text
target C INSERT: count/min/max/distinct = 10000/1/10000/10000
target D INSERT: count/min/max/distinct = 10000/1/10000/10000
RocksDB directories: table-1975, table-1976
CF counts before tag UPDATE: table-1975=129, table-1976=129
```

The tag target then ran the supported two-column update and compaction path:

```sql
UPDATE pixels.bench68_v2.e2e_scoped_tagfix_20261123
SET tag_1_type='CCC', tag_1_para=DECIMAL '3.1400'
WHERE id BETWEEN 1 AND 10000;
```

The daemon recorded 10,000 primary keys and two tag updates. As expected for
the versioned UPDATE protocol, the pre-compaction read still showed the old
materialized tag values. Tag-aware compaction completed in 0.956 s of physical
rewrite time and published one compact file. Post-compaction checks passed:

```text
rows=10000
count(tag_1_type='CCC')=10000
count(tag_1_para=3.1400)=10000
count(both predicates)=10000
min(id)=1, max(id)=10000, distinct(id)=10000
tag predicate result=10000
```

The final layout had `table-1977=130` CFs (the extra CF is the tag index),
while the independent primary-only table remained `table-1976=129`. This is
direct evidence that tag-index writes and compaction remain in the per-table
namespace and do not merge with another table.

Two non-result failures were captured. The first deployment omitted the
`RocksDBFactory$DbState` inner class from the override jar and failed at class
loading; rebuilding the jar with the outer and inner classes fixed it. A
separate target used two already-materialized `v-0-*` paths and caused the
compactor to construct a nested path; it was discarded and the clean single
root-layout arm above was used. Neither failure is included in throughput
numbers.

This probe validates correctness and namespace isolation, but is not a
million-row acceptance result. It also does not claim restart persistence: the
isolated runtime currently uses a stale recovery checkpoint backup for safe
experiments, so a restart would intentionally retire newly-created files. A
valid persistence arm requires first producing a checkpoint that contains the
new table files. The optimization remains opt-in until that checkpoint-backed
restart test and a broader apples-to-apples A/B comparison are completed.

## Table-scoped fixed-source 1M arm (2026-11-23)

After the 10K correctness probe, a fixed 68-column source was generated in
100 chunks of 10,000 rows because Trino 466 limits one `sequence()` result to
10,000 entries. The source passed `count/min/max/distinct =
1000000/1/1000000/1000000`. A fresh table-scoped target then measured:

```text
cold table-to-table INSERT: 11.752 s ~=85.1K rows/s
  grouped primary_ms=2893.347; main_index_ms=768.314
warm append of another 1M rows: 9.657 s ~=103.6K rows/s
  grouped primary_ms=2202.806; main_index_ms=514.130
final target count/min/max/distinct = 2000000/1/2000000/2000000
```

The first attempt used a new RocksDB root without creating its parent
directory and failed with index error 10602 before opening RocksDB. This was a
deployment/setup defect: RocksDB creates its own database directory but not a
missing parent. The code now creates the full path before listing or opening
column families, and a direct fresh-path probe confirmed that a missing
`table-3000` directory is created and opened successfully. The failed arm is
not included in the throughput numbers.

The same 2M-row table then ran the exact two-column tag UPDATE for the first
1M keys:

```text
UPDATE wall time: 1.645 s ~=608K rows/s
tag storage: 2 logical updates, 1,000,000 primary keys
```

Tag-aware compaction completed successfully. Because the target contained 2M
rows, it rewrote both ordered files: physical rewrite 34.687 s, resolve
26.185 s, publish 20.009 s. The final materialized checks passed:

```text
total rows=2000000
updated tag_1_type='UPD1M'=1000000
updated tag_1_para=9.0000=1000000
both tag predicates=1000000
min(id)=1, max(id)=2000000, distinct(id)=2000000
```

This is the first table-scoped million-row end-to-end arm that covers both
INSERT and the supported tagging UPDATE path. INSERT remains in the 85--104K
rows/s range on this wide fixture, while UPDATE reaches about 608K rows/s.
The result is materially below the desired million-row INSERT target but
above the minimum tens-of-thousands floor; it does not yet establish a
million-row INSERT acceptance result.

## Connector dispatch and sink batch probes on the fixed 68-column target (2026-11-23)

Two INSERT-side experiments were run against the same hot target,
`bench68_v2.page_dispatch_target_1m_20261123`, using contiguous 1M-row
appends and exact count/min/max/distinct checks after every arm. The target
reached 7,000,000 rows with IDs 1--7,000,000 and no duplicates.

An opt-in connector candidate hoisted the type switch in
`PixelsPageSink.appendColumnValues` outside the row loop. It compiled and
preserved the RLE path, but the alternating evidence did not show a stable
benefit. The candidate arms were 7.271 s and 8.709 s; original-connector
controls were 10.717 s, 8.852 s, and 13.074 s. The component metrics explain
the noise: candidate and original `append_ms` were both about 1.37 s, while
source scheduling/read and primary-index RPC wall time varied materially
between process restarts. The candidate was reverted and is **rejected**;
there is no connector commit for it.

A configuration-only sink probe increased `row.batch.size` from 10,000 to
100,000. It reduced `flush_count` from 100 to 10, but the 1M append took
13.555 s (about 73.8K rows/s), with `write_ms=941.937` and
`index_ms=1360.375`, versus the restored 10K configuration. It was reverted
and is **rejected**. The default 10K batch remains active.

The same target also ran a supported exact-key two-column tag UPDATE over the
first 1M rows. SQL wall time was 5.989 s (about 167K rows/s); the connector
route reported `rows=1000000`, `encoding_ms=68.867`,
`request_build_ms=271.999`, one shared RPC, `rpc_ms=1563.307`, and
`total_ms=1913.820` (about 522K rows/s inside the route). The daemon reported
one 10MB shared request with `decode_ms=248.345`, `storage_ms=536.007`, and
`service_ms=1237.085`. Physical tag columns remained unchanged before
COMPACT, as required by the versioned tag-index design; prior tag-aware
COMPACT arms verify materialization and predicate visibility.

These probes narrow the next optimization target: connector cell dispatch and
larger sink batches are not currently the limiting factor. The remaining
INSERT gap is dominated by source/task execution plus the grouped primary
index/write path, while UPDATE is already in the lower hundreds-of-thousands
range on the exact shared-key route. Any subsequent change must retain the
tag-index-before-COMPACT semantics.

## Trino writer-count probe on the same hot target (2026-11-23)

The fixed target was extended from 7M to 11M rows using the same 68-column
source shape and exact primary-key checks after every append. Trino 466 does
not expose `task_writer_count`; the supported session controls are
`task_min_writer_count` and `task_scale_writers_enabled`. With
`task_min_writer_count=4` and scaling disabled, each INSERT produced four
Pixels files and one grouped primary-index RPC per file.

Three consecutive 1M-row four-writer appends took 8.052 s, 7.104 s, and
6.555 s, or approximately 124.2K, 140.8K, and 152.6K rows/s. The target
checks after these arms were exact. An eight-writer arm between them took
7.534 s, about 132.7K rows/s, so eight writers did not improve over four.
The four-writer arms had per-file index append work of roughly 0.2--0.9 s;
the grouped primary RPCs remained the larger per-file component and overlapped
across writers.

This is the first repeatable INSERT improvement on the current fixed wide
fixture: it raises the observed warm rate from the roughly 90--113K rows/s
single-writer controls into the 124--153K rows/s range. It is still below the
desired lower-hundreds-of-thousands-to-million target, and it remains an
opt-in session configuration pending a clean cold-start and persistence arm.
The main Trino runtime was not changed.

## 11M INSERT -> UPDATE -> tag-aware COMPACT E2E (2026-11-23)

The fixed target was extended to exactly 11,000,000 rows before the final
semantic run. The second exact-key two-column UPDATE used a valid `VARCHAR(10)`
tag value (`UPD1M`) and completed in 3.091 s SQL wall time, approximately
323.5K rows/s. The connector route reported `encoding_ms=70.932`,
`request_build_ms=202.594`, one 10MB shared RPC, `rpc_ms=790.084`, and
`total_ms=1063.757`. The daemon reported `decode_ms=222.382`,
`storage_ms=362.570`, and `service_ms=626.748`.

Tag-aware `COMPACT -c 8` then rewrote all 11M rows successfully:

```text
physical rewrite: 186.171 s
tag-index resolve: 11,000,000 rows, 689 RPCs, 150.643 s
primary relocation: 11,000,000 rows, 768 RPCs
metadata publish: 98.769 s
CLI wall: 316.312 s, rc=0
```

Post-COMPACT verification returned:

```text
total/min/max/distinct = 11000000/1/11000000/11000000
tag_1_type='UPD1M' = 1000000
tag_1_para=3.1400 = 1000000
tag predicate count = 1000000
sample ids 1,2,3 materialized as UPD1M / 3.1400
```

The isolated Trino coordinator was restarted with the same explicit
`PIXELS_HOME` and `PIXELS_CONFIG`. The post-restart checks returned the same
11M row count, ID range, distinct count, and 1M tag counts/predicate result.
This proves file/metadata visibility across a query-service restart without
touching the main runtime. Retina restart persistence remains covered by the
earlier clean 1M E2E restart arm; this current 11M arm deliberately avoids the
stale recovery-checkpoint path used by exploratory runs.

The COMPACT metrics also sharpen the bottleneck hypothesis: UPDATE itself is
already above 300K rows/s end-to-end on the warm exact-key path, whereas
materializing tags over a wide 11M table is dominated by tag-index resolution,
primary relocation, and metadata publication. INSERT remains the main gap to
the million-row target; the next code work should focus on grouped primary
index service cost and parallel source/task execution, while preserving the
tag-index-before-COMPACT contract.

After the post-COMPACT hot append, a source-ID offset mistake was detected:
the source used for that append already contained IDs 4,000,001--5,000,000,
so adding 11M created a valid but non-contiguous 15M--16M range. No duplicate
rows were committed. Four corrective 1M appends with offsets 7M, 8M, 9M, and
10M filled the missing 11M--15M ranges. The final authoritative check is now
`count/min/max/distinct = 16000000/1/16000000/16000000`, with the materialized
`UPD1M` tag still present on exactly 1M rows. The non-contiguous intermediate
check is excluded from throughput conclusions.

## 64-thread grouped-primary A/B and clean 1M tag-aware E2E (2026-11-23)

An isolated Retina restart tested the opt-in JVM property
`-Dpixels.index.grouped.primary.write.threads=64`. Earlier direct-generation
tables created with `LIKE` did not declare `pk='id'`; their 4.666--4.676 s
INSERT results measured a no-primary-index path and are explicitly excluded
from the comparison.

The valid arm used an explicit 68-column schema, a single table-root `paths`
property, and `pk='id'`. With four Trino writers and scaling disabled, a 1M
append under the 64-thread setting took 8.860 s, approximately 112.9K rows/s.
Two preceding warm appends on a primary-index table took 8.902 s and 8.826 s
(112.3K and 113.3K rows/s). The exact post-append check passed:

```text
count/min/max/distinct/null-tag = 1000000/1/1000000/1000000/1000000
```

This is below the accepted four-writer warm range of 124.2--152.6K rows/s.
The 64-thread property is therefore **rejected as a throughput optimization**;
it was not made the default and did not change the main runtime.

The same clean target then ran the supported tag path:

```text
UPDATE wall: 4.598 s for 1,000,000 rows (~217.5K rows/s SQL wall)
COMPACT -c 8: CLI wall 55.897 s, rc=0
physical rewrite: 18.519 s
tag-index resolve: 1,000,000 rows, 63 RPCs, 12.845 s
primary relocation: 1,000,000 rows, 128 RPCs
metadata publish: 9.934 s
```

Post-COMPACT checks passed exactly:

```text
count/min/max/distinct = 1000000/1/1000000/1000000
tag_1_type='UPD1M' = 1000000
tag_1_para=3.1400 = 1000000
combined tag predicate = 1000000
```

The experiment also exposed a recovery precondition. The isolated recovery
directory had a pointer to a missing checkpoint body; the retained pre-clean
backup restored service metadata but not the later 16M target visibility,
while the physical files remained on disk. A 32G Retina heap then failed during
recovered write-buffer initialization with `OutOfMemoryError`; a single 64G
isolated Retina started and served the E2E arm. This is a recovery/infrastructure
finding, not a throughput result. All main-runtime processes were unchanged.

The optimization decision is unchanged: UPDATE is already in the
lower-hundreds-of-thousands range on the supported tag route; INSERT remains
the gap to the million-row target. Next experiments should target grouped
primary-index/RocksDB write cost or task/source execution, with every INSERT
table explicitly declaring its primary key and path root before accepting a
number.

## Warm table-to-table INSERT JFR split (2026-11-23)

The clean tagged target from the previous section was used for two additional
warm table-to-table appends. The source was the same target with
`WHERE id <= 1000000`; the destination IDs were offset by 1M and 2M, so both
arms inserted one million new primary-key rows without changing the schema or
the tag columns. The isolated Trino used 32 writers with writer scaling
disabled, while the isolated Retina remained on the already-deployed
64-thread grouped-primary setting.

```text
append IDs 1,000,001..2,000,000: 6.863 s CLI wall, 145.7K rows/s
  grouped-primary: 1M entries, max request total ~= 1.60 s
  exact count/min/max/distinct after append = 2M/1/2M/2M

append IDs 2,000,001..3,000,000: 5.355 s CLI wall, 186.7K rows/s
  grouped-primary: 1M entries, max request total ~= 0.91 s
  exact count/min/max/distinct after append = 3M/1/3M/3M
```

The two numbers are warm-path probes, not a new fixed-source acceptance
baseline; they are retained to show the attainable range after compaction and
to avoid comparing against the earlier cold RocksDB-open path. The index
service metrics and RocksDB LOG show no write stalls or pending compaction
bytes during the probe. This makes a RocksDB background-stall explanation
unlikely for the current warm path.

A 6-second JFR on the isolated Trino during the second arm collected 554
execution samples. The most frequent relevant frames were:

```text
PixelsPageSink.appendPage                         161
PixelsPageSink.appendColumnValues                157
PixelsWriterImpl.writeColumnVectors              102
ScanFilterAndProjectOperator / PixelsFilePageSource 63 / 63
PixelsRecordReaderImpl.readBatch                  53
PixelsInsertIndexWriter.appendBatch               18
IndexServiceGrpc.putPrimaryIndexEntries           13
```

These are stack-sample counts rather than exclusive CPU percentages, but the
ordering is sufficient to change the next optimization priority. The warm
index path is no longer the dominant sampled Trino-side cost; PageSink vector
conversion, Pixels column encoding, and source/task scheduling are the next
targets. No code change is accepted from this probe. The next A/B must use a
fresh, materialized one-million-row source and paired fresh targets, and must
capture Trino query timeline metrics instead of relying only on CLI wall time.

## Per-column PageSink dispatch probe (2026-11-23)

The Trino JFR ordering suggested a small, reversible connector experiment:
`PixelsPageSink.appendColumnValues` previously called the generic per-cell
`appendValue` path, repeating the type-category dispatch for every value. The
candidate in `pixels-trino-dml` commit `014c86b` moved that dispatch outside
the row loop while preserving null handling, decimal width handling, heap-slice
retention, RLE handling, and the hidden timestamp column. It compiled on the
isolated JDK 23 build with test compilation enabled.

The candidate was deployed only to the isolated Trino after a connector
restart. The test reused the valid 68-column primary-key target and the same
one-million-row source projection as the preceding warm probe; destination
IDs were offset to avoid duplicates. Both arms passed exact row validation:

```text
candidate first arm: 7.147 s, count/min/max/distinct = 4M/1/4M/4M
candidate warm arm:  5.993 s, count/min/max/distinct = 5M/1/5M/5M
previous warm probe: 5.355 s for 1M rows (~186.7K rows/s)
```

The candidate warm arm was approximately 11.9% slower than the preceding
comparable warm probe. It is therefore **rejected** and is not a retained
optimization. The isolated connector was restored from its pre-deployment
backup; SHA-256 of the restored jar and backup is identical, and the final
post-rollback check remained `5000000/1/5000000/5000000`. The next connector
experiment should measure Pixels writer encoding and task finishing separately
before changing this hot loop again.

## Writer diagnostic stack reuse probe (2026-11-23)

`pixels-dml` commit `39fbdd78` changed `PixelsWriterImpl.writeColumnVectors` to
capture the caller stack once per row batch instead of once per column. The
stack is only formatted on the existing exceptional `ClassCastException` path,
so the change preserves diagnostics while removing repeated stack allocation in
the normal path. The candidate was rebuilt on the isolated JDK 11 Maven build,
deployed only to the isolated Trino plugin, and the original core jar was saved
outside the plugin directory for rollback.

The probe used the same explicit 68-column tagged table, 32 fixed Trino writers,
writer scaling disabled, and one-million-row table-to-table appends. The first
two candidate arms extended the clean target from 5M to 7M rows:

```text
candidate arm 1: 7.722 s, approximately 129.5K rows/s
candidate arm 2: 6.875 s, approximately 145.5K rows/s
candidate final exact check: 7000000/1/7000000/7000000
```

The connector was then restored to the original core jar and restarted in the
same isolated runtime. A post-rollback pair extended the table from 7M to 9M
rows:

```text
baseline arm 1 after restart: 11.331 s
baseline warm arm 2:          6.642 s, approximately 150.6K rows/s
baseline final exact check:    9000000/1/9000000/9000000
```

The first post-deployment arm is affected by restart/cache state, but the warm
comparison still shows the candidate about 3.5% slower than the paired baseline
arm; the candidate's first arm was also materially slower. Index metrics showed
normal successful writes and the RocksDB logs reported zero write stalls and
zero pending compaction bytes. There was no correctness regression, but there
was no repeatable throughput gain. The candidate is therefore **rejected** and
the isolated runtime remains on the original core jar. The code commit remains
in history as a measured negative result; future writer work should target
encoding/batch materialization with a profiler-backed hypothesis rather than
this diagnostic-only allocation.

## Rebuilt materialized-source INSERT baseline (2026-11-23)

The historical fixed-source table had survived recovery as metadata with zero
visible rows, so it was not usable evidence. A new independent source,
`bench68_v2.fixed_source_fresh_20261122`, was created through the connector
with the full 68-column schema and no primary index. It was materialized once
from the valid target and verified exactly as
`1000000/1/1000000/1000000`. It was not modified during the performance arms.

The destination remained the valid tagged target
`page_dispatch_target_clean64_e2e2_20261123`, with `pk=id`, a single-root path,
and the original core jar. Each arm inserted the fixed source with a distinct
one-million-ID offset, used `task_scale_writers_enabled=false`, and validated
the complete destination count, range, and distinctness:

```text
4 writers:  Trino timeline 6.850 s, approximately 146K rows/s
32 writers: Trino timeline 3.257 s, approximately 307K rows/s
32 writers warm repeat: Trino timeline 3.375 s, approximately 296K rows/s
final destination: count/min/max/distinct = 17000000/1/17000000/17000000
```

The query timelines also separate the scheduling shape: the 4-writer arm spent
about 5.176 s in finishing, while the two 32-writer arms spent about 1.604 s
and 1.548 s. This is the first clean, materialized-source E2E INSERT evidence
in the current runtime that reaches the lower hundreds-of-thousands target
range without changing the tagging or primary-index semantics. The 32-writer
setting is therefore retained as the fixed-source benchmark configuration, not
yet as a global deployment default; the result does not claim the million-row
target, and source-free/empty-table and self-source numbers must not be mixed
with it.

## Fixed-source 64-writer negative control (2026-11-23)

The same materialized one-million-row source and tagged primary-key target were
used for a 64-writer arm after the fixed-source 32-writer baseline. The arm
inserted a fresh ID range and passed the exact destination check:

```text
64 writers: Trino timeline 3.757 s, approximately 266K rows/s
final check for this arm: 18000000/1/18000000/18000000
```

This is slower than the two fixed-source 32-writer arms at 3.257 s and 3.375 s
(approximately 296--307K rows/s). The 64-writer timeline also spent about
2.026 s finishing versus 1.548--1.604 s for the 32-writer arms, while primary
index request tails increased as more files contended for the same grouped
service. The result rejects a higher Trino writer count as the next route to
the million-row target; 32 writers remains the fixed-source benchmark point.

## Selected-read null scratch probe (2026-11-23)

The stable INSERT JFR had `PixelsRecordReaderImpl.readBatch` and string-column
decoding near the top of the source-side samples. A code audit found that every
`readSelected` implementation allocated a fresh `boolean[size]` null scratch
array for each selected batch. Commit `986e15c2` changed these readers to reuse
one scratch array per `ColumnReader`, growing it only when a larger batch is
seen. No vector layout, null bit meaning, tag column, or on-disk format was
changed. The candidate compiled successfully in the isolated JDK 11 Maven
build.

The isolated E2E comparison used the explicit 68-column tagged target, 32 fixed
Trino writers, writer scaling disabled, and one-million-row table-to-table
appends. The original core jar first extended the target from 9M to 10M rows:

```text
original control: 5.677 s, approximately 176.1K rows/s
exact check: 10000000/1/10000000/10000000
```

After the candidate restart, the first arm was 11.266 s due to restart/cache
state; the warm arm extended the target from 10M to 11M rows in 5.946 s
(approximately 168.2K rows/s), with exact check
`11000000/1/11000000/11000000`. The connector was restored to the original
core jar and a post-restart control pair was run. Its warm arm took 5.995 s
(approximately 166.8K rows/s), with final exact check
`14000000/1/14000000/14000000`.

The candidate warm arm was only 0.8% faster than the paired post-rollback warm
control, while the preceding original arm was 5.677 s. This is not a repeatable
gain against the observed run-to-run variance, so the candidate is **rejected**
and commit `02b9c0a1` reverts it. The isolated runtime remains on the original
core jar. The probe is still useful: selected-read scratch allocation is a
real allocation hotspot worth measuring with an allocation-focused recording,
but it is not currently a throughput lever on this E2E fixture.

## Fixed-source index flush parallelism probe (2026-11-23)

The isolated Trino JVM normally uses
`-Dpixels.insert.index.flush.parallelism=4`. With the materialized source and
32 fixed writers held constant, an isolated configuration A/B temporarily
changed this value to 8 and then restored it to 4.

The `8` arm used fresh ID ranges and passed the exact destination check through
20M rows. Its first post-restart timeline was 8.022 s; the warm arm was:

```text
flush.parallelism=8: 3.862 s, approximately 259K rows/s
```

After restoring the original JVM configuration, the first arm again showed the
expected restart cost (7.927 s), and the paired warm control was:

```text
flush.parallelism=4: 3.876 s, approximately 258K rows/s
final destination: count/min/max/distinct = 22000000/1/22000000/22000000
```

The 0.4% difference is below the observed scheduling variance and neither arm
improved on the earlier 3.257--3.375 s fixed-source 32-writer runs. The probe is
therefore **rejected** as a throughput optimization; the isolated configuration
is restored to 4. The result reinforces that the next work should instrument
Trino finishing and grouped-index queueing together, rather than independently
raising executor parallelism.

## All-null page-column bulk append probe (2026-11-22)

The fixed 68-column source contains 58 columns that are NULL for all 1,000,000
source rows (the unpopulated tag pairs). The connector candidate in
`pixels-trino-dml` commit `f7d72cd` used the Trino `ValueBlock` null bitmap to
recognize a selected page range that was entirely NULL, then filled the target
Pixels vector's null bits with `Arrays.fill` and advanced its write index once.
Mixed-null, null-free, dictionary, RLE, and value/tag conversion paths were left
unchanged. The candidate was compiled with the isolated JDK 23 build and loaded
only by the isolated Trino instance. It was reverted by `207181e` after the
measurement.

The E2E test used the materialized
`bench68_v2.fixed_source_fresh_20261122`, the tagged indexed target
`bench68_v2.page_dispatch_target_clean64_e2e2_20261123`, 32 Trino writers,
`task_scale_writers_enabled=false`, and a fixed source ID offset per arm. The
candidate's fair warm arms were:

```text
all-null bulk candidate: 4.102 s, approximately 244K rows/s
all-null bulk candidate: 4.041 s, approximately 247K rows/s
```

After restoring the original connector jar, the comparable controls were:

```text
original after restart: 8.208 s, approximately 122K rows/s
original warm control:  3.866 s, approximately 259K rows/s
original warm control:  3.267 s, approximately 306K rows/s
```

The candidate is therefore **rejected**: its best fair arm was about 23.7%
slower than the final original warm control, and it did not improve the prior
3.257--3.375 s fixed-source baseline. The final exact table check was
`32000000/1/34000000/32000000`; the two unused ID ranges were caused by failed
HTTP session-header attempts and were not counted as successful arms. There
were no duplicate IDs. Tag preservation also held (`tag_1_type='UPD1M'`,
`tag_1_para=3.1400`, and the intentionally NULL `tag_2_*` columns remained
unchanged). The isolated runtime is back on the original connector jar.

Operational note: when restarting the isolated launcher from a non-interactive
SSH session, both JDK 23 and `PIXELS_HOME=/home/pixels/validation-million-update/runtime/pixels`
must be exported explicitly. Without them, the launcher first selected Java 11
and then the connector fell back to jar-bundled configuration; neither state is
valid benchmark evidence.

## Fixed-source index cost upper bound and writer sweep (2026-11-22)

The indexed and unindexed paths were compared with the same materialized
`fixed_source_fresh_20261122` and the same 68-column projection. A no-primary-key
target provides an upper bound for the file/source path but does not provide
tagging semantics:

```text
no-index target, first 1M: 3.269 s, approximately 306K rows/s
no-index target, warm 1M:  1.702 s, approximately 587K rows/s
indexed target, 24 writers: 3.257--3.279 s, approximately 305--307K rows/s
indexed target, 32 writers: 3.266--3.322 s, approximately 301--306K rows/s
```

The warm no-index target was intentionally allowed to receive the same source
twice, so its final check is `2000000/1/1000000/1000000`; this is an upper-bound
timing, not a uniqueness result. The indexed target remained unique and its
post-sweep check was `37000000/1/39000000/37000000`, with sampled tag values
still `UPD1M/3.1400` and the intentionally NULL tag pairs unchanged.

A client-writer sweep filled the missing point between the earlier 4/32/64
tests:

```text
16 writers: 6.248 s cold, 4.094 s warm
24 writers: 3.257 s, 3.279 s
32 writers: 3.266 s, 3.322 s
64 writers: 3.757 s (earlier fixed-source negative control)
```

The current sweet spot is therefore 24--32 writers. The unindexed upper bound
and the indexed comparison show that the primary-index path adds roughly
1.6 seconds on this warm fixture; changing the client writer count outside the
24--32 range does not close that gap.

## Retina-side JFR probe for indexed INSERT (2026-11-22)

The isolated Retina process was recorded with Java 11's `settings=profile`
configuration during a 24-writer indexed fixed-source INSERT. The query wrote
1,000,000 rows successfully in 3.523 seconds. The 118 execution samples are
not an exclusive CPU profile, but their stack shape is consistent with the
component metrics: the most frequent deep frames were
`MainIndexBuffer.snapshotForFlush`, `SqliteMainIndex.flushCache`,
`IndexServiceImpl.flushIndexEntriesOfFile`, and
`SqliteMainIndex.putEntries`; protobuf parse/build and
`IndexServiceImpl.putGroupedPrimaryEntries` were also present. This shifts the
next code audit toward main-index snapshot/flush batching and lock scope,
while retaining the primary grouped-write path and tag publication ordering.

No runtime configuration was changed by this probe. The indexed target was
left on the original connector and the isolated daemon configuration.

## Primitive row-id sort probe and restart durability finding (2026-11-22)

The Retina JFR showed `Long.valueOf`, `AbstractCollection.toArray`, and
`ComparableTimSort` beneath `MainIndexBuffer.snapshotForFlush`. The candidate
in commit `d2c4e6d6` keeps the existing HashMap and range-building semantics, but
copies keys into a primitive `long[]` and calls `Arrays.sort(long[])` instead
of materializing `Long[]`/`List<Long>` and invoking boxed TimSort. No tag,
visibility, primary-key, or file format behavior was changed.

The candidate was compiled into an isolated daemon full jar and deployed only
to the validation Retina. A fresh 68-column source with exactly 1,000,000
rows, `id=1..1,000,000`, `tag_1_type='SRC'`, `tag_1_para=1.0000`, and all
other tag pairs NULL was used with a new `pk='id'` target. The cold first
INSERT completed correctly but spent 174.032 s in the Trino timeline while
the grouped primary-index metrics showed approximately 170 s of first-use
RocksDB/index initialization; it is not a fair sort comparison. Two warm
candidate appends on the same target were:

```text
candidate warm append 1M: 3.443 s, approximately 290.4K rows/s
candidate warm append 1M: 3.295 s, approximately 303.5K rows/s
checks after 3M rows: count/min/max/distinct = 3000000/1/3000000/3000000
tag checks: tag_1_type='SRC'=3000000, tag_2_type IS NULL=3000000
```

These results are inside the existing 3.257--3.375 s original-connector
warm range and do not establish a repeatable gain. The candidate is therefore
rejected pending a lower-variance allocation-focused benchmark; the original
daemon jar is restored in the isolated runtime. The candidate code and the
revert remain in Git history with the measured result.

The restart used to restore the original jar also exposed a durability gap in
the current validation harness. Recovery reported `baselineFiles=0` and
`retired=1166`, dropping visibility entries whose catalog type was
`TEMPORARY_INGEST`. The previously visible validation source, no-index target,
and indexed target all returned zero rows after the restart, even though their
table definitions and physical paths remained. This is not a throughput
result and is not attributed to the primitive sort change. It means an
INSERT benchmark may not be called restart-durable until the post-INSERT file
metadata is confirmed `REGULAR` and the recovery checkpoint is confirmed to
contain the files. The next validation pass must include that check before
any further warm A/B comparison.

## Fixed-jar checkpoint persistence re-test (2026-11-22)

The previous restart failure was reproduced with the stale runtime jar: after
the 300-second GC cycle published
`recovery_cds20_0.bin`, the old same-timestamp cleanup path removed the body
and left the pointer unusable. The source fix in `pixels-dml` commit
`46d397c6` skips deletion when the displaced body path is the newly published
body path. A fresh isolated daemon full jar was built from the validation
branch and deployed only to the validation Retina:

```text
jar SHA256 = e549a70ef801421cb68e201cfcd10f1d58200bda7c6ad5777433e5031058a69c
```

With that jar, a new 68-column source and `pk='id'` target were created. The
source contained 10,000 rows, `tag_1_type='DUR'`,
`tag_1_para=2.0000`, and NULL values in the remaining tag pairs. The target
passed its pre-restart check. After the GC scheduler published a checkpoint
with `rgs=2754`, the body remained present at 1.8 MiB. Retina was then
restarted, recovered from the checkpoint, and explicitly completed the
isolated single-node `RECOVERING -> READY` handshake with
`RetinaService.markReady(recoveryEpoch)`.

The post-restart Trino check was exact:

```text
count/min/max/distinct = 10000/1/10000/10000
tag_1_type='DUR'       = 10000
tag_1_para=2.0000      = 10000
tag_2_type IS NULL     = 10000
```

This is the first clean restart-persistence result for a newly inserted
tag-capable table after deploying the same-timestamp checkpoint fix. It does
not change the INSERT throughput baseline, and it does not claim that the
manual READY handshake can be omitted in a production lifecycle.

## RunLenIntEncoder early-DELTA probe (2026-11-24, rejected)

The fixed-source INSERT JFR showed `RunLenIntEncoder.determineEncoding()` as
the leading Trino-side sampled method. The candidate moved ZigZag and
percentile computation after the DELTA checks, so fixed-delta and monotonic
runs could return without work that their selected encoding did not use. It
did not change the wire format, tag columns, primary-index route, or recovery
semantics. The candidate compiled successfully with the isolated JDK 11/23
builds and was deployed only to the isolated Trino plugin. The original core
jar was kept as a byte-for-byte rollback copy.

The new materialized source
`bench68_v2.e2e_alloc_source_20261124` contained exactly one million rows
(`id=100001..1100000`, distinct IDs) and was not modified during the arms.
The destination `bench68_v2.e2e_alloc_target_20261124` declared `pk='id'`,
used one table-root path, and every successful arm used 32 fixed Trino
writers with writer scaling disabled. All checks were exact and no duplicate
IDs were observed:

```text
candidate after connector restart: 7.054 s, IDs 22100001..23100000
candidate warm arm:               4.754 s, about 210.3K rows/s
candidate check after 3M rows:    3000000/20100001/25100000/3000000

original after connector restart: 9.289 s, 1M rows
original warm arm:                5.920 s, about 168.9K rows/s
original warm arms without restart: 5.366 s and 4.512 s,
                                    about 186.4K and 221.6K rows/s
original check after 7M rows:     5000000/20100001/29100000/5000000
```

The candidate JFR reduced the sampled `determineEncoding()` share to 15.7%
and sampled `percentileBits()` only four times. However, the comparable
original warm control still reached 4.512 s and its JFR retained 23.5%
`determineEncoding()` samples. The candidate's 4.754 s result therefore falls
inside the original run-to-run range and does not establish an E2E gain. The
candidate was rejected, its source was restored, and the isolated runtime
was restored to the original core SHA (`a1d514475f8467b9b10d153d14ff6b2df206cba9124f4af3bd2cf30ff2d4876d`).
The hotspot remains a valid follow-up target, but a future change must reduce
the complete writer/finishing path rather than only the local percentile
work.

## Projection isolation and synchronous column-write probe (2026-11-24, rejected)

To separate SQL projection cost from the Pixels writer, a second immutable
source, `bench68_v2.e2e_prescaled_source_20261124`, was materialized with the
ID offset already applied. It passed the exact source check
`1000000/40100001/41100000/1000000`. An indexed target consumed it with the
ordinary `INSERT INTO target SELECT * FROM source` shape. The first arm was
15.086 s because the new target opened its primary-index state; the warm arm
from a second pre-offset source was 5.009 s, about 200K rows/s. The target
check after both arms was exact:

```text
2000000/40100001/42100000/2000000
```

This overlaps the original-core `id + offset` warm range of 4.512--5.366 s,
so the projection expression is not the dominant gap on this fixture.

The JFR-guided writer candidate added an opt-in
`pixels.writer.column.write.parallel=false` mode that wrote the 68 column
vectors synchronously, leaving the existing cached-thread-pool path as the
default. It was deployed only to the isolated Trino. The candidate made the
next 1M-row source materialization run for more than two minutes, compared
with roughly 1--3 s for the original parallel path; no target E2E arm was
accepted. The candidate queries were cancelled, the source change was
reverted, and the isolated Trino/core jar were restored to the original
parallel implementation. This rejects a blanket synchronous column-write
strategy; any future writer scheduling change needs bounded per-batch
parallelism rather than serializing all columns.

## Main-index buffer `putIfAbsent` probe (2026-11-22)

The Retina JFR also showed `MainIndexBuffer.insert` together with
`HashMap.containsKey`, `HashMap.put`, and `HashMap.getNode`. Commit `8f24742d`
replaced the duplicate `containsKey` plus `put` sequence with
`HashMap.putIfAbsent`, preserving duplicate rejection and cache admission. The
change was built as daemon jar
`69e5a2e4de1e6fcf0daa00daa9ebf7e64c5fcf4c806101f90828e130611fd07b` and
deployed only to the isolated Retina. The original fixed-recovery jar was
restored after the A/B; the code probe was reverted by `8241f3f6`.

The first two candidate INSERTs were deliberately excluded because the client
did not send the fixed 32-writer session. With
`task_scale_writers_enabled=false,task_min_writer_count=32`, the candidate
used the same 68-column materialized source and tagged `pk='id'` target for two
one-million-row appends:

```text
candidate warm 1: 4.50 s, approximately 222K rows/s
candidate warm 2: 3.90 s, approximately 256K rows/s
```

After a checkpoint and restart, the original jar passed the recovery check and
provided the paired control. The first post-restart control was 27.3 s and was
excluded as cache warm-up. Subsequent controls were:

```text
original warm 1: 4.06 s, approximately 246K rows/s
original warm 2: 3.52 s, approximately 284K rows/s
original warm 3: 3.24 s, approximately 308K rows/s
```

The candidate did not beat the stable original warm range and was slower than
the last two controls. The probe is therefore **rejected** and reverted. The
final exact check after all accepted appends was
`count/min/max/distinct = 8010000/1/11100000/8010000`, with
`tag_1_type='PIA'` on 8,000,000 rows and `tag_2_type IS NULL` on 8,010,000
rows. No correctness regression was observed; the result is a negative
throughput finding, not a change to the INSERT baseline.

## Table-scoped 2M INSERT -> UPDATE -> COMPACT and checkpoint-watermark probe (2026-11-22)

After enabling the already-existing opt-in `index.rocksdb.table.scoped=true`
on the isolated Retina, a fixed 68-column target
`bench68_v2.table_scoped_e2e_target_20261122` was populated from the
materialized one-million-row source in two arms. The final pre-compaction
INSERT check was exact:

```text
count/min/max/distinct = 2000000/100001/3100000/2000000
tag_1_type='PIA'       = 2000000
tag_2_type IS NULL     = 2000000
```

The second arm then ran the supported two-column tagging UPDATE for one
million keys. SQL wall time was 2.045 s, approximately 489K rows/s. As
required by the versioned tag protocol, the pre-COMPACT read still showed the
old materialized tags. Tag-aware `COMPACT -c 8` completed in 98 s, including
physical rewrite 78.2 s, tag resolve 26.7 s, and publish 20.5 s. Post-COMPACT
checks passed exactly: 2M rows, unique IDs, 1M rows with
`tag_1_type='SCOPED'`, 1M rows with `tag_1_para=3.1400`, and the other 1M
rows retained `PIA`.

The first restart gate failed for a reason separate from the UPDATE or
table-scoped RocksDB implementation. The checkpoint published before the
compact file was visible to recovery, so the restarted fixed jar reported
`baselineFiles=197, retired=2` and the target read zero rows. This is retained
as a negative durability sample; it is not counted as a successful E2E result.

The checkpoint investigation found that the isolated transaction service
returned `safeTrue=0` but `safeFalse=300670`. The writer high watermark had
advanced, while the read-only low watermark remained at zero. A read-only
`beginTrans` call also failed in this runtime, whereas a normal begin/commit
advanced the writer watermark to 300671. Therefore the 300-second checkpoint
cycle could not safely publish a newer checkpoint when it included running
queries, even though `/v1/query` showed no active Trino query.

### Opt-in watermark experiment

The code now has an explicit opt-in property:

```properties
retina.gc.include.running.queries=true
```

The default remains `true`. When an isolated test has independently proved
that no long-running readers are present, setting it to `false` makes GC use
the writer-safe watermark. This is a diagnostic/durability switch, not a
production-default change: ignoring a real running reader would be unsafe.

With the property set to `false` and the GC interval temporarily set to 10 s,
an isolated opt-in jar published checkpoints at `checkpointAppliedTs=300671`
and `300675`. The opt-in jar SHA256 was
`72b15257ab5e3d0c1b26e7a3985493eb5203ad81fdd510333c7b39d91a799686`. A new table
`bench68_v2.table_scoped_optin_checkpoint_20261122` then ran 100K INSERT,
10K tagging UPDATE, and tag-aware COMPACT. The pre-restart materialized check
was exact:

```text
count/min/max/distinct = 100000/100001/200000/100000
tag_1_type='OPTIN'     = 10000
tag_1_para=8.8800      = 10000
```

After the opt-in Retina was restarted and marked READY, the same exact check
passed. The prior stale-checkpoint table remained at zero, demonstrating that
the result is specifically attributable to publishing a newer checkpoint and
not to a query-cache artifact. The runtime was then restored to the original
fixed jar and original configuration (`retina.gc.interval=300`, default
`includeRunningQueries=true`, and table-scoped mode unset).

Decision: retain the property and the evidence, but do not enable it by
default or claim the 2M table-scoped arm restart-durable under the current
low-watermark behavior. The next durable fix should make the transaction
service advance the read-only low watermark for completed autocommit work, or
provide a lifecycle-safe checkpoint trigger; then rerun the full 2M gate with
the conservative `includeRunningQueries=true` setting.

## Trino autocommit read classification and restart-durable INSERT (2026-11-22)

The next probe followed the history to commit
`2977640b85794652a11151a5e3ac39947a73bd9f`. That commit deliberately changed
Trino to start every transaction with `beginTrans(false)`, because Trino's
read-only flag was unreliable and a fresh-table UPDATE otherwise received
timestamp zero. The side effect is that the daemon's immutable transaction
classification stayed `readOnly=false` even for ordinary SELECT statements,
so `commitTrans(..., true)` could still push the writer high watermark rather
than the reader low watermark.

The accepted fix keeps the non-zero start timestamp and adds an explicit
end-of-life reclassification path:

1. `PixelsTransactionHandle` records whether connector metadata entered a
   write path (`beginInsert`, `applyUpdate`, MERGE, CREATE TABLE, or DROP
   TABLE).
2. On commit or rollback, a handle with no write operation calls the new
   reserved transaction property `trans_read_only=true` before termination.
3. `TransContextManager` atomically moves the pending context from the write
   set to the read-only set and updates the mutable classification. A write
   transaction is never reclassified after it has started committing.

The reserved property is daemon-side and therefore does not depend on Trino's
unreliable transaction flag. The conservative default
`retina.gc.include.running.queries=true` was retained.

### Isolated E2E evidence

The candidate was compiled with targeted `javac` on cds20 and deployed only
to the isolated validation runtime. The active candidate SHA256 values were:

```text
pixels-trino-connector-0.2.0-SNAPSHOT.jar
d737a3118a479d2ff4b63f84168b16fb5e0b78145ae8b0e6e7fc9ae5695fdeb5
pixels-common-0.2.0-SNAPSHOT.jar
bff59fe6d4dbb1ae5ede9df53843b96ac056cc9e5d46815dc21cafbf204ace1f
```

With the old daemon, the control SELECT left `safeTrue=300682` and advanced
`safeFalse` from `310034` to `330001`. With the candidate daemon and candidate
connector, the same SELECT moved `safeTrue` from `300682` to `330001`, and the
runtime published `recovery_cds20_330001.bin`. This isolates the fix to
completed read-only transaction classification rather than a GC setting.

For restart durability, a fresh 68-column no-index table
`bench68_v2.lowwm_e2e_target_20261122` was created with one explicit root
path. A separate table-to-table INSERT copied 100,000 rows from
`putifabsent_source_20261122`:

```text
INSERT result = 100000
pre-restart count/min/max = 100000/100001/200000
safe watermark before read = 340013/340013 (low/high safe values)
checkpoint published = recovery_cds20_340013.bin
checkpoint baselineFiles = 218
```

Retina was then restarted with the candidate daemon classes, recovered
`recovery_cds20_340013.bin`, and was marked READY through the normal recovery
epoch handshake. The post-restart query returned exactly:

```text
count/min/max = 100000/100001/200000
```

This closes the previously failing low-watermark durability gap for ordinary
Trino autocommit reads while preserving the original non-zero timestamp
behavior needed by UPDATE. The change is accepted for source control; the
isolated runtime remains the validation target and the main runtime was not
modified.

## Main-index duplicate-lookup microprobe (2026-11-22, rejected)

The retained JFR evidence points at `MainIndexBuffer` and the SQLite main-index
flush path, so a minimal candidate changed the existing duplicate check from
`containsKey(rowId)` followed by `put(rowId, location)` to one `get(rowId)`
followed by `put`. The owning main-index write lock already protects this
sequence, and `RowLocation` values are non-null, so the candidate preserved
the duplicate and cache-admission semantics.

The same materialized one-million-row source and one explicit `pk='id'`
target were used throughout. The original implementation first built the
target and then appended IDs 1,000,001--2,000,000 in 4.095 s. After the
candidate daemon restart, the first append was a restart/cache warm-up at
14.008 s and was excluded; the next two candidate warm appends took 3.857 s
and 3.220 s. The exact check after 5M rows was
`count/min/max/distinct = 5000000/100001/5100000/5000000`.

The daemon was then restarted back to the original implementation after a
checkpoint, and two control warm appends took 3.072 s and 4.095 s. The final
control check passed exactly:
`count/min/max/distinct = 8000000/100001/8100000/8000000`.

The candidate range is inside the control's observed scheduling/cache range;
there is no repeatable throughput gain. The code was reverted and the
isolated runtime remains on the original implementation. Decision: **reject**
this micro-optimization. The next main-index work should target measured
SQLite flush/lock behavior or a larger end-to-end bottleneck, not individual
HashMap lookup count.

## Correct grouped-primary property, p8 rejection, and fresh tag UPDATE JFR (2026-11-22)

The isolated Trino process had previously been started with
`-Dpixels.index.grouped.primary.write=true`. That is a daemon property, not the
connector switch. The connector reads
`pixels.insert.index.grouped.primary.write` from
`PixelsInsertIndexWriter.GROUPED_PRIMARY_WRITE_PROPERTY`. The old process
therefore still issued 128 primary-index RPCs per file. This was corrected only
in the isolated runtime; no production default was changed.

### Grouped-primary E2E A/B

The fixed materialized source
`bench68_v2.e2e_alloc_source_20261124` remained exactly one million rows, and
the destination declared `pk='id'` with one table-root path. Every arm used 32
Trino writers through:

```text
task_scale_writers_enabled=false,task_min_writer_count=32
```

The prior non-grouped warm arm completed in 4.978 s (about 201K rows/s) and
logged `primary_rpc_count=128` per file. With the corrected connector property
and `pixels.insert.index.flush.parallelism=4`, the first arm completed in
6.141 s after the isolated Trino restart, then a same-configuration warm arm
completed in 3.610 s (about 277K rows/s). The warm grouped arm logged 32 files,
32 primary RPCs, one RPC per file, and the target check remained exact at
11,000,000 rows with distinct primary keys. In the first corrected arm, the
32 grouped writes summed to about 22.2 s of primary-put wall intervals while
the final main-index flushes summed to about 1.22 s. This confirms that RPC
count was reduced, but the grouped request does not automatically remove the
server-side index work or its contention.

The follow-up arm used `pixels.insert.index.flush.parallelism=8`. The first
attempt was invalid because a hand-written restart command omitted the existing
`--add-opens=java.base/java.nio=ALL-UNNAMED` runtime requirement; it failed
before reading a source row and the target was unchanged. After restoring the
complete command, the cold arm took 9.134 s and the warm arm took 3.859 s
(about 259K rows/s). The corrected warm result is below the p4 warm result, so
parallelism 8 is rejected. The isolated runtime was restored to p4 with the
correct connector property, grouped max entries 200,000, and daemon grouped
threads 32.

Decision: retain grouped-primary as an experimental, explicitly configured
route because the one-RPC-per-file behavior is real and correctness passed,
but do not make it a default optimization claim. The current fixed-source
INSERT acceptance point remains the best repeatable warm result rather than a
million-row result. Further gains must come from reducing grouped server-side
RocksDB/main-index work or source/task overhead, not just reducing RPC count or
raising client flush parallelism.

### Fresh supported 1M-row UPDATE and Retina JFR

On the same 13-million-row target, three disjoint one-million-row exact-key
updates changed two tag columns per row. All three returned exactly 1,000,000
updated rows. The first two stable arms were:

```text
SQL wall 1.997 s  = 501K rows/s
SQL wall 1.308 s  = 763K rows/s
```

For both arms, the route log reported `executor=MPP`,
`exact_eligible=true`, one primary-key domain, and `rpc_count=1`; no merge
fallback metric appeared. The faster arm's connector metric was
`encoding_ms=51.792`, `request_build_ms=206.024`, `rpc_ms=879.939`, and
`total_ms=1137.827`, with a roughly 10 MB request. This keeps the supported
tagging UPDATE route above the minimum target and near the stretch target on a
warm exact-key run.

A short JFR around a further 1M-row UPDATE wrote
`runtime/jfr/update-e2e-1m-20261124.jfr` (3.9 MB). Its Java samples were
consistent with the route metric: the visible application frames were
`IndexServiceImpl.appendSharedTagIndexEntries`, `PixelsTagIndex.appendBatch`,
`PixelsTagIndexCodec.encode`, and `PixelsTagIndex.estimateValueBytes`. The
recording did not show a new dominant blocking subsystem or justify a code
change; the current UPDATE implementation and tag-before-COMPACT semantics
remain unchanged.

### Grouped flush-parallelism sweep (2026-11-22)

With the corrected grouped-primary property, the fixed-source 1M-row arm was
repeated at `pixels.insert.index.flush.parallelism=2`. The first post-restart
arm took 9.522 s and the same-configuration warm arm took 4.357 s
(approximately 230K rows/s). The warm p2 result is below the p4 warm result of
3.610 s (approximately 277K rows/s), while p8 was 3.859 s (approximately
259K rows/s). The three-point sweep therefore has a clear local optimum at
p4 for this 32-writer, 68-column fixture:

```text
grouped flush parallelism 2: 4.357 s warm
grouped flush parallelism 4: 3.610 s warm
grouped flush parallelism 8: 3.859 s warm
```

Decision: reject p2 and p8 as the next optimization direction and restore the
isolated runtime to grouped p4. The remaining INSERT gap is not explained by
this client-side flush fan-out knob; future work should measure or reduce the
server-side grouped RocksDB/main-index path and source/task scheduling.

The daemon-side grouped metrics support that direction. For the same target
index (`table_id=2292`, `index_id=2243`), the 32 per-file requests averaged
approximately:

```text
                 primary_ms   main_index_ms   total_ms
p4 cold              586.6          35.7        622.3
p4 warm              314.8          27.3        342.1
p8 warm              303.8          18.8        322.6
p2 warm              468.9          50.0        518.9
```

These are service-side averages, not SQL wall rates. They show that the main
index contribution is small relative to the grouped primary write, and that
p2 increases primary-write queueing even though it reduces client fan-out.
The p8 service average is not enough to win end-to-end because source/task
and publication scheduling also contribute to SQL wall time.

## Cross-CF single-WriteBatch probe (2026-11-22, rejected)

The grouped service path already combines all vnode entries into one RPC, but
then calls `RocksDB.write()` once per vnode column family from the grouped
executor. Because the isolated deployment has `index.cache.enabled=false`, an
opt-in candidate was compiled to put all 128 vnode CF batches from one grouped
request into a single cross-CF RocksDB `WriteBatch`. The default path and the
cache-enabled path were left unchanged.

The candidate compiled with targeted `javac` on cds20 and was deployed only by
putting the two candidate daemon classes first on the isolated Retina
classpath. Recovery of `recovery_cds20_360092.bin` completed, and a 15M-row
count read passed before the INSERT. The E2E arm produced 32
`PIXELS_PRIMARY_INDEX_GROUPED_BATCH` markers, confirming that the candidate
path was actually used. It then performed very poorly:

```text
single cross-CF WriteBatch, 1M rows: SQL wall 40.330 s
candidate primary_ms: approximately 32--35 s
normal grouped p4 warm control: SQL wall 3.610 s
```

The single native write serialized work that the normal per-CF writes overlap;
it is rejected. The candidate classes were removed from the local and remote
validation source copies, and the isolated Retina was restored to the original
low-watermark classpath.

The candidate arm was not used as a durability result: Retina was restarted
after the arm without first publishing a new checkpoint, so recovery correctly
replayed the last checkpoint and did not expose the uncheckpointed post-arm
state. After rollback, the durable target check was exactly
`15,000,000/20,100,001/42,100,000/15,000,000` for count/min/max/distinct.
This is a valid rollback/integrity check, not evidence that the candidate
lost committed data. Decision: **reject** the single-WriteBatch design and
keep the existing per-vnode grouped writes.

### Lock-free follow-up to isolate the monitor effect

The first candidate held the `RocksDBFactory` class monitor across the native
write. Since that could itself serialize the 32 grouped RPCs, a follow-up
removed the monitor while keeping the same cross-CF batch shape and reran the
fixed-source arm. It produced 32 batch markers and an exact pre-restart check:

```text
SQL wall = 12.513 s
target = 16,000,000/20,100,001/43,100,000/16,000,000
```

The result is still far below the normal per-vnode grouped p4 warm result of
3.610 s. Its per-request `primary_ms` rose through roughly 5.9--8.0 s, so the
large cross-CF WriteBatch itself remains the limiting shape even without the
Java monitor. The follow-up was rolled back as well. Recovery from the
previous checkpoint then returned the original target exactly to
`15,000,000/20,100,001/42,100,000/15,000,000`; the uncheckpointed 16M arm is
not used as a durability claim.

Final decision: **reject** both the synchronized and lock-free cross-CF
single-WriteBatch variants. Keep the source tree and isolated runtime on the
original per-vnode grouped implementation.

## RocksDB WAL upper-bound probe (2026-11-22, rejected as an optimization)

The active isolated runtime was copied to two fresh, explicit `pk='id'`
68-column probe tables. Both used the same materialized one-million-row source
and the fixed 32-writer session. The normal configuration kept
`index.rocksdb.disable.wal=false`; a second Retina restart used only the
temporary setting `index.rocksdb.disable.wal=true`. This was an upper-bound
measurement, not a proposed default, because disabling WAL would weaken the
INSERT crash-recovery contract.

The SQL wall results were:

```text
WAL enabled:  first arm 4.440 s, warm arm 2.816 s (~355K rows/s)
WAL disabled: first arm 6.117 s, warm arm 2.721 s (~367K rows/s)
```

The warm difference is only about 3% and is much smaller than the observed
run-to-run source/task and index-initialization variation. Therefore WAL is not
the dominant cause of the current INSERT gap. The candidate configuration was
not retained: `pixels.properties` was restored to
`index.rocksdb.disable.wal=false`, and Retina was restarted on the original
grouped-primary classpath.

This probe also fixed an important durability-test procedure. The probe arms
were intentionally not followed by checkpoint publication before the restart.
After recovery from `recovery_cds20_360092.bin`, the existing validation target
returned exactly to its checkpoint state
`15,000,000/20,100,001/42,100,000/15,000,000`, and both new probe tables had
zero visible rows. This is expected replay-to-checkpoint behavior, not
evidence of a WAL regression or data loss from a committed checkpoint. Future
performance arms that need a post-restart durability assertion must publish a
checkpoint first; pre-checkpoint performance-only arms must not be reported as
durability tests.

Decision: **reject disabling WAL** as a production optimization and keep WAL
enabled. Continue investigation in the server-side grouped primary write and
source/task scheduling paths.

## Warm INSERT JFR: grouped-RPC decode and re-bucketing are the next code target (2026-11-22)

To separate the remaining Java/service work from storage configuration, a new
explicit-primary-key 68-column probe table was warmed once and then measured
with a second 1M-row INSERT under the normal WAL-enabled grouped-p4 runtime.
The warm SQL wall was 3.719 s. Retina recording
`runtime/jfr/insert-e2e-warm-20261122.jfr` lasted 5 seconds and contained 261
`jdk.ExecutionSample` events.

The most frequent leaf samples were:

```text
57  IndexServiceImpl.putGroupedPrimaryEntries
47  PutPrimaryIndexEntriesRequest.Builder.mergeFrom
25  PrimaryIndexEntry.Builder.mergeFrom
19  java.util.HashMap.getNode
14  sun.security.provider.DigestBase.engineUpdate
7   SqliteMainIndex.putEntries
```

Across complete sampled stacks, protobuf request/entry parsing accounted for
the largest repeated group (`PutPrimaryIndexEntriesRequest.parsePartialFrom`
242 samples; `PrimaryIndexEntry.parsePartialFrom` 144), followed by
`IndexServiceImpl.putGroupedPrimaryEntries` and
`IndexUtils.getBucketIdFromByteBuffer`. The samples are not a wall-time
profile, but they are consistent with the service implementation: the client
already knows each entry's bucket, while the grouped RPC sends mixed buckets
and the daemon hashes every key again into a `HashMap` before dispatching to
the per-vnode RocksDB writers.

The next candidate is therefore a protocol-level grouped request that carries
the already-resolved vnode/bucket grouping (or an equivalent per-entry bucket
sidecar), allowing the daemon to skip repeated key hashing and generic
re-bucketing while retaining one RPC per Trino writer and the existing
per-vnode `WriteBatch`/WAL semantics. This is only a candidate: it must be
benchmarked against the current 32-writer grouped-p4 path and must preserve
abort bookkeeping, tag publication ordering, and the explicit primary-key
correctness checks. No code change was made from this JFR alone.

### Client vnode-sidecar A/B (2026-11-22, rejected)

The JFR-derived candidate was implemented as an opt-in protocol field
`virtualNodeIds`, parallel to `indexEntries`. The Trino writer populated it
from the bucket map it already maintains, and the daemon used it only for
grouped requests while retaining the existing per-vnode RocksDB writes. The
candidate compiled successfully in `pixels-common`, `pixels-daemon`, and the
Trino connector; the full reactor was not usable on cds20 because its
unrelated `pixels-retina` native build requires `cmake`.

The candidate was deployed only to the isolated runtime and compared using a
fresh explicit-primary-key 68-column target, the fixed materialized source,
and 32 writers:

```text
candidate cold after restart: 31.021 s
candidate warm:                4.068 s (~246K rows/s)
normal grouped-p4 warm:        2.816--3.610 s (~277--355K rows/s)
```

The cold arm was dominated by post-restart index initialization and is not
used for the decision. The warm arm was still slower than the normal path,
despite avoiding server-side SHA-256 bucket calculation. The extra repeated
protobuf field and its parsing cost outweighed the saved hash/re-bucketing
work in this workload. The candidate's target passed the exact physical/logical
check after two arms:
`2,000,000/100,001/1,100,000/1,000,000` for count/min/max/distinct.

The isolated daemon and connector were then restored from the deployment
backup, and the Trino JVM was restarted without the sidecar property. The
candidate source changes were reverted locally. Decision: **reject** the
parallel vnode-sidecar design in its current form; retain the existing grouped
RPC and investigate a lower-overhead wire representation or a larger-granularity
server API only if a future probe can remove protobuf overhead as well as the
hashing.

### Contiguous vnode-range follow-up (2026-11-22, rejected)

The sidecar follow-up reduced the wire metadata to at most one range per
bucket: the client sent `(virtualNodeId, entryOffset, entryCount)` for the
already bucket-contiguous entries, and the daemon used `subList` ranges instead
of hashing each key. Chunk-boundary handling was implemented and checked so a
bucket split across requests is merged rather than overwritten. The candidate
was compiled and deployed only to the isolated runtime.

On the same 68-column source and fixed 32-writer session, the results were:

```text
range candidate cold after restart: 27.896 s
range candidate warm 1:              4.031 s (~248K rows/s)
range candidate warm 2:              4.234 s (~236K rows/s)
normal grouped-p4 warm:              2.816--3.610 s (~277--355K rows/s)
```

After two arms, the target check was exact:
`2,000,000/100,001/1,100,000/1,000,000` for count/min/max/distinct. The
range metadata removed the per-entry vnode field but still lost end-to-end;
the remaining protobuf decoding, object construction, request scheduling, and
per-vnode service work dominate the saved hash loop. The runtime was restored
from the deployment backup with the original grouped-p4 properties and the
candidate source was reverted. Decision: **reject** the contiguous range
protocol as well; the next code experiment must reduce the nested protobuf
entry representation or the number of service-side request objects, not only
the bucket lookup.

### Client-side grouped bookkeeping follow-up (2026-11-22, rejected)

The existing grouped writer already keeps `pendingEntriesByBucket`, so the
client can recover the successful keys for abort cleanup from the bucket
sections that were sent. The candidate changed only this bookkeeping path: it
used the known chunk/bucket intersections instead of hashing every completed
entry again with `bucketIdResolver`. No daemon or protobuf change was made,
and the implementation accounted for a bucket split by the grouped
`max.entries` chunk boundary.

The candidate was compiled and deployed only to the isolated Trino runtime.
Using the same materialized 1M-row, 68-column source, explicit primary key,
fixed 32-writer session, and normal grouped-p4 properties, the target passed
the two-arm correctness check exactly:
`2,000,000/100,001/1,100,000/1,000,000` for count/min/max/distinct. The
end-to-end SQL wall times were:

```text
candidate cold after restart: 9.490 s (~105K rows/s)
candidate warm 1:              3.998 s (~250K rows/s)
candidate warm 2:              3.975 s (~251K rows/s)
candidate warm 3:              3.106 s (~322K rows/s)
normal grouped-p4 warm:        2.816--3.610 s (~277--355K rows/s)
```

Even after the post-restart effect was removed, the candidate did not beat the
normal path consistently and had no compensating protocol or server-side
benefit. It was rolled back from the isolated Trino plugin backup; the daemon
and Retina were not changed. Decision: **reject** this bookkeeping-only
optimization as an end-to-end throughput improvement. The abort bookkeeping
still merits a focused unit test if this path is revisited, but it is not the
current INSERT bottleneck. The next useful experiment should target grouped
request decode/object allocation or service-side request scheduling with a
measurement that can isolate those costs.

### Compact vnode sidecar follow-up (2026-11-22, rejected)

The previous sidecar probe used `repeated uint32`, which added a protobuf
element and parsing overhead for every row. This follow-up kept the same
grouped request but added an opt-in `bytes virtualNodeIds` field: one unsigned
byte per entry, valid for the current 128-bucket layout. The daemon used the
sidecar when its length and bucket range were valid, otherwise falling back to
the original SHA-256 bucket calculation. The field was backward-compatible and
the property was opt-in (`pixels.insert.index.grouped.primary.write.compact.vnode`).

The first deployment exposed and fixed a packaging-only issue: the connector
jar had been updated without the matching `pixels-common` dependency, causing
`NoSuchMethodError` before any rows were written. That run is excluded. After
deploying the complete connector ZIP, the fixed-source E2E produced:

```text
compact sidecar: cold 9.050 s; warm 4.535 s, 3.731 s, 3.346 s
control (original connector): cold 7.217 s; warm 3.980 s, 3.520 s, 3.418 s
```

Both tables passed the exact primary-key check after four 1M-row arms:
`4,000,000/100,001/1,100,000/1,000,000`. The daemon grouped metrics covered
the same 4M rows and 128 requests per arm: the compact-sidecar table summed
about 90.5 s of `primary_ms`, versus about 69.8 s for the immediate control.
These service sums overlap concurrent requests and are diagnostic rather than
SQL wall time, but they do not show a server-side win. The SQL results likewise
show only run-to-run overlap, not a stable improvement over the original
grouped path.

The compact sidecar and daemon overlay were removed from the isolated runtime;
the original connector, daemon jar, and grouped-p4 properties are active again.
Decision: **reject** the compact vnode sidecar as an INSERT throughput
optimization. The remaining target is not the SHA-256 bucket lookup alone;
future work should reduce grouped protobuf entry/object overhead or improve
the per-vnode RocksDB write path, with a same-process control captured in the
same run.

### Server-side array bucketing follow-up (2026-11-22, rejected)

To isolate the small `HashMap.getNode` signal from the JFR, the grouped daemon
path was changed only in the isolated runtime to use a fixed 128-slot array of
bucket lists. RPC shape, SHA-256 bucket assignment, RocksDB `WriteBatch`, WAL,
and the 32-thread executor were unchanged. This was intended as a low-risk
allocation/indexing probe, not a semantic change.

On the same fixed source and 32-writer session, the candidate completed three
1M-row arms with exact checks after each arm:
`3,000,000/100,001/1,100,000/1,000,000`. SQL wall times were:

```text
array bucketing: cold 5.013 s; warm 3.617 s, 3.625 s, 3.075 s
immediate original control: cold 7.217 s; warm 3.980 s, 3.520 s, 3.418 s
```

The isolated service metric covered four arms/4M rows for both comparable
tables: the array candidate summed about 83.2 s of `primary_ms`, while the
original control summed about 69.8 s. The one 3.075 s arm is therefore treated
as run-to-run variance, not a reproducible improvement. The overlay was
removed and the original daemon jar restored. Decision: **reject** array
bucketing as a standalone optimization; the next probe must address the
RocksDB write/flush path or the grouped request lifecycle rather than the
container used for re-bucketing.

## Fixed-source writer-count sweep below 32 (2026-11-22)

The grouped p4 runtime was kept unchanged while the Trino session writer count
was varied on the same explicit-primary-key 68-column source. The session used
`task_scale_writers_enabled=false` and set `task_min_writer_count` to the test
point. A 16-writer cold arm took 10.231 s; its warm arm took 3.797 s
(approximately 263K rows/s). Two 24-writer arms took 3.956 s and 3.745 s,
respectively (approximately 267K rows/s on the warm arm). These are both below
the established 32-writer warm arm at 3.610 s (approximately 277K rows/s).

All arms completed and the fixed target retained unique primary keys. Decision:
keep 32 as the reproducible writer-count point for this fixture and reject
further writer-count tuning as the immediate path to the million-row target.

## Compression checkpoint gate and client bookkeeping probe (2026-11-22)

The isolated Retina was restored to `index.rocksdb.compression.type=
LZ4_COMPRESSION` after a NO_COMPRESSION exploratory run. Four fresh LZ4
targets used the fixed 68-column, 1M-row source and the fixed 32-writer
session. Their SQL checks were exact:
`1,000,000/100,001/1,100,000/1,000,000` for every target. The observed
SQL walls were:

```text
LZ4 control: 5.655 s (cold), 3.822 s, 3.760 s, 3.711 s (warm)
```

The earlier NO_COMPRESSION table was not checkpointed before the Retina
restart and returned zero visible rows afterward. Its wall times therefore
remain a heat-only observation, not a durable comparison. A first aggregate
over both `pixels-daemon.log` and `retina.out` appeared to show twice the
grouped request count; those files duplicate the same Retina log lines. The
canonical daemon log has 32 requests/1M entries per LZ4 table, matching the
exact SQL checks. Decision: **do not change the compression default**; rerun
only with a checkpoint-backed NO_COMPRESSION arm if compression is revisited.

The next small candidate addressed a concrete client-side duplicate: grouped
INSERT computed the vnode hash while appending each entry and then computed
the same hash again in `recordCompletedGroupedEntries()` after a successful
RPC, solely to populate abort bookkeeping. The candidate carried a primitive
bucket id array alongside the flattened grouped entries, preserving the
protobuf request, tag/index semantics, abort bookkeeping, and RocksDB path.
It compiled successfully on cds20 with the connector Maven build and was
deployed only to the isolated Trino. Every candidate target passed the exact
1M-row check:

```text
candidate: 6.984 s (cold), 5.200 s, 4.104 s, 4.991 s (warm)
immediate original LZ4 controls: 5.655 s, 3.822 s, 3.760 s, 3.711 s
```

The candidate has no repeatable end-to-end gain and was slower across the
warm set. The original plugin was restored from
`runtime/logs/duplicate-bookkeeping-20261122/plugin-before`; the source
change was reverted locally and in the isolated source copy. Decision:
**reject** this bookkeeping-only optimization. It confirms that the current
INSERT gap is not explained by this second SHA-256 pass.

During the same fixed-source profile arm, a `/proc` sampler observed a
4.082-second 1M-row INSERT at approximately 245K rows/s. Trino and Retina
each accumulated roughly 20--22 CPU-seconds on the 80-thread host (about
five cores each), while system load stayed below 0.3. This is diagnostic
evidence against whole-host CPU saturation; the next accepted probe should
instrument or tune grouped RocksDB write/flush scheduling and reconcile its
per-request counters, not simply add client writers.

## RocksDB WriteBatch preallocation probe (2026-11-22, rejected)

The grouped primary path creates one RocksDB `WriteBatch` per vnode bucket.
An opt-in Retina-only overlay constructed `WriteBatch(reservedBytes)` with
`64 * entries` reserved bytes, controlled by
`pixels.index.rocksdb.write.batch.reserve.bytes.per.entry=64`. The default
constructor remained unchanged in the source and the overlay was placed ahead
of the full daemon jar only on the isolated Retina. The full Maven build was
not available because the host has no `cmake`; targeted `javac` compilation of
`RocksDBIndex.java` succeeded, and the overlay was removed after the probe.

On the same 68-column, 1M-row source and 32-writer session, all four target
checks were exact:
`1,000,000/100,001/1,100,000/1,000,000`. SQL timelines were:

```text
reserved WriteBatch: 7.835 s (cold), 5.016 s, 4.128 s, 4.652 s (warm)
original LZ4 controls: 5.655 s, 3.822 s, 3.760 s, 3.711 s
```

The daemon grouped metrics also varied without a consistent reduction in
`primary_ms` (candidate per-table sums were approximately 57.4, 45.4, 48.9,
and 38.1 seconds). There is no reproducible end-to-end or service-side win.
The original Retina was restored and marked READY; the system property and
source overlay were removed. Decision: **reject** WriteBatch preallocation as
a standalone optimization. The next probe should address the number and
overlap of native per-vnode writes, with explicit safeguards against the
serialization seen in the rejected single cross-CF batch.

## RocksDB background-flush parallelism probe (2026-11-22, rejected)

The original isolated configuration uses
`index.rocksdb.max.background.flushes=2`. An isolated Retina restart changed
only this property to `4`; WAL, LZ4 compression, write-buffer sizes,
compaction settings, grouped request shape, and the 32-writer Trino session
were unchanged. Four fresh 1M-row targets all passed the exact check
`1,000,000/100,001/1,100,000/1,000,000`.

```text
flushes=4: 5.649 s (cold), 4.036 s, 4.413 s, 3.713 s (warm)
original flushes=2 controls: 5.655 s, 3.822 s, 3.760 s, 3.711 s
```

The corresponding daemon grouped `primary_ms` sums were about 59.0, 41.1,
48.0, and 45.5 seconds. They do not show a consistent improvement over the
original 2-flush runtime, and the SQL warm set is slightly worse overall.
The configuration was restored to `2` from
`runtime/logs/rocks-flush4-20261122/pixels.properties.before`, and Retina was
restarted and marked READY. Decision: **reject** this simple flush-thread
increase; the next RocksDB experiment needs to distinguish native write
queueing from background flush/compaction work rather than raising both
implicitly.

The RocksDB `LOG` files for the four flush4 tables provide a negative
diagnostic result: write-stall counters were zero, estimated pending
compaction bytes were zero, and cumulative compaction work was approximately
zero during these fresh INSERTs. The runtime is now restored to
`max.background.flushes=2`, LZ4, WAL enabled, with the original daemon and
connector processes active. This makes the next investigation a grouped
native-write scheduling/lock profile, not another background-flush knob.

## Exact UPDATE sparse-key correctness fix (2026-11-22)

The first sparse-key E2E exposed a correctness defect in the original exact
route. For a finite predicate such as `id BETWEEN 1 AND 3`, the encoder
expanded the whole inclusive range and appended tag entries without checking
whether every generated primary key existed. With only ids 1 and 3 present,
the statement reported three updated rows; after inserting id 2 and running
compaction, id 2 incorrectly inherited the tag. This is a correctness failure,
not an acceptable approximation for the million-row benchmark.

The fix adds an optional `ignoreMissing` field to
`ResolvePrimaryEntriesRequest`. The strict existing API remains unchanged for
compaction and other callers. The exact UPDATE path now resolves generated
primary keys in batches of 65,536, keeps only returned existing keys, appends
tags for that filtered set, and reports the filtered count. A separate
`primary_key_resolve_ms` metric makes the new cost visible. The daemon-side
resolver skips absent primary-index entries only when this explicit flag is
set; missing primary locations still fail the request.

Validation on the isolated cds20 runtime used a table with primary index
`id`, inserted ids 1 and 3, and executed the range UPDATE. The fixed runtime
produced the following evidence:

```text
UPDATE: 2 rows
[PIXELS_PRIMARY_RESOLVE_METRIC] tableId=2712, indexId=2663,
  keys=3, resolved=2, serviceMs=5.603451
[PIXELS_TAG_INDEX_SHARED_METRIC] ... logicalUpdates=2, primaryKeys=2
```

The subsequent insert of id 2 retained its original `N/2.0000` values on
ordinary reads; no tag was appended for the missing key. The earlier unfixed
run is retained as the regression witness showing the `3 rows` result and the
post-compaction ghost tag. A full post-fix compaction rerun was not promoted
to an acceptance result because the exploratory runtime later lost its
published recovery-checkpoint body and the fail-closed Retina recovery guard
correctly refused a fresh restart. No checkpoint or metadata was deleted or
fabricated to force that test.

This fix is therefore an UPDATE correctness gate, not yet a performance win.
For a dense 1M-row range it adds primary-index existence resolution (expected
to be measurable in `primary_key_resolve_ms`); the next performance run must
measure that cost against the prior 501K--763K rows/s exact-route baseline.
The accepted order is: dense correctness, sparse correctness, post-compaction
correctness, then throughput optimization. Any optimization that bypasses
existence filtering is rejected regardless of its rows/s result.

## Batched primary-index existence lookup (2026-11-22, pending E2E throughput)

The exact UPDATE correctness fix makes primary-index existence resolution an
unavoidable part of the supported route. Code inspection found that the
RocksDB implementation created a fresh `RocksIterator` for every generated
primary key. This is particularly expensive for a dense 1M-row range: the
lookup is already grouped into RPC batches, but the daemon still paid iterator
construction and teardown once per key.

The candidate adds an order-preserving batch lookup API with the old point
lookup as the default fallback. `CachingSinglePointIndex` preserves the
existing cache behavior, while `RocksDBIndex` reuses one configured read
iterator for all keys in a vnode batch. It still performs the same version-aware
`seek` and logical-prefix check for every key; it does not use `MultiGet`,
which would be incorrect because an index key can have multiple timestamped
versions and the lookup must select the newest visible version. The daemon
groups the request by vnode, resolves each group through the batch API, and
then emits entries in the original request order. Missing-key filtering and
strict missing-key failure semantics are unchanged.

The candidate compiled successfully on cds20: `pixels-common` regenerated the
protobuf classes and installed successfully, and the direct `pixels-daemon`
package completed successfully. The focused RocksDB regression passed:
`testGetUniqueRowIdsBatchPreservesVersionAndMissingSemantics` (1 test, 0
failures). The complete legacy `TestRocksDBIndex` class had one unrelated
pre-existing failure in `testPutEntries` while the new batch test passed; that
failure is retained as a test-environment result and is not attributed to this
change.

No end-to-end rows/s claim is made yet. The isolated runtime remains blocked by
the missing published Retina recovery-checkpoint body described above, so this
candidate must be measured against the dense UPDATE baseline after a safe
runtime recovery. Acceptance requires: dense UPDATE correctness, sparse-key
correctness, post-compaction correctness, and a reproducible reduction in
`primary_key_resolve_ms` or total SQL wall time without changing the tagging
contract.

## Fresh 68-column INSERT -> UPDATE -> COMPACT E2E (2026-11-22)

The pending candidate was measured on the rebuilt isolated runtime:

```text
runtime: e2e-fresh-20261122
table: validationfresh.e2e68_fresh_candidate5_20261122
fixture: 68 columns, one root paths URI, pk=id, pk_scheme=rocksdb
```

The earlier candidate4 table is excluded: it was created before the Retina
Java-module fix and failed with a missing visibility entry. Candidate5 was
created after that fix.

### INSERT

```text
INSERT: 1,000,000 rows
Trino query elapsed: 11.861 s       ~= 84.3k rows/s
CLI end-to-end wall: 13.979 s       ~= 71.5k rows/s
SELECT count(*): 1,000,000
```

Connector metrics included `primary_put_wall_ms=5881.610`,
`append_ms=1566.756`, and `write_ms=2063.744`. This is a valid committed and
readable INSERT, but it is below the historical warm hundreds-of-thousands
results and does not reach the target.

### UPDATE and tag-aware COMPACT

The exact dense range update was:

```sql
UPDATE validationfresh.e2e68_fresh_candidate5_20261122
SET tag_1_type = 'R', tag_1_para = 9.0000
WHERE id BETWEEN 4000000001 AND 4001000000;
```

Evidence:

```text
UPDATE: 1,000,000 rows
Trino query elapsed: 16.375 s      ~= 61.1k rows/s
CLI end-to-end wall: 18.462 s      ~= 54.2k rows/s
primary_key_resolve_ms: 14,794.105
UPDATE connector total_ms: 16,070.425
rpc_count=1, rpc_ms=917.980
tag-index shared service_ms: 688.645
```

The daemon resolved all 1,000,000 keys in 15 batches of 65,536 plus a
16,960-key tail. Before COMPACT, ordinary reads still returned the original
physical tag values; this is expected for the supported versioned UPDATE
contract. UPDATE records the Tag Index state and COMPACT materializes it.

Tag-aware `COMPACT -s validationfresh -t e2e68_fresh_candidate5_20261122 -n
no -c 8` completed successfully:

```text
CLI wall: 53.130 s
tag resolve: 1,000,000 rows, 62 RPCs, 12.167 s
physical rewrite: 18.763 s
publish: 8.391 s
```

Immediate post-COMPACT checks passed:

```text
total=1,000,000
tag_1_type='R'=1,000,000
tag_1_para min=max=9.0000
tag_1_type='R' AND tag_1_para=9.0000 => 1,000,000
boundary ids 4000000001 and 4001000000 => ('R', 9.0000)
```

This is valid fresh-runtime evidence for the complete in-process
INSERT -> UPDATE(Tag Index) -> COMPACT -> materialized read path. It does not
meet the throughput target: INSERT is about 71.5k--84.3k rows/s depending on
the wall definition, and UPDATE is about 54.2k--61.1k rows/s.

### Restart durability: failed gate, retained as a regression

Retina was restarted with the required Java 23 `java.nio` module opens. It
loaded checkpoint `recovery_cds20_5.bin` in `RECOVERING`, and the normal
`get status -> markReady(recoveryEpoch)` handshake was performed. The
post-handshake read returned:

```text
total=0
updated=0
tag predicate count=0
```

Recovery logged `baselineFiles=0`, `retired=2`, and `warns=242`; the compacted
file and its source were subsequently classified as `RETIRED` in metadata.
The published checkpoint pointer later referenced a body that was no longer
present. Therefore the in-process COMPACT result is accepted as a functional
E2E result, but persistence across Retina restart is **failed**, not accepted.
No checkpoint, metadata row, or physical file was fabricated or manually
changed to make the check pass.

The next narrowly scoped fix is the recovery lifecycle: preserve the compact
output's REGULAR metadata and checkpoint visibility entry through checkpoint
publication/cleanup, then repeat the same restart readback. Only after that
gate passes should throughput optimization be promoted.

## Checkpoint visibility fix: clean candidate8 E2E (2026-11-22)

The checkpoint fix was rebuilt and exercised from a clean isolated snapshot.
The Java regression suite passed 31/31 tests; the daemon fat jar was rebuilt
with the fix and deployed only to `runtime/e2e-fresh-20261122`.

The accepted table used the dataset fixture's single-root-path construction:

```text
table: validationfresh.e2e68_fresh_candidate8_20261122
columns: 68
rows: 1,000,000
primary key: id (7000000001..7001000000)
paths: one file:// root; connector derives v-0-ordered and v-0-compact
```

### INSERT and UPDATE

```text
INSERT: 1,000,000 rows
Trino query elapsed: 12.216 s       ~= 81.9k rows/s
CLI shell wall: 14.289 s             ~= 70.0k rows/s
SELECT count/min/max: 1,000,000 / 7000000001 / 7001000000

UPDATE: 1,000,000 rows
Trino query elapsed: 11.723 s       ~= 85.3k rows/s
CLI shell wall: 13.592 s             ~= 73.6k rows/s
primary_key_resolve_ms: 9,931.028
UPDATE connector total_ms: 11,322.453
rpc_count=1, rpc_ms=962.353
```

The INSERT metrics were `primary_put_wall_ms=6,125.029` and
`main_flush_ms=909.446`. The UPDATE route was logged as exact MPP primary-key
eligible, and all 1,000,000 keys resolved.

### COMPACT and same-timestamp checkpoint rewrite

Tag-aware COMPACT completed with the correct root-path layout:

```text
tag resolve: 1,000,000 rows, 62 RPCs, 12.205 s
physical rewrite: 17.186 s
publish: 8.634 s
relocated rows: 1,000,000, relocation RPCs: 128
```

Post-COMPACT checks passed:

```text
total=1,000,000
tag_1_type='R'=1,000,000
tag_1_para min=max=9.0000
tag_1_type='R' AND tag_1_para=9.0000 => 1,000,000
boundary ids 7000000001 and 7001000000 => ('R', 9.0000)
```

The checkpoint evidence directly exercises the bug fixed by commits
`8cb3e5d0` and `a82ad7fa`: before COMPACT, the latest body had
`checkpointAppliedTs=4, rgs=305`; after COMPACT, the timestamp remained 4 but
the body was republished with `rgs=306`. The visibility-generation change
therefore prevents the stable-timestamp early return from losing the compact
file.

### Restart durability gate

Retina was stopped and restarted with the Java 23 module opens. It loaded
`recovery_cds20_4.bin` and reported `baselineFiles=1`; the normal recovery
epoch handshake was then performed:

```text
STATE=RECOVERING
STATE_AFTER=READY
```

After the restart and readiness transition, all of the following still
passed: total row count 1,000,000; updated tag count 1,000,000; tag predicate
count 1,000,000; and both boundary rows `(R, 9.0000)`. This candidate therefore
passes the previously failed persistence gate. The earlier candidate5 failure
remains recorded as a regression and was not overwritten.

This fixes a correctness/durability blocker, not the throughput target.
Candidate8 remains at roughly 70--82k rows/s for INSERT and 74--85k rows/s for
UPDATE depending on shell-versus-Trino wall definition, so the lower
hundreds-of-thousands target is still outstanding.

## Primary-resolution phase probe and batch-size sweep (2026-11-23)

The isolated fresh runtime was instrumented without changing the resolution
semantics. `IndexServiceImpl.resolvePrimaryEntries` now reports bucket setup,
single-point RocksDB lookup, and location/response construction separately:

```text
[PIXELS_PRIMARY_RESOLVE_METRIC]
keys=... bucketMs=... singlePointMs=... locationResponseMs=... serviceMs=...
```

The first 1,000,000-row UPDATE after restarting Retina was a cold run and is
not a steady-state result: its connector `primary_key_resolve_ms` was
21,586.589 ms, with the first 65,536-key request taking about 11.3 s in
bucket setup. A subsequent warm run using the default 65,536-key client batch
was:

```text
CLI wall: 12.510 s                         ~= 79.9k rows/s
primary_key_resolve_ms: 9,618.586 ms
UPDATE connector total_ms: 10,373.895 ms
request_build_ms: 208.511 ms, rpc_ms: 524.417 ms
```

The warm Retina phase logs were approximately 30--38 ms for bucket setup,
426--514 ms for single-point lookup, and 20--48 ms for location/response per
65,536-key request (with a 16,960-key tail). The single-point phase therefore
remains the dominant measured server-side stage; request construction and the
shared tag-index RPC are not the primary limiter.

Two larger client batches were tested against the same isolated table. They
reduced the number of RPC batches but increased per-request RocksDB work and
were slower overall:

```text
batch size   primary_key_resolve_ms   connector total_ms   CLI wall
65,536       9,618.586                10,373.895           12.510 s
131,072      11,235.845               12,158.057           15.955 s
262,144      12,731.467               13,564.642           16.006 s
```

The default remains 65,536. The Trino connector now reads an optional
`pixels.update.primary-key-resolve-batch-size` JVM property, defaulting to
65,536, so future sweeps do not require source edits. The 131,072 and 262,144
values are rejected as optimizations by this evidence.

An exact RocksDB `multiGet` experiment was also rejected for the UPDATE path.
UPDATE resolution uses the transaction snapshot timestamp and must retain
prefix-seek semantics to find the latest visible version; it is not an exact
`Long.MAX_VALUE` lookup. The experiment was removed and is not part of the
candidate implementation.

The post-sweep COMPACT attempt resolved 1,000,000 tag rows but reported zero
physical rewrite outputs, and the readback still showed the prior physical
tag values. This run is retained as a failed materialization observation, not
as correctness evidence; the previously accepted candidate8
INSERT -> UPDATE -> COMPACT -> restart gate remains the only accepted
materialized result.

## Primary-index INSERT recheck with explicit table metadata (2026-11-23)

The first candidate created during this pass (`e2e68_fresh_candidate9_20261123`)
was intentionally not accepted: its DDL omitted `pk='id'` and therefore did
not create a primary index. Its 1M-row table-to-table INSERT took 5.683 s, but
the connector emitted `index_ms=0` and no `PIXELS_INSERT_INDEX_METRIC`. That
number is useful only as a no-index data-path probe, not as the INSERT result
for the tag-aware primary-key workload.

A clean replacement (`e2e68_fresh_candidate10_20261123`) used the same 68-column
schema and a single root, with the complete supported metadata:

```sql
WITH (
  storage = 'file',
  paths = 'file:///.../e2e68_fresh_candidate10_20261123/v-0-ordered',
  pk = 'id',
  pk_scheme = 'rocksdb'
)
```

The source was the validated 1M-row `e2e68_fresh_candidate8_20261122` table.
The destination checks passed after both arms:

```text
after first append:  1000000 / 7000000001 / 7001000000 / 1000000
after warm append:   2000000 / 7000000001 / 8001000000 / 2000000
```

Measured remote CLI wall and connector evidence were:

```text
primary-index first append: 12.341 s ~= 81.0K rows/s
  primary_put_wall_ms=5919.612, primary_rpc_count=5
  sink write_ms=803.195, index_ms=1445.566

warm append with id + 1,000,000,000: 9.489 s ~= 105.4K rows/s
  primary_put_wall_ms=3992.687, primary_rpc_count=5
  sink write_ms=582.929, index_ms=870.485
```

Both INSERTs were real table-to-table operations and both used the primary
index route. They are therefore accepted as comparable evidence, but remain
below the lower-hundreds-of-thousands goal. The gap is not explained by the
file encoder alone: the warm arm still spends about 4.0 s in primary-index
puts, while the total remote wall is 9.5 s. The no-index candidate9 result must
not be used to claim that the target has been reached.

## Session-controlled writer count on a clean indexed target (2026-11-23)

The candidate10 source had been through repeated UPDATE/COMPACT experiments,
so candidate11 was created with the same explicit `pk='id'` metadata and was
fed by the single-file materialized candidate9 source. This isolated the
writer-count/session effect from the source layout. The first two arms used
the connector's default writer shape and are retained as a control:

```text
default session, 1M rows:  query elapsed 8.038 s ~= 124.4K rows/s
default session, 1M rows:  query elapsed 7.471 s ~= 133.8K rows/s
```

The historical benchmark's session controls were then applied explicitly:

```text
task_min_writer_count=32
task_scale_writers_enabled=false
```

Two fresh one-million-row appends were performed into the same indexed target.
Both created 32 fragments, used the grouped primary-index route, and passed
the exact uniqueness/range checks:

```text
first 32-writer arm:  query elapsed 3.989 s ~= 250.7K rows/s
warm 32-writer arm:   query elapsed 3.739 s ~= 267.4K rows/s
final target:         count/min/max/distinct = 3000000/7000000001/10001000000/3000000
```

This explains the earlier apparent plateau: the 8--10 s results were not
using the session configuration of the accepted 24--32 writer benchmark. The
current isolated runtime reaches the lower-hundreds-of-thousands INSERT goal
on a real primary-index table-to-table path. It is still well below 1M rows/s;
the remaining measured cost is distributed across 32 per-file finishing/index
paths rather than the final metadata publish (225 ms on the warm arm).

A final 24-writer arm on the same target used
`task_min_writer_count=24, task_scale_writers_enabled=false` and a fresh ID
range. It completed in 3.171 s, approximately 315.4K rows/s, and passed the
exact final check:

```text
count/min/max/distinct = 4000000/7000000001/11001000000/4000000
```

On this machine and fixture, 24 writers are currently the better session
point than 32 writers (315.4K versus 250.7--267.4K rows/s). This is a
benchmark-session result, not a global connector default change; the target
still does not approach 1M rows/s.

## Indexed INSERT -> UPDATE -> COMPACT completion on candidate11 (2026-11-23)

The same candidate11 then exercised the complete tag-aware route on a populated
indexed table. A one-million-row exact primary-key range was updated with
`tag_1_type='U24'` and `tag_1_para=24.0000`:

```text
UPDATE query elapsed: 9.545 s ~= 104.8K rows/s
rows=1000000, primary_key_resolve_ms=8382.522
request_build_ms=271.922, rpc_count=1, rpc_ms=528.088, total_ms=9257.642
```

As expected for the tag-before-COMPACT design, an immediate physical readback
still showed the old tag values. The isolated Pixels CLI then ran
`COMPACT -n no -c 8`; the 4M-row target required approximately seven minutes
of compaction work. The post-COMPACT readback passed:

```text
total rows = 4000000
tag_1_type = 'U24' = 1000000
tag_1_para min/max = 24.0000 / 24.0000
updated-range U24 count = 1000000
```

This is a complete correctness/materialization E2E for the current fixture.
It also makes the remaining performance split explicit: INSERT reaches
315.4K rows/s at the 24-writer benchmark point, UPDATE reaches 104.8K rows/s
before COMPACT, and COMPACT is a separate multi-minute materialization stage
on a 4M-row table. The million-row goal is therefore not yet met end-to-end;
the next high-value optimization target remains primary-key resolution for
UPDATE and the compaction scan/rewrite path, not the tag publication semantics.

## Opt-in parallel primary-key resolution for UPDATE (2026-11-23)

The serialized UPDATE resolver issued one 65,536-key snapshot lookup at a time.
For 1M rows this creates 16 independent batches; daemon probes showed roughly
0.4--0.6 s of service time per batch. The connector now supports the opt-in
JVM property:

```text
-Dpixels.update.primary-key-resolve-parallelism=N
```

The default is `1`, preserving the previous serialized behavior. When enabled,
the connector submits independent snapshot batches concurrently but joins
their results in input order, so key filtering and tag ordering remain stable.
The change compiled successfully on cds20 with the isolated connector build;
it was not enabled in the main runtime.

The isolated candidate11 table was used for fresh one-million-row ranges. The
same 65,536-key batch size and one shared tag-index RPC were retained:

```text
parallelism  primary_key_resolve_ms  connector_total_ms  Trino_elapsed  rate
1             8,382.522              9,257.642           9.545 s       104.8K/s
4             2,958.079              3,676.256           3.918 s       255.2K/s
8             1,866.437              2,804.700           3.079 s       324.8K/s
16            1,532.389              2,171.124           2.445 s       409.0K/s
32            1,427.836              2,069.087           2.358 s       424.1K/s
```

The 16- and 32-way arms have the same effective maximum of 16 tasks for a
1M-row request; their small difference is within run-to-run scheduling and
RocksDB variance. The candidate is therefore accepted as an opt-in UPDATE
optimization, with 16 as the useful configured ceiling for this batch size,
but the default remains 1 until broader concurrency and failure-injection
validation is completed. On this fixture, tag-aware UPDATE now reaches roughly
0.4M rows/s, while indexed INSERT remains at roughly 0.315M rows/s at its
24-writer point. Neither path has reached 1M rows/s yet.

### Primary-resolution batch-size matrix (2026-11-23)

With the new opt-in resolver parallelism held at 16, two additional batch sizes
were tested on fresh 1M-row ranges of candidate11:

```text
batch size  primary_key_resolve_ms  connector_total_ms  Trino_elapsed
32,768      1,997.169              2,858.796           4.520 s
65,536      1,532.389              2,171.124           2.445 s
131,072     3,011.357              3,953.767           5.652 s
```

The 131,072-key requests took about 1.6 s each on the daemon, so reducing the
number of waves did not compensate for the larger per-request RocksDB work.
The 32,768-key arm also lost to the 65,536-key point. The matrix therefore
rejects both new batch sizes and restores the isolated runtime to
`batch_size=65536, parallelism=16`; the default source behavior remains
serialized (`parallelism=1`).

## Parallel tag-snapshot resolution and physical rewrite profile (2026-11-23)

The compaction path had the same serialized shape as the original UPDATE
resolver: tag-index snapshot keys were resolved in sequential 16,384-key RPCs.
The CLI now supports the opt-in property:

```text
-Dpixels.compact.primary-resolve-parallelism=N
```

The default remains `1`. On candidate11, which had no remaining ordered input
after its accepted compact, a 4M-row snapshot-only comparison reduced the
tag-resolution phase from 50.563 s and 245 RPCs to 7.138 s with `N=8`. This is
not a physical rewrite throughput result, but it confirms that the independent
snapshot RPCs overlap safely and preserve the ordered response contract.

A real physical-rewrite validation was then run on candidate10, which still
had two 1M-row ordered files. One 1M-row range was updated to `U35/35.0000`;
the new CLI compacted the table with `N=8` and passed the final readback:

```text
total=2000000
tag_1_type='U35'=1000000
tag_1_para=35.0000=1000000
updated-range U35=1000000
```

The phase metrics were:

```text
tag snapshot:       1,000,000 rows, 62 RPCs, 2.159 s
physical rewrite:   32.955 s
  primary resolve:  2,000,000 rows, 124 RPCs, 24.608 s
  non-resolve work: 8.347 s
relocation:         2,000,000 rows, 128 RPCs
publish:            18.002 s
total CLI:          about 51 s
```

The candidate is accepted as an opt-in compaction optimization for the
snapshot phase, but the larger remaining bottleneck is the primary resolution
performed while rewriting source rows. Further compaction work should target
that 124-RPC path; writer-count tuning is not the next lever. The code was
compiled on cds20 and was used only by the isolated CLI jar.

### Physical-rewrite asynchronous resolution A/B (2026-11-23; rejected)

An additional candidate attempted to overlap source-row primary-key resolution
with the physical rewrite. The implementation submitted bounded 16,384-key
resolution batches to an opt-in executor and applied results in input order;
the default serial path was unchanged. Two fresh 2M-row tables were prepared
from the same source fixture, each with one 1M-row tagged update:

```text
fixture       resolver       physical rewrite wall   publish     result
candidate12   serial         107.7 s                  18.5 s       correct
candidate13   parallel=8     156.0 s                  17.5 s       correct
```

Both tables retained 2,000,000 rows and exactly 1,000,000 updated rows. The
serial candidate resolved 2,000,000 rows in 145 RPCs (25.924 s summed RPC
time); the parallel candidate used 142 RPCs (25.783 s summed RPC time). The
summed RPC metric is not a wall-clock metric, but the end-to-end rewrite and
CLI wall clocks show no benefit and a substantial regression for `parallel=8`.

During follow-up attempts the isolated Trino connector also reported repeated
gRPC `GOAWAY`/connection-closed errors while metadata and transaction calls
were being made. The isolated Trino process had to be restarted with the
runtime's explicit JDK and `PIXELS_HOME` configuration before it could be
probed again; subsequent INSERT attempts were still not reliable enough for a
new performance arm. This is recorded as a stability warning, not as proof of
a direct causal relationship to the candidate.

The asynchronous physical-rewrite candidate is therefore rejected and was not
committed. The next compaction investigation should first isolate index-service
contention and connection health, then test lower-concurrency or a different
batching architecture only after the runtime is stable. The accepted changes
remain the opt-in snapshot parallelism and the opt-in UPDATE resolver
parallelism.

### Correct-root physical-rewrite follow-up (2026-11-23)

The preceding candidate12/candidate13 physical-rewrite numbers are not a fair
layout-controlled comparison. Their table definitions supplied the generated
`v-0-ordered` and `v-0-compact` directories as explicit `paths`. The connector
then derived another layout below those directories, which explains the two
physical outputs and the later metadata-path failures. Those runs remain useful
as a rejected-candidate record, but their rewrite wall clocks must not be used
as a baseline for the standard table-root layout.

A fresh candidate15 was therefore created with one table-root URI and an
explicit primary index:

```text
paths = .../e2e68_fresh_candidate15_20261123
pk = id
pk_scheme = rocksdb
```

It received two one-million-row INSERTs, then one exact one-million-row
`UPDATE` to `U39/39.0000`. With the experimental physical-rewrite jar and
`-Dpixels.compact.primary-resolve-parallelism=2`, COMPACT completed correctly:

```text
tag snapshot:       1,000,000 rows, 62 RPCs, 6.526 s
physical rewrite:   14.845 s (one output)
  primary resolve:  2,000,000 rows, 124 RPCs, 23.777 s
relocation:         2,000,000 rows, 128 RPCs
publish:            17.367 s
```

The final readback passed:

```text
total=2000000
tag_1_type='U39'=1000000
tag_1_para min/max=9.0000/39.0000
updated-range U39=1000000
```

This is a valid correctness and phase-profile result for the standard root
layout. Two earlier serial/p4 attempts failed because their generated DDL
retained already-expanded `v-0-*` paths; that was a fixture-construction error,
not evidence that the serial code could not handle a root layout. `SHOW CREATE`
must therefore not be copied verbatim when constructing a fresh benchmark
table: use a new root URI and explicitly restore the primary-index properties.

### Correct-root async physical-rewrite recheck (2026-11-23)

The source implementation was restored and rebuilt on cds20. It keeps
`parallelism=1` as the serial path and, for values above one, submits bounded
16,384-key source-row resolution batches while the reader/writer continues;
results are applied in submission order before publication. The default was
not changed. The compactor unit tests passed (3 tests, 0 failures), and a new
candidate21 run used the freshly rebuilt full jar.

Two independent clean p2 and p4 pairs were then run on the standard root
layout, followed by a source-built p4 validation:

```text
arm / fixture       tag snapshot   primary resolve   rewrite   publish   CLI
p2 / candidate15   6.526 s        23.777 s          14.845 s  17.367 s  32 s
p2 / candidate19   6.419 s        23.946 s          14.891 s  16.959 s  31 s
p4 / candidate18   3.442 s        25.753 s           9.999 s  18.011 s  28 s
p4 / candidate20   3.521 s        24.643 s           9.665 s  18.067 s  27 s
p4 / candidate21   3.584 s        28.240 s          10.746 s  18.578 s  29 s
```

Every run processed 2,000,000 source rows, relocated 2,000,000 rows in 128
RPCs, and passed the final readback with exactly 1,000,000 updated rows and
the requested tag value. The p4 arms reduce end-to-end CLI time by roughly
9--13% against the clean p2 arms in this fixture. The summed primary-RPC time
does not improve; the gain comes from overlapping resolution with source
reading and physical writing. The earlier p8 rejection remains valid for its
bad-layout/stability experiment and is not a reason to enable p8 here.

The async source-row resolver is therefore retained as an opt-in candidate,
with p4 the only compact physical-rewrite setting validated on a correct root
fixture. The default remains serial until broader failure-injection and larger
table validation are completed. The remaining bottleneck is still the primary
resolution RPC path, but its wall-clock cost can now be overlapped safely at a
moderate concurrency.

### 8M-source-row p4 validation (2026-11-23)

Candidate22 extended the correct-root fixture to eight one-million-row INSERTs
(`8,000,000` source rows), then updated exactly one million rows to
`U47/47.0000`. COMPACT was run with the freshly rebuilt jar and
`-Dpixels.compact.primary-resolve-parallelism=4`:

```text
tag snapshot:       1,000,000 rows, 62 RPCs,   3.619 s
physical rewrite:   8,000,000 rows, one output, 33.254 s
primary resolve:    8,000,000 rows, 496 RPCs, 101.639 s (summed RPC time)
relocation:         8,000,000 rows, 512 RPCs
publish:            one output, 72.101 s
compact CLI:        105 s
```

The final Trino readback passed after publication:

```text
total=8000000
tag_1_type='U47'=1000000
tag_1_para min/max=9.0000/47.0000
updated-range U47=1000000
```

This validates the opt-in p4 pipeline at 8M source rows: the physical rewrite
and the asynchronous source-row resolution complete without lost updates,
duplicate primary keys, or incorrect tag visibility. The `101.639 s` resolver
number is a summed RPC metric rather than wall-clock time; the approximately
`105 s` CLI duration demonstrates that resolution is overlapped with rewrite
and publication. It remains a throughput bottleneck, so this result is a
scale/correctness milestone, not evidence that the million-row target has been
reached for UPDATE itself. The default and the user-facing behavior remain
unchanged.

### Correct-root p8 sanity check (2026-11-23)

The earlier p8 rejection came from a bad-layout/stability experiment. To
separate that fixture problem from the parallel resolver itself, candidate23
used a fresh standard table root, a 2M-row table-to-table INSERT, and one exact
1M-row UPDATE to `U48/48.0000`. COMPACT was run with
`-Dpixels.compact.primary-resolve-parallelism=8`:

```text
tag snapshot:       1,000,000 rows, 62 RPCs,   2.214 s
physical rewrite:   2,000,000 rows, one output,  8.082 s
primary resolve:    2,000,000 rows, 123 RPCs,  24.283 s (summed RPC time)
relocation:         2,000,000 rows, 128 RPCs
publish:            one output, 17.336 s
compact CLI:        25 s
```

The final readback passed:

```text
total=2000000
distinct_id=2000000
tag_1_type='U48'=1000000
tag_1_para min/max=9.0000/48.0000
updated-range U48=1000000
```

This is a valid correctness result for p8 on the standard root layout and
shows that the old bad-layout p8 failure must not be generalized to all p8
runs. It is not yet a controlled p4-vs-p8 benchmark: candidate23 was produced
by a single 2M-row table-to-table INSERT and therefore had a different ordered
file layout from the earlier p4 fixtures. The p8 result is retained as an
opt-in candidate pending the matched-layout repeat and the later 8M writable
tag-aware validation recorded below.

A matched-layout repeat (candidate24, the same 2M source table shape with a new
root) produced nearly identical results:

```text
arm / fixture       tag snapshot   primary resolve   rewrite   publish   CLI
p8 / candidate23   2.214 s        24.283 s           8.082 s  17.336 s  25 s
p8 / candidate24   2.099 s        25.249 s           8.022 s  17.779 s  25 s
```

Candidate24 also passed the final readback with `total=2000000`,
`distinct_id=2000000`, and exactly `1000000` rows carrying `U49` in the
updated range. The repeat makes p8 a stable opt-in result for this fixture,
but it still does not justify changing the default or claiming a general
8M-scale improvement.

### Opt-in parallel primary-index relocation (2026-11-23)

The 8M p4 profile showed that publish/relocation, rather than physical
rewriting, had become the dominant wall-clock phase. `CompactExecutor` was
issuing relocation RPCs bucket by bucket in one serial loop. A new opt-in
property, `pixels.compact.primary-relocate-parallelism`, now runs independent
vnode buckets concurrently while preserving the existing RPC batch size and
the order of batches within each bucket. Its default is `1`, so existing
behavior is unchanged unless explicitly enabled.

Two matched 2M standard-root runs used p8 source-row resolution and p8 bucket
relocation:

```text
arm / fixture                  tag snapshot   primary resolve   rewrite   publish   CLI
p8/p8 / candidate25           2.226 s        24.680 s           8.696 s   6.749 s  15 s
p8/p8 / candidate26           2.082 s        24.529 s           8.538 s   6.711 s  15 s
```

The control candidate24 used the same source-table shape and p8 source-row
resolution with relocation parallelism left at its default of one:

```text
p8/p1 / candidate24           2.099 s        25.249 s           8.022 s  17.779 s  25 s
```

Both p8/p8 runs passed final readback with 2,000,000 total rows, 2,000,000
distinct primary keys, and exactly 1,000,000 updated rows in the requested
range (`U50` and `U51`, respectively). The relocation RPC count remained 128;
the improvement comes from overlapping independent bucket RPCs, not from
weakening correctness or reducing the number of index updates. The result is
therefore retained as a stable opt-in optimization, but the default remains
serial pending index-service saturation and failure-injection testing.

The relocation path was also exercised at the 8M-row scale in candidate27:

```text
tag snapshot:       0 rows
physical rewrite:   8,000,000 rows, one output, 22.475 s
primary resolve:    8,000,000 rows, 489 RPCs, 103.445 s (summed RPC time)
relocation:         8,000,000 rows, 512 RPCs
publish:            28.648 s
compact CLI:        51 s
```

The readback retained `8,000,000` rows, `8,000,000` distinct primary keys,
and the pre-existing physical `U47` rows. This is relocation-scale evidence,
not an 8M UPDATE/tag-visibility result: the attempted UPDATE used a
non-existent sparse ID interval and correctly affected zero rows. A follow-up
candidate copied from the already-compacted candidate22 source also returned
zero for an existing interval, showing that this table-to-table fixture does
not preserve a writable primary-index state for a subsequent tag UPDATE. It
is excluded from UPDATE throughput conclusions. The valid tag-aware E2E
evidence remains candidate22 at 8M and candidates25/26 at 2M.

### 8M writable tag-aware p8/p8 E2E (2026-11-23)

Candidate29 closes the scale-validation gap with a writable fixture. It was
constructed from the clean 1M candidate9 source using eight direct INSERTs;
each subsequent INSERT added a `1,000,000,000` primary-key offset. This is
important: copying from an already-compacted source does not preserve the
writable primary-index state required for a subsequent UPDATE. Candidate29
then updated the existing second ID segment
(`8000000001--8001000000`) to `U53/53.0000`; the UPDATE itself returned
exactly 1,000,000 rows.

COMPACT used both opt-in settings at p8:

```text
tag snapshot:       1,000,000 rows, 62 RPCs,   4.284 s
physical rewrite:   8,000,000 rows, one output, 25.156 s
primary resolve:    8,000,000 rows, 496 RPCs, 105.360 s (summed RPC time)
relocation:         8,000,000 rows, 512 RPCs
publish:            one output, 30.691 s
compact CLI:        55 s
```

The final Trino readback passed:

```text
total=8000000
distinct_id=8000000
tag_1_type='U53'=1000000
tag_1_para min/max=9.0000/53.0000
updated-range U53=1000000
```

This is the first complete 8M writable tag-aware E2E for the p8 resolver plus
p8 bucket-relocation pipeline. It validates correctness and scale, while the
summed primary-resolution time and publish phase remain the dominant costs.
The defaults remain unchanged; controlled index-service saturation and
failure-injection testing are still required before considering a default
parallel setting.

### Relocation p16 rejection (2026-11-23)

Candidate30 repeated the 2M writable fixture with p8 source-row resolution but
raised `pixels.compact.primary-relocate-parallelism` from 8 to 16:

```text
tag snapshot:       1,000,000 rows, 62 RPCs,  2.071 s
physical rewrite:   2,000,000 rows, one output, 7.719 s
primary resolve:    2,000,000 rows, 124 RPCs, 24.932 s (summed RPC time)
relocation:         2,000,000 rows, 128 RPCs
publish:            one output, 7.533 s
compact CLI:        15 s
```

The final readback was correct (`total=2000000`, `distinct_id=2000000`, and
`U54` in exactly 1,000,000 updated rows), but publish was slower than the
matched p8 runs at approximately 6.7 s and the total CLI time did not improve.
The p16 arm is rejected as a saturation point; p8 remains the highest
validated opt-in relocation setting, and the default stays serial.

## PageSink column-loop probe (2026-11-24, rejected)

The INSERT JFR had shown `PixelsPageSink.appendValue` as a visible Trino-side
hot method. An isolated connector overlay replaced the per-cell category
dispatch in `appendColumnValues` with one category switch per column and
specialized primitive/string/decimal loops. The wire format, null handling,
RLE handling, tag columns, and primary-index route were unchanged. The change
compiled on cds20 and was measured only in the isolated runtime; it was not
committed or pushed.

The comparison used the same 68-column indexed target
`validationfresh.e2e68_fresh_candidate85_20261124`, the same materialized
1M-row source, fixed `task_min_writer_count=32`, disabled writer scaling, and
`task_concurrency=64`. Each arm appended a disjoint 1M-row ID range. Trino
server timelines were:

```text
original PageSink, post-restart: 9.258 s
original PageSink, warm:          5.617 s  (~178K rows/s)
column-loop candidate, post-restart: 8.918 s
column-loop candidate, warm:         6.229 s  (~161K rows/s)
```

The candidate did not improve the measured end-to-end path. Its grouped
primary-put and main-index metrics were comparable to the control, while the
sink/write and finishing intervals were higher in the warm arm. The probe is
therefore rejected and the isolated Trino was restored byte-for-byte to the
original connector jar. The remaining INSERT bottleneck is service-side
primary-index scheduling/flush and Trino finishing, not the local per-cell
category dispatch alone. The next probe should instrument or reduce that
queueing path without changing tag-aware semantics.

## Fresh-target writer retest (2026-11-24)

To separate target size from service scheduling, a new independent root was
created as `validationfresh.e2e68_page_fresh_20261124` with the same 68-column
schema and explicit `pk='id'`, `pk_scheme='rocksdb'` metadata. Four disjoint
1M-row appends from the materialized source passed the exact final check:

```text
count=4,000,000
min(id)=10,000,000,001
max(id)=13,001,000,000
distinct(id)=4,000,000
```

The first two arms used 32 writers and the next two used 24 writers. The
server timelines were:

```text
32 writers, fresh target: 9.332 s (~107K rows/s)
32 writers, warm repeat:  6.219 s (~161K rows/s)
24 writers, warm arm:    5.201 s (~192K rows/s)
24 writers, warm repeat:  5.314 s (~188K rows/s)
```

This confirms that 24 writers are currently better than 32 on this runtime,
but the previously observed 315K rows/s point is not stable under the present
service state. The fresh target also rules out the 49M-row target alone as the
explanation for the lower rate. The accepted current INSERT point is therefore
roughly 188--192K rows/s on this repeatable fresh-target run; the next useful
work remains primary-index request scheduling/flush, not more writer-count or
PageSink micro-tuning.

## Grouped flush max-concurrency sweep (2026-11-24)

The service metrics showed 24 writer RPCs entering a grouped-primary path with
the connector cap at 16. On the same fresh target, source, 24-writer session,
and tag-aware primary-index route, the isolated Trino property was swept across
16, 24, and 32. Each arm appended a disjoint 1M-row range and passed the final
distinct-primary-key check.

```text
max-concurrency=16, warm repeat: 5.314 s  (~188K rows/s)
max-concurrency=24, warm arm:    4.181 s  (~239K rows/s)
max-concurrency=32, warm arm:    4.749 s  (~210K rows/s)
```

The cap-24 arm reduced the Trino finishing interval to about 1.82 s and had a
shorter primary-put tail than the cap-16 control. Raising the cap to 32
increased the warm timeline again, so 24 is retained as the isolated-runtime
candidate and 32 is rejected as a saturation point. This is an opt-in runtime
configuration result, not yet a production-default change; failure-injection,
checkpoint/restart, and a longer multi-table run are still required before
changing the default. The result moves the repeatable INSERT point from about
188K to about 239K rows/s, but does not yet reach the 300K+ goal.

## Fresh-target recheck after Trino lease cleanup (2026-11-24)

The first writer-shape follow-up after the cap sweep was contaminated by two
CLI clients that did not close their Trino sessions. Their transactions
(`trans_id=670068` and `670070`) remained under the offload detector lease,
which blocked subsequent work and eventually caused a generic query failure.
This was a harness/service-state issue, not a data correctness result. The
isolated Trino process was restarted with the same
`flush-cap24-trino.args`; no Retina or table data was reset.

A second independent indexed target,
`validationfresh.e2e68_page_fresh2_20261124`, was then used with the same
68-column materialized source, 24 fixed writers, writer scaling disabled,
`task_concurrency=64`, and connector cap 24. The three disjoint one-million-
row INSERTs produced these Trino timelines:

```text
first arm (cold): 6.888 s  (~145K rows/s)
warm arm 1:       3.872 s  (~258K rows/s)
warm arm 2:       4.223 s  (~237K rows/s)
```

The final check passed exactly:

```text
count=3,000,000
min(id)=10,000,000,001
max(id)=12,001,000,000
distinct(id)=3,000,000
```

This confirms that the cap-24 path can repeatedly return to the lower
240--260K rows/s range after a clean Trino session, but it does not establish
a stable 300K+ result. It also shows that the apparent degradation on the
11M-row target cannot be attributed to target row count alone: residual
transaction leases and service state materially affect the measured timeline.
Future performance arms must record and clean active query/lease state before
comparing numbers. The result remains an isolated opt-in configuration and
does not justify changing the production default.

## Checkpoint-backed recovery after GC heap failure (2026-11-24)

The first post-sweep restart exposed an operational precondition that had not
been satisfied by the performance harness: Retina was still in `RECOVERING`
because the normal `get status -> markReady(recoveryEpoch)` handshake had not
been completed. Calling `markReady` started the configured 10-second GC, but
the 32 GiB Retina then exhausted its heap while `runGC()` processed the large
backlog of historical retired files. The `retina-gc-thread` ended with Java
heap OOM followed by a JVM `SIGSEGV`; no checkpoint body was deleted or
overwritten. This arm is rejected as an infrastructure failure.

The isolated service was recovered from the retained
`recovery_cds20_670007.bin` using a separate `Xmx64G` configuration and a
temporary 300-second GC interval. The recovery itself was exact, but the
INSERTs made after checkpoint 670007 were correctly absent after restart:
both `e2e68_page_fresh_20261124` and `e2e68_page_fresh2_20261124` read zero
rows. Those post-checkpoint performance arms are therefore not durable
results and are not used as evidence of data loss from a committed
checkpoint.

With the same 64 GiB heap and a temporary 10-second GC interval, the service
then published `recovery_cds20_670077.bin` while remaining alive. A new
100,000-row table-to-table INSERT into
`e2e68_page_fresh2_20261124` was committed after that checkpoint, using IDs
`60000000001..60000954000`. Its exact pre-restart check was:

```text
count=100000
distinct(id)=100000
tag_1_type='U111'=100000
tag_1_para min=max=111.0000
```

## Trino writer floor 32: rejected (2026-11-24)

With the stable Retina runtime and the same benchmark session properties, a
probe forced the local writer range to exactly 32:

```text
task_concurrency=64
task_min_writer_count=32
task_max_writer_count=32
task_scale_writers_enabled=false
```

The two warm INSERT arms measured 4.94 s and 4.58 s
(`20261124_012259_00032_txdru` and `20261124_012308_00033_txdru`). The paired
24--32 writer baseline was 3.93 s. For this unpartitioned table shape, forcing
all 32 writers adds overhead rather than increasing throughput, so the probe
is rejected and the benchmark remains on the 24--32 writer range.

The post-probe correctness check remained exact at 29M rows with unique IDs
and complete `U111`/`111.0000` tags.

## Writer-range follow-up: 16--16 also rejected (2026-11-24)

The legal lower writer setting was tested after Trino rejected `max=24` because
`task_max_writer_count` must be a power of two. With
`task_min_writer_count=16` and `task_max_writer_count=16`, two warm arms
measured 4.38 s and 4.45 s (`20261124_012612_00037_txdru` and
`20261124_012621_00038_txdru`). This is slower than the 24--32 baseline, so
the lower writer range is also rejected. The benchmark configuration remains
`task_min_writer_count=24`, `task_max_writer_count=32`, with writer scaling
disabled.

The correctness check after the probe remained exact at 31M rows, with all IDs
unique and all `U111`/`111.0000` tag values preserved.

## Long-decimal direct-read probe: rejected (2026-11-24)

Trino JFR on the 8191 validation server showed
`PixelsPageSink.appendValue`, `VariableWidthBlock.copyPositions`, and
`LongDecimalType.getObject` among the hottest INSERT-side methods. The test
table contains three `DECIMAL(38,2)` columns, so a connector candidate replaced
the per-position `Int128` wrapper read in `appendDecimalValues` with direct
`Int128ArrayBlock` high/low reads. The candidate compiled successfully and was
installed only on the validation runtime; it was not committed or pushed.

The first implementation retained the block logical-position lookup inside
the row loop and produced a 7.05 s warm arm. A second implementation added a
plain-`Int128ArrayBlock` direct-index branch and retained mapping only for
wrapped blocks, but still produced a 7.32 s warm arm. The stable connector
jar, restored afterward, produced a 4.46 s warm arm at the same 24--32 writer
session settings. The candidate therefore made the end-to-end INSERT slower;
the allocation hotspot is not independently worth optimizing in this form.

Both candidate arms wrote valid data. After restoring the original connector
jar, the final check was exact at 38M rows:

```text
count=38,000,000
distinct(id)=38,000,000
min(id)=10,000,000,001
max(id)=4,010,001,000,000
tag_1_type='U111'=38,000,000
tag_1_para min=max=111.0000
```

The source worktree is clean and the runtime is back on the original stable
connector jar.

The normal GC cycle then published `recovery_cds20_670080.bin`. Retina was
stopped and restarted from that body with the 64 GiB configuration, and the
normal recovery-epoch handshake completed (`RECOVERING -> READY`). The
post-restart check returned exactly the same 100,000 rows, ID range, distinct
count, and `U111/111.0000` tag values. This is accepted as a checkpoint-backed
tag-aware INSERT durability proof. The larger heap and 10-second interval are
isolated validation settings; neither is promoted as a production default
without a resource-budget and GC-backlog analysis.

## Durable-runtime INSERT recheck after Retina recovery (2026-11-24)

After the checkpoint-backed 100K recovery proof, the isolated runtime was
left on the 64 GiB Retina configuration, GC interval 10 seconds, connector
grouped-primary cap 24, and a fixed 24-writer Trino session. Three additional
disjoint 1M-row table-to-table INSERTs were run into the same indexed target.
The Trino timelines were:

```text
arm 1: 3.790 s  (~264K rows/s)
arm 2: 3.111 s  (~321K rows/s)
arm 3: 3.397 s  (~294K rows/s)
```

The target check after the three arms passed exactly:

```text
count=3,100,000
min(id)=60,000,000,001
max(id)=63,001,000,000
distinct(id)=3,100,000
```

The Retina process stayed alive and the background GC advanced the published
checkpoint through `recovery_cds20_670088.bin`. These runs establish a current
lower-300K INSERT performance band on the recovered, checkpoint-capable
runtime, rather than a single isolated peak. They are performance evidence;
only the preceding 100K arm has a post-restart readback in this section, so a
full 3.1M restart readback is still a separate durability gate. The 64 GiB
heap and 10-second interval remain isolated validation settings.

### Full 3.1M restart readback

The latest checkpoint from the three performance arms was
`recovery_cds20_670088.bin`. Retina was restarted from that exact body and
completed the normal recovery-epoch handshake. The post-restart query passed:

```text
count=3,100,000
distinct(id)=3,100,000
min(id)=60,000,000,001
max(id)=63,001,000,000
tag_1_type='U111'=3,100,000
tag_1_para min=max=111.0000
```

This closes the full-size checkpoint/restart gate for the current INSERT
fixture: the approximately 300K rows/s performance band and the complete
3.1M-row tag-aware result survive a Retina restart. The runtime remains an
isolated validation setup because its 64 GiB heap and shortened GC interval
still require production resource-budget review.

## Trino task-concurrency control (2026-11-24)

With the same recovered Retina, connector grouped-primary cap 24, fixed
24-writer session, 68-column source, and indexed target, one additional 1M
row append used `task_concurrency=32` instead of the accepted 64:

```text
task_concurrency=32: 3.656 s  (~274K rows/s)
```

The target remained correct after the arm:
`count=4,100,000`, `distinct(id)=4,100,000`, with IDs spanning
`60000000001..64001000000`. The matched `task_concurrency=64` arms were
294--321K rows/s, so lowering task concurrency is rejected as an optimization.
The 64 setting remains the isolated benchmark configuration; this arm is
performance evidence and was not used as a separate restart gate.

## Promote grouped-RPC cap 24 as the code default (2026-11-24)

The cap-24 behavior was initially opt-in with a default of zero (unlimited).
The isolated evidence now covers both sides of the decision: the cap moved
the repeatable INSERT path from roughly 188K rows/s to a lower-300K band, and
the complete 3.1M-row result passed checkpoint/restart recovery. The matched
32-writer and task-concurrency probes were rejected, while the explicit
property remains available for rollback and diagnostics.

Commit `2fc7677` therefore changes
`DEFAULT_PRIMARY_FLUSH_MAX_CONCURRENCY` from `0` to `24`. Setting
`-Dpixels.insert.index.flush.max-concurrency=0` still restores the historical
unlimited behavior, so operators retain an explicit escape hatch. The change
is a connector default only; it does not alter UPDATE semantics, tag layout,
checkpoint format, or the Retina GC configuration. The isolated validation
runtime used an explicit cap-24 argument when this commit was prepared; the
following section records the subsequent no-property default-value E2E check.
This commit should not be read as production deployment evidence by itself.

## Code-default cap-24 E2E check (2026-11-24)

The connector jar containing commit `2fc7677` was deployed to the isolated
validation Trino, and the explicit
`-Dpixels.insert.index.flush.max-concurrency` argument was removed from the
Trino process. This makes the test exercise the Java source default of 24,
rather than an explicitly configured cap. The grouped publish metric still
reported `fragments=24`, confirming that the intended grouped path was used.

The first 1M-row INSERT after the Trino restart took 8.863 s (~113K rows/s)
and is classified as a cold-start result. The next disjoint 1M-row INSERT,
with the same 24 writers and `task_concurrency=64`, took 4.062 s (~246K
rows/s). The target check after both default-value arms passed:

```text
count=6,100,000
distinct(id)=6,100,000
min(id)=60,000,000,001
max(id)=66,001,000,000
```

This confirms that the promoted default is active and preserves the exact
tag-aware table-to-table INSERT result. Its steady-state result is within the
previously observed lower-240K-to-lower-300K band, but it does not by itself
raise the current INSERT ceiling to 1M rows/s. The 64 GiB Retina and shortened
GC interval remain isolated validation settings.

## Writer-count probe (2026-11-24)

The code-default runtime then ran one disjoint 1M-row arm with
`task_min_writer_count=48`, `task_concurrency=64`, and writer scaling disabled,
compared with the accepted 24-writer setup. The 48-writer arm took 4.554 s
(~220K rows/s), versus 4.062 s (~246K rows/s) for the preceding default-value
24-writer arm. It also increased the grouped publish work from 24 fragments and
195 ms total to 48 fragments and 734 ms total.

The target remained exact after the probe:

```text
count=7,100,000
distinct(id)=7,100,000
min(id)=60,000,000,001
max(id)=67,001,000,000
```

Increasing writer count on this single-node fixture is therefore rejected. The
result supports the current diagnosis that the dominant cost is the Trino
data/exchange and page conversion path, not the final metadata publish RPC.

## Sustained 3M-row INSERT and queryStats evidence (2026-11-24)

To reduce sensitivity to a single 1M-row timing, one statement appended three
disjoint 1M-row source copies with the accepted 24-writer settings. It took
10.391 s for 3,000,000 rows, or approximately 289K rows/s. The exact target
check passed:

```text
count=10,100,000
distinct(id)=10,100,000
min(id)=60,000,000,001
max(id)=80,001,000,000
tag_1_type='U111'=10,100,000
tag_1_para min=max=111.0000
```

After the arm, the isolated Retina published
`recovery_cds20_670099.bin`; both the 64 GiB Retina process and the default-
value Trino process remained alive. This records checkpoint progress only, not
a new post-restart durability gate for the full 10.1M-row target.

For the preceding 1M default-value arm, Trino queryStats reported 290 MB of
physical input, 437 MB of internal network input, 595 MB of stage output, and
3.12 s of Stage 1 `ExchangeOperator` wall time (3.07 s CPU) inside a 4.062 s
query. The connector publish metric was 195 ms. This is the strongest current
evidence that the single-node ceiling is in the Trino page/exchange and data
conversion path, rather than in Retina metadata publication. A transient
thread-list sample was not used as evidence because it did not provide a
reliable interval CPU measurement.

## Block-dispatch specialization probe (2026-11-24)

`PixelsPageSink.appendColumnValues` originally called the per-cell
`appendValue` type switch for every value. The connector branch was changed to
dispatch once per Block and use a type-specialized loop, while preserving the
existing RLE fast path and null, decimal, time, timestamp, and binary handling.
The change compiled successfully against the JDK 23 Trino runtime. The
repository's Maven Surefire configuration reported tests as skipped, so that
result is not treated as a unit-test pass; the E2E checks below are the
authoritative runtime checks for this probe.

The temporary optimized jar was exercised with five disjoint 1M-row arms. The
first arm after restart was cold at 9.154 s; the subsequent warm timelines were
4.022 s, 3.502 s, 3.920 s, and 3.808 s. The target checks remained exact,
including `U111/111.0000` tags. QueryStats for the 4.022 s arm showed
`TableWriterOperator` CPU of 2.10 s and total query CPU of 8.67 s.

For a same-runtime control, the original jar was restored and exercised with
five disjoint arms. Its warm timelines were 4.738 s, 4.052 s, 3.280 s, and
3.824 s. The control QueryStats showed `TableWriterOperator` CPU of 6.19--6.68
s, while Stage 1 `ExchangeOperator` remained the critical wall-time path.
The specialized loop therefore produces a substantial writer-CPU reduction,
but the wall-time distributions overlap and do not establish a stable
throughput increase toward 1M rows/s. It is retained as a CPU-efficiency
optimization, not as the final throughput solution.

The final control check passed:

```text
count=20,100,000
distinct(id)=20,100,000
min(id)=60,000,000,001
max(id)=110,001,000,000
tag_1_type='U111'=20,100,000
tag_1_para min=max=111.0000
```

The active validation jar was restored to the original cap-24-default SHA256
`4c33d82daa12de8c7572dbf3aaf25eab8f9d551061c42d06598cc712430da8a4` after the
probe. The next optimization target remains the Stage 1 Exchange/data path.

## Writer scheduling probes (2026-11-24)

Two additional session-level probes were run without changing the connector
jar. Enabling Trino writer scaling with `task_min_writer_count=24` produced
1M-row timelines of 3.351 s, 6.023 s, and 3.560 s. Matching the source's
approximately 15 scan drivers with fixed `task_min_writer_count=15` produced
3.135 s, 5.642 s, and 3.129 s. Every arm returned the expected inserted row
count and unique ID range, but both probes contained multi-second long tails
and did not beat the stable 24-writer band.

These settings are rejected as default benchmark settings. The current
single-node INSERT conclusion remains a lower-240K-to-lower-300K rows/s band;
the occasional 300K+ arm is a peak observation, not yet a reproducible
million-row/s result.

After these probes the target contained 25.1M rows with exact uniqueness and
tag values, and the isolated Retina status remained `READY` at checkpoint
epoch `1dc618b2-432c-4f48-a4e9-c7af198c5f22`. The latest published checkpoint
body was `recovery_cds20_670120.bin`; this is a live-state check, not a new
post-restart readback gate.

## Direct indexed versus no-index INSERT path (2026-11-24)

The preceding Exchange/PageSink evidence was complemented by a clean direct
path comparison on the same 68-column, 1M-row materialized source. The
indexed arm used the ordinary table-to-table `INSERT ... SELECT` route with
the primary-key index and the tag columns intact. A no-index table was used
only as a diagnostic upper bound; it is not an acceptable implementation
because it removes the primary-index and tag-aware update contract.

The fresh indexed direct arm (`20261123_231238_00043_mb5ng`) took 5.136 s for
1,000,000 rows (~195K rows/s). QueryStats showed approximately 4.10 s of
Stage 1 `ExchangeOperator` output wall time and 10.79 s of summed
`TableWriterOperator` add-input wall time. The per-writer connector metrics
reported one grouped primary RPC each; the grouped service metrics showed
`primary_ms` around 0.83--2.20 s for 30--56K-entry requests, while the main
index portion was only tens of milliseconds.

The no-index diagnostic arm (`20261123_231342_00046_mb5ng`) took 2.403 s
(~416K rows/s) with the same source shape and 24 fragments. Its sink metrics
reported `index_ms=0`, and its publish metric was 126 ms. This isolates the
primary-index write path as a large cost component, but it does not imply that
the index can be removed from the production route.

A clean indexed warm append was then run against
`validationfresh.e2e68_page_direct_20261124` using 24 writers,
`task_concurrency=64`, and writer scaling disabled. Query
`20261123_232333_00055_mb5ng` inserted a disjoint 1,000,000-row range in
4.188 s (~239K rows/s). The final table check was exact:

```text
count=2,000,000
distinct(id)=2,000,000
min(id)=10,000,000,001
max(id)=410,001,000,000
tag_1_type='U111'=2,000,000
tag_1_para min=max=111.0000
```

The append's publish metric was 103 ms; representative per-writer primary
put wall times reached 1.24 s, with main-index flushes generally below 50 ms.
This clean warm control confirms that the earlier indexed/no-index gap is not
only first-table initialization. The immediate optimization target is now
the grouped primary-index/RocksDB write schedule and its interaction with the
24 concurrent Trino writers. The diagnostic `direct2` table is excluded from
correctness baselines because a repeated harness submission left duplicate
physical rows; its isolated query timing remains usable only as a performance
probe for the disjoint range that it inserted.

## Matched client-writer sweep on the clean indexed target (2026-11-24)

To test whether the 24-writer setting was merely an arbitrary local optimum,
the clean `e2e68_page_direct_20261124` target was extended with two disjoint
1M-row ranges. The source, schema, grouped primary route, `task_concurrency=64`,
and writer-scaling setting were held constant; only the fixed writer count
changed. Every arm preserved the primary-key and tag-aware route.

```text
8 writers:  5.443 s  (~184K rows/s)
16 writers: 4.800 s  (~208K rows/s)
24 writers: 4.188 s  (~239K rows/s, preceding clean control)
```

The 8-writer arm produced 8 fragments and a primary-put tail of about 2.62 s;
the 16-writer arm produced 16 fragments and a tail of about 1.49 s. The
corresponding main-index publish work stayed below 110 ms. The final target
check after all four disjoint arms was exact:

```text
count=4,000,000
distinct(id)=4,000,000
min(id)=10,000,000,001
max(id)=610,001,000,000
tag_1_type='U111'=4,000,000
tag_1_para min=max=111.0000
```

This rules out under-parallelized client fan-out as the immediate cause of the
INSERT ceiling: increasing from 8 to 24 writers improves throughput, but the
24-writer arm still remains below 300K rows/s and the primary-index tail is
the limiting stage. Further gains require changing the grouped RocksDB write
shape or its native scheduling, not another small writer-count adjustment.

## Multi-source split probe and fixture correction (2026-11-24)

The large candidate-85 table was not a usable range-slice source: although it
contained 45M rows, its IDs had gaps and the intended 1M range returned zero
rows. A clean indexed target, `e2e68_page_multisource_20261124`, was then
created and fed by four 250K slices from the same validated candidate-86
source using `UNION ALL`. The 1M-row result was exact:

```text
count=1,000,000
distinct(id)=1,000,000
tag_1_type='U111'=1,000,000
tag_1_para min=max=111.0000
```

However, the statement took 10.386 s (~96K rows/s), with most of the cost in
planning/finishing rather than useful scan parallelism. This is rejected as an
INSERT optimization; adding logical source branches is not equivalent to
adding efficient physical workers.

For completeness, an earlier performance-only union used candidate80--83 and
appended 1M rows to the long-running `e2e68_page_fresh2_20261124` target. Those
historical sources carried different tag values, so the target now contains
`U111` (25.35M), `AAA` (500K), and `U82` (250K). That arm is not part of the
tag-preserving benchmark baseline. The accepted tag-preserving checkpoint
state remains the 25.1M-row state immediately before this probe, and the clean
multi-source target above is used for any follow-up split experiments.

## Rejected RocksDB value-buffer hoist (2026-11-24)

The JFR profile showed `RocksDBThreadResources.getValueBuffer()` on the grouped
primary-write path. A low-risk-looking candidate therefore resolved the
thread-local eight-byte value buffer once per `WriteBatch` instead of once per
entry. The candidate module compiled successfully and was installed only as an
isolated Retina overlay; the stable overlay remained available for rollback.

The candidate was tested on the same indexed `e2e68_page_fresh_20261124`
target after a successful stable warm append. Its first real 24-writer append
failed during writer close:

```text
query=20261124_000503_00012_mb5ng
elapsed=11.252 s
failure=IndexException: Failed to put primary index entries, error code=10602
path=RPCIndexService.putPrimaryIndexEntries -> grouped primary writer
10602=INDEX_PUT_SINGLE_POINT_INDEX_FAIL
```

The failure is materially different from the earlier first-open timeout: the
candidate reached the single-point primary-index write and returned the index
service error. No candidate rows were committed. The stable runtime was
restored, the same target recovered with exactly 2,000,000 rows, and a stable
24-writer append then committed successfully in 7.456 s. The final target
check was exact:

```text
count=3,000,000
distinct(id)=3,000,000
min(id)=10,000,000,001
max(id)=310,001,000,000
tag_1_type='U111'=3,000,000
tag_1_para min=max=111.0000
```

The precise JNI ownership/position mechanism behind the error is not yet
proven, so this experiment does not claim a narrower root cause. The
engineering conclusion is firm: the value-buffer hoist is rejected and must
not be merged. The original per-entry buffer preparation is restored in both
the source tree and the running Retina; the failed overlay is retained only as
forensic evidence.

## Rejected ThreadLocal value-buffer lookup (2026-11-24)

The failed hoist was narrowed to a safer candidate: retain the original
per-entry `position(0)`/`limit(8)` preparation, but replace the per-entry
`ConcurrentHashMap<ThreadId, ByteBuffer>.computeIfAbsent` lookup with a
`ThreadLocal<ByteBuffer>`. The candidate compiled and was deployed as an
isolated Retina overlay after recovery from checkpoint `670188`.

On `e2e68_page_fresh_20261124`, the candidate appended a disjoint 1M-row slice
from `e2e68_fresh_candidate86_20261124` successfully:

```text
query=20261124_001909_00021_mb5ng
elapsed=11.33 s
physicalWrittenDataSize=291,107,949 B
```

The target check was exact at 4M rows:

```text
count=4,000,000
distinct(id)=4,000,000
min(id)=10,000,000,001
max(id)=410,001,000,000
tag_1_type='U111'=4,000,000
tag_1_para min=max=111.0000
```

The stable overlay was then restored from checkpoint `670194` and ran the same
1M-row INSERT shape on the same target, starting at 4M existing rows:

```text
query=20261124_002358_00025_mb5ng
elapsed=10.77 s
physicalWrittenDataSize=291,112,064 B
```

This is not a perfectly paired benchmark because the stable arm started with
one million more existing rows, but the candidate was slower despite the
smaller index. The result provides no evidence of an INSERT gain, so the
ThreadLocal lookup change is rejected and was not committed. The remote source
and running Retina were restored to the original implementation.

## Accepted BIGINT primary-key encoding fast path (2026-11-24)

The INSERT JFR profile also showed per-row allocation in
`TypeDescription.convertColumnVectorToByte`. The common benchmark schema uses a
single BIGINT primary key; the old path allocated a `ByteBuffer` for every key
just to emit eight canonical big-endian bytes. The accepted change adds a
specialized single-BIGINT path in `PrimaryKeyBytes.fromColumnVectors` that emits
the same bytes directly into one eight-byte array. Composite keys and all other
types keep the existing path, and NULL primary keys still raise the same
`IllegalArgumentException` contract.

After the unit test passed, the candidate was loaded only into the isolated
validation Trino plugin. With the same 24-writer settings and the same
tag-preserving source, it appended 1M rows to `e2e68_page_fresh_20261124`:

```text
candidate query=20261124_003342_00001_waz8q
existing rows=5,000,000
execution=6.59 s  (~152K rows/s)
physicalWrittenDataSize=291,443,975 B
```

The stable plugin was then restored and appended the same 1M-row shape at the
next disjoint offset:

```text
control query=20261124_003543_00000_b3nad
existing rows=6,000,000
execution=8.28 s  (~121K rows/s)
physicalWrittenDataSize=291,405,090 B
```

This is a conservative comparison because the stable control started with a
larger existing index. The fast path was nevertheless about 20% faster in
execution time and preserved exact data correctness. After both arms, the
target check was:

```text
count=7,000,000
distinct(id)=7,000,000
min(id)=10,000,000,001
max(id)=710,001,000,000
tag_1_type='U111'=7,000,000
tag_1_para min=max=111.0000
```

The candidate is accepted for the BIGINT single-column primary-key case; a
follow-up paired run should measure it on an empty/fresh target before using it
as the final headline throughput number.

## `insertNew` grouped-main-index switch: inconclusive, not enabled (2026-11-24)

The Retina JFR after the BIGINT fast path still sampled
`MainIndexBuffer.insert`. The daemon already contains an opt-in
`pixels.index.grouped.primary.write.new-main-index=true` switch that routes
grouped INSERTs through `MainIndex.putNewEntries`/`MainIndexBuffer.insertNew`,
avoiding relocation scans and cache invalidation that are unnecessary for
freshly allocated row IDs. The switch was tested without changing source or
the default, using the same 24-writer, tag-preserving INSERT shape.

The first candidate arm after a Retina restart took 24.59 s and was excluded
as a cold RocksDB/recovery arm; its grouped metrics showed primary-index work
around 18.5 s. A second candidate arm without restart took 4.88 s elapsed
(4.80 s execution). The switch was then removed and the stable daemon was
restarted from a checkpoint containing the same 10M-row state. Its first arm
took 15.77 s and was likewise excluded as cold; a stable warm arm then took
5.19 s elapsed (5.12 s execution).

The warm difference is too small and not perfectly paired because the arms
started with different existing index sizes. The grouped metrics show the
main-index portion around 0.29--0.32 s per candidate request versus
0.30--0.37 s for the stable requests, while the primary RocksDB portion still
dominates. The switch is therefore retained as a diagnostic option but is not
enabled by default or claimed as a throughput optimization. The runtime is
back on the stable daemon configuration. After the complete probe sequence,
the target check remained exact at 12M rows with unique IDs and complete
`U111`/`111.0000` tags.

## High-scale paired recheck of `insertNew`: rejected as a default change (2026-11-24)

The earlier switch probe did not use a sufficiently paired state. A further
check first corrected the benchmark command itself: the headline INSERT
baseline uses these Trino session properties and omitting them changes the
execution regime materially:

```text
task_concurrency=64
task_min_writer_count=24
task_max_writer_count=32
task_scale_writers_enabled=false
```

At roughly 22M existing rows, the stable default path (`putEntries` /
`MainIndexBuffer.insert`) measured 14.28 s for the cold post-restart arm and
4.42 s for the warm arm (`20261124_011413_00022_txdru` and
`20261124_011432_00023_txdru`). The same target was then restarted with only
`pixels.index.grouped.primary.write.new-main-index=true` enabled. Its cold arm
measured 14.27 s and its two warm arms measured 4.10 s and 3.94 s
(`20261124_011715_00025_txdru`, `20261124_011733_00026_txdru`, and
`20261124_011827_00027_txdru`).

The stable daemon was restored and run again at the same scale. Its first arm
after restart was 18.30 s (cold and excluded), followed by a 3.93 s warm arm
(`20261124_012054_00030_txdru`). Thus the apparent 7--11% candidate gain did
not reproduce against the final warm control; it is within the observed
restart/cache and batch variance. The switch remains useful for diagnostics,
but it is rejected as a default optimization and is not used for the headline
throughput claim. The runtime ended on the stable 32-thread configuration.

The final correctness check after this sequence remained exact at 27M rows:

```text
count=27,000,000
distinct(id)=27,000,000
min(id)=10,000,000,001
max(id)=2,710,001,000,000
tag_1_type='U111'=27,000,000
tag_1_para min=max=111.0000
```

## Compact grouped-primary wire format: rejected (2026-11-24)

The JFR profile showed nested `PrimaryIndexEntry` protobuf parsing and
construction on the grouped INSERT route. An opt-in protocol candidate added a
`PutCompactPrimaryIndexEntries` RPC whose batch carried table id, index id,
timestamp, and file id once; each row carried only the primary-key bytes,
rowId, and row-group coordinates. The daemon reconstructed the existing
`PrimaryIndexEntry` objects before entering the unchanged grouped RocksDB and
main-index code, so WAL, tag-preserving row locations, abort bookkeeping, and
recovery semantics were unchanged. The default RPC remained untouched.

The candidate was compiled and deployed only to the isolated validation
runtime. The first run exposed a deployment omission (`pixels-common` in the
Trino plugin directory still contained the old generated protobuf classes) and
failed before index flush; that run committed no rows and was excluded. After
installing the matching common jar, the candidate completed two disjoint 1M-row
INSERTs successfully. The comparable timings were:

```text
regular grouped RPC, query 20261124_021925_00022_iwrt8: 6.063 s (~165K rows/s)
compact grouped RPC, query 20261124_022151_00000_yx5fg: 10.414 s (~96K rows/s; post-restart)
compact grouped RPC, warm query 20261124_022339_00003_yx5fg: 5.864 s (~170K rows/s)
regular grouped RPC, warm query 20261124_022515_00001_nve3a: 4.623 s (~216K rows/s)
```

All successful arms used `task_concurrency=64`, 24--32 writers, disabled writer
scaling, the same 68-column tag-preserving source, and disjoint primary-key
ranges. The final target check was exact at 44M rows:

```text
count=44,000,000
distinct(id)=44,000,000
min(id)=10,000,000,001
max(id)=10,010,000,100,000
tag_1_para min=max=111.0000
tag_1_type='U111'=44,000,000
```

The compact wire shape is therefore rejected: reconstructing the nested
objects server-side and adding a second RPC path cost more than it saved in
this workload. The source, generated protocol, daemon fat jar, connector jar,
and runtime switch were restored to the pre-probe stable versions. The next
optimization should target the service-side grouped RocksDB scheduling or a
representation that avoids reconstruction entirely, not this wire-only form.

## Fresh coordinator recovery and current paired throughput (2026-11-24)

The first post-restart one-row INSERT probe was invalid as a performance run.
The Trino task had read its one source row but waited in
`PixelsInsertIndexWriter.finish()` for `putPrimaryIndexEntries`. A thread dump
showed the Retina request blocked in `RocksDBFactory.preCreateColumnFamilies()`
while waiting for the coordinator's metadata `getTableById` RPC. The active
coordinator log also contained repeated `NoClassDefFoundError` entries for
Guava and shaded Netty classes. This was a runtime classpath/process-lifecycle
failure after the isolated jar replacement, not an INSERT algorithm result.

The stuck query was canceled without being counted. The fresh coordinator and
worker were restarted from the current complete daemon fat jar, while Retina
and its data/checkpoint directories were left in place. The restarted
coordinator reached the existing Retina READY state and emitted no class-load
errors. A one-row INSERT then finished in 1.03 s, and a primary-key readback
returned exactly one row.

The corrected wide-table benchmark used the historical session controls:
`task_concurrency=64`, `task_min_writer_count=24`,
`task_max_writer_count=32`, and `task_scale_writers_enabled=false`, together
with grouped primary writes and connector flush parallelism 4. Disjoint
one-million-row ranges were appended to the indexed
`validationfresh.e2e68_page_fresh_20261124` table:

```text
session-corrected cold arm: 4.22 s ~= 237K rows/s
session-corrected warm arm: 4.02 s ~= 249K rows/s
```

Both INSERTs returned `1,000,000 rows`; the warm connector metrics showed five
grouped primary RPCs, with primary-index put wall time still about 4--5 s in
aggregate. The omitted-session control was much slower (11.63 s cold and
8.98 s warm), so those numbers are retained only as a harness warning and are
not compared with the accepted session-corrected results.

The same runtime then executed tag-aware UPDATEs on confirmed contiguous
one-million-row ranges created by the preceding INSERTs. The first arm took
1.96 s (`~510K rows/s`), and the disjoint warm arm took 1.69 s
(`~592K rows/s`). The warm connector metric was:

```text
rows=1000000
primary_key_resolve_ms=892.589
request_build_ms=238.885
rpc_ms=338.929
total_ms=1515.004
rpc_count=1
```

The tag predicate read immediately after UPDATE returned zero physical rows,
which is expected under the existing tag-before-COMPACT contract; the UPDATE
results and shared tag-index RPC completed successfully, while physical tag
materialization remains a separate COMPACT step. The current headline remains
therefore approximately `249K rows/s` for indexed INSERT and `592K rows/s`
for tag-aware UPDATE on this fixture. INSERT is still below the several-
hundred-thousand target band, and the next optimization target remains the
service-side grouped RocksDB write schedule rather than Trino transaction or
metadata commit.

## Strict table-to-table INSERT recheck and current source-side profile (2026-11-24)

The preceding session-corrected 4.02 s INSERT used a generated `UNNEST` source
and is retained only as a connector/service diagnostic. The general INSERT
headline must use a materialized source table. A strict recheck therefore used
the one-million-row table
`validationfresh.e2e68_fresh_candidate86_20261124`, whose source range is
`10,000,000,001..10,001,000,000` and whose rows carry `U111/111.0000` tags.
The source and target had different historical tag-column orderings, so the
statement explicitly projected all thirty type columns followed by all thirty
parameter columns. The first attempted statement was rejected during analysis
with a type-order mismatch and committed no rows; the explicit projection then
completed successfully.

With the same grouped-primary route and the standard 24-writer session:

```text
strict table-to-table cold arm: 7.01 s ~= 143K rows/s
strict table-to-table warm arm: 4.27 s ~= 234K rows/s
```

The two arms used disjoint destination ranges `66,000,000,001..66,001,000,000`
and `67,000,000,001..67,001,000,000`. Each range passed the exact check
`count=1,000,000`, `distinct(id)=1,000,000`, and
`tag_1_type='U111' AND tag_1_para=111.0000`. This is the current accepted
table-to-table INSERT point for the present wide fixture; the faster 249K
generated-source arm is not used as the general INSERT headline.

A 20-second JFR around a third strict warm arm produced the following sampled
hot-method ordering:

```text
PixelsPageSink.appendValue                                  16.32%
UTF_8$Decoder.decodeLoop                                     5.93%
VariableWidthBlock.copyPositions                             5.93%
PixelsPageSink.appendColumnValues                            1.48%
PixelsInsertIndexWriter.appendBatch                          1.73%
StringColumnWriter.writeCurPartWithDict                      1.85%
```

The source-side sink metrics for the warm arm summed, across 24 writers, to
approximately `conversion_ms=7.03 s`, `write_ms=3.15 s`, and `index_ms=1.26 s`;
these are overlapping per-writer intervals, not a serial query duration. The
profile and interval split move the next INSERT work from metadata/commit
tuning toward source string/dictionary decoding, PageSink conversion, and
Stage-1 exchange scheduling. Any further candidate must retain the hidden
timestamp, wide-decimal handling, tag columns, primary-index write, and exact
table-to-table semantics.

## Fixed 32-writer control after strict recheck (2026-11-24)

To test whether the 24-writer result was leaving throughput on the table, a
disjoint strict table-to-table arm was run with
`task_min_writer_count=32`, `task_max_writer_count=32`, and writer scaling
disabled. Query `20261124_041927_00053_67tdy` inserted the destination range
`69,000,000,001..69,001,000,000` and finished in `6.20 s`, approximately
`161K rows/s`. The exact readback passed:

```text
count=1000000
distinct(id)=1000000
min=69000000001
max=69001000000
tag_1_type='U111' AND tag_1_para=111.0000 = 1000000
```

The query stats identify the long tail rather than a metadata commit issue:
Stage 1 `ExchangeOperator` used `2.70 s` of output wall and `2.56 s` CPU,
while the 32-writer `TableWriterOperator` accumulated `11.71 s` wall and
`7.62 s` CPU across its drivers. A neighboring fixed-24 control,
`20261124_042121_00059_67tdy`, inserted
`70,000,000,001..70,001,000,000` in `4.22 s`, approximately `237K rows/s`,
and passed the same exact check. This pair rejects 32 writers as a current
default for this one-node wide fixture; 24 writers remains the better
reproducible point. The next optimization target is the Stage-1 exchange and
source-to-writer scheduling path, not another primary-index-only change.

## Constant-tag projection diagnostic (2026-11-24)

The materialized source fixture is intentionally wide and its observed sample
row has constant tag values (`tag_1=U111/111.0000` and the remaining tag
columns `AAA/3.1400`). To isolate the cost of carrying those columns through
the source and Stage-1 exchange, a diagnostic arm projected the six dimensions
from the source but supplied those observed constants as typed SQL literals.
This is not a general table-to-table implementation and is not a correctness
substitute for copying arbitrary source tags.

The two disjoint diagnostic arms wrote destination ranges
`71,000,000,001..71,001,000,000` and `72,000,000,001..72,001,000,000` with
the normal 24-writer session. They finished in `4.17 s` and `3.93 s`, or
approximately `240K` and `254K rows/s`. The exact checks passed for both arms:
`count=1,000,000`, `distinct(id)=1,000,000`, contiguous min/max, and the
representative `tag_1`, `tag_2`, and `tag_30` values.

The first diagnostic query read only `50,033,632B` from the source versus
`290,289,264B` for the strict copy. Its Stage-1 exchange output was `257ms`
(`153ms` CPU), versus `2.27s` (`2.25s` CPU) on the neighboring strict arm;
TableWriter CPU fell from `7.34s` to `3.58s`. Nevertheless, end-to-end wall
time stayed near four seconds because the target still physically writes the
same wide row and performs the same primary-index publication. The warm
repeat improved only to about `254K rows/s`, so column width and exchange are
confirmed contributors but not the sole INSERT ceiling. A real optimization
should preserve arbitrary tags while avoiding redundant materialization, for
example through a connector/Trino dictionary or constant-block path, and must
be revalidated against non-constant tags before acceptance.

## Rejected reader-side single-entry dictionary-to-RLE probe (2026-11-24)

The source reader was temporarily changed so a non-null string dictionary with
exactly one dictionary entry became a Trino `RunLengthEncodedBlock`; arbitrary
dictionaries and nullable dictionaries retained the existing `DictionaryBlock`
path. The connector compiled successfully on JDK 23 and loaded on the
isolated Trino with the correct `PIXELS_HOME`/`PIXELS_CONFIG` environment.

The strict table-to-table arm used the fixed source and the disjoint destination
range `73,000,000,001..73,001,000,000`. It returned the exact expected
`1,000,000` rows, unique contiguous IDs, and representative `tag_1`, `tag_2`,
and `tag_30` values, but query `20261124_044254_00004_qdbrs` took `10.28 s`
(`~97K rows/s`) versus the stable connector's neighboring `4.22 s`
(`~237K rows/s`). The candidate was rolled back to connector SHA
`4c33d82daa12de8c7572dbf3aaf25eab8f9d551061c42d06598cc712430da8a4`.

This rejects reader-side RLE substitution as a throughput optimization for the
current Trino Exchange/PagesSerde path. The constant-tag diagnostic still
shows that reduced input width helps, but the representation change must be
made at a layer that does not introduce an Exchange or writer long tail.
