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

## Acceptance criteria and remaining work

- [x] INSERT files publish as `REGULAR` with a valid row-id hull.
- [x] A committed 1M-row table survives COMPACT and two Retina restarts.
- [x] Exact-key 1M-row UPDATE reaches the hundreds-of-thousands rows/s range.
- [x] Table-to-table 1M-row INSERT reaches the lower hundreds-of-thousands
  rows/s range.
- [ ] Synthetic UNNEST INSERT reaches the target; current result is 12.8-29.8k
  rows/s and needs a separate producer/concurrency investigation.
- [ ] Automate the recovered Retina replay/READY handshake for the validation
  harness.
- [ ] Profile and reduce duplicate Tag Index/primary-index resolution during
  COMPACT; the stage evidence identifies this as the leading next optimization.
