# RFC: 68-Column E2E Cold/Warm Validation — 2026-11-19

## Scope

This run validates the current tagging-first DML architecture on the isolated
`cds20` runtime.  The test deliberately separates:

1. first-write index initialization (cold path),
2. subsequent writes using already-open index state (warm path),
3. table-to-table `INSERT`, and
4. exact primary-key `UPDATE` without including `COMPACT` in its timing.

The test used the branch tip `8d17848e` and the staged index instrumentation
already recorded in `defbebe7`.  Runtime configuration was restored to the
baseline multi-column-family mode:

```text
index.bucket.num=128
index.rocksdb.multicf=true
index.rocksdb.data.path=/home/pixels/validation-million-update/runtime/pixels/rocksdb
```

Runtime endpoints were the isolated Trino `8091`, metadata `18988`,
transaction `18989`, Retina `18990`, and IndexServer `18995`.

## Dataset

The isolated schema was `pixels`.  The source table was
`e2e68_20261119_source`, with 68 columns, `id bigint` as the primary key, and
30 `(tag_N_type, tag_N_para)` pairs.  Its ordered path was:

```text
file:///home/pixels/validation-million-update/runtime/data/e2e68_20261119/source/
```

The source range was `id=1000000000..1000999999` (1,000,000 rows).  A target
table with the same schema used the corresponding `target/` path.

## Results

### 1. First source load: cold path

SQL shape:

```sql
INSERT INTO e2e68_20261119_source
SELECT ...
FROM UNNEST(sequence(0, 99)) AS b(block)
     CROSS JOIN UNNEST(sequence(0, 9999)) AS r(off)
```

The writer produced 1,000,000 rows, but the statement failed during transaction
commit with `error code=10213` after `331.452 s`.  The table temporarily exposed
940,000 rows.  The server-side primary-index log contained 128 vnode entries
for file `8002`; their summed `single_point_init_ms` was approximately
`311,370 ms`, or about `2.43 s` per vnode.  This is the dominant cold-path
cost; it is not row encoding cost.

The missing 60,000 rows were then appended to the same table.  That warm
follow-up completed in `1.905 s`, and the source count became exactly
1,000,000.  Its writer metric was:

```text
rows=60000 primary_rpc_count=128
primary_put_ms=457.438 primary_put_wall_ms=467.326
main_flush_ms=32.872
```

### 2. Table-to-table INSERT: cold target path

SQL:

```sql
INSERT INTO e2e68_20261119_target
SELECT * FROM e2e68_20261119_source
```

The writer again processed 1,000,000 rows, but the statement failed at commit
with `error code=10213` after `324.103 s`; 930,000 rows were visible in the
target afterward.  The writer metric was:

```text
file_id=8228 rows=1000000 primary_rpc_count=128
primary_put_ms=311250.241 primary_put_wall_ms=311487.460
main_flush_ms=4451.123
```

This independently reproduces the same cold vnode initialization behavior on a
new table.  It also shows that the current cold path can cross the Trino/Pixels
transaction boundary before metadata publication completes.

### 3. Exact 1M-row UPDATE: warm path

SQL:

```sql
UPDATE e2e68_20261119_source
SET tag_1_type='UPD1M', tag_1_para=DECIMAL '9.0000'
WHERE id BETWEEN 1000000000 AND 1000999999
```

Observed wall time was `8.191 s`, approximately `122,100 rows/s`.  Trino
reported:

```text
[PIXELS_UPDATE_ROUTE] exact_eligible=true domain_count=1
[PIXELS_UPDATE_METRIC] rows=1000000 metadata_ms=0.000
encoding_ms=74.324 request_build_ms=312.430 rpc_count=1
request_bytes=20000065 rpc_ms=7290.283 total_ms=7682.998
```

IndexServer reported:

```text
[PIXELS_TAG_INDEX_METRIC] updates=2 primaryKeys=2000000
requestBytes=20000065 decodeMs=351.308 storageMs=3686.174 serviceMs=6706.659
```

This is a valid exact-path result: one UPDATE statement, one Tag Index RPC,
and no merge fallback.  The measured rate is above one hundred thousand rows/s
but below the several-hundred-thousand target.

### 4. Direct tag-index probe and compaction follow-up

The direct IndexServer probe found both tag entries:

```text
tag_1_type = UPD1M       primary keys = 1,000,000
tag_1_para = 9.0000      primary keys = 1,000,000
```

Sample keys at positions `0`, `1`, and `999999` resolved through the primary
index to regular file `8002`.  The prefix lookup path returned all 1,000,000
keys; the exact lookup path returned zero, which is consistent with the
current tag-point reader using prefix lookup for this index representation.

The first compaction attempt failed before physical rewrite because the JDK
needed `--add-opens=java.base/java.nio=ALL-UNNAMED`.  A second attempt included
that option (and `sun.nio.ch`) and successfully passed tag resolution, but a
runtime thread dump then found the real pre-rewrite bottleneck: the main thread
was spending minutes in `TagIndexSnapshot` construction at `Map.copyOf`.
The snapshot map had already been made unmodifiable by
`PixelsTagCompactionUpdateMap.build`; the snapshot constructor and the
compactor constructor copied the million-entry map again.

```text
tag_entries=2 tagged_rows=1000000 resolve_rows=1000000
resolve_rpc_count=62 resolve_ms=12791.524452
```

The A/B fix removed both redundant copies and reused the existing immutable
map.  On the same data and isolated runtime, the optimized run completed in
`57.871 s` (`RC=0`) and published the compact file:

```text
[PIXELS_COMPACTION_STAGE_METRIC] stage=physical_rewrite
worker_ms=20132.240 non_resolve_worker_ms=7167.414 worker_wall_ms=20132.240
[PIXELS_COMPACTION_RESOLVE_METRIC] rows=1060000 resolve_rpc_count=65
resolve_ms=12964.826
[PIXELS_COMPACTION_INDEX_METRIC] relocated_rows=1060000 relocation_rpc_count=128
[PIXELS_COMPACTION_STAGE_METRIC] stage=publish publish_ms=9489.124
```

The row-level breakdown for the 1,000,000-row file was:

```text
read_ms=879.168 primary_key_ms=239.425 update_ms=324.721 writer_ms=2882.152
```

After publication, `count_if(tag_1_type = 'UPD1M')` returned `1,000,000`,
sample rows returned `UPD1M / 9.0000`, and the table reported `1,060,000` rows
with `id` range `1000000000..1001059999`.  The previous timeout was therefore
caused primarily by redundant million-entry snapshot copying, not by Direct
I/O physical rewrite.

## Interpretation

The strongest current bottleneck is cold initialization of 128 primary-index
vnode handles.  The evidence supports the thesis proposal's next direction:
vnode prewarm or an equivalent server-side initialization strategy.  The
existing client fan-out experiment must not simply be repeated as a fix: the
historical 4/16-thread fan-out regressed, so the next optimization should
reduce serialized initialization/write overhead rather than add uncontrolled
client concurrency.

The exact UPDATE route is now confirmed and is not the old merge-path mistake.
Its current steady-state bottleneck is Tag Index storage (`storageMs` about
3.69 s, with roughly 7.29 s client RPC time), so the next warm UPDATE probe
should split RocksDB batch construction from `rocksDB.write`.

For the optimized compaction, the remaining cost is now measurable rather than
opaque: 12.96 s resolves tagged rows, 7.17 s is non-resolve physical rewrite,
and 9.49 s is publication/index relocation.  The full UPDATE-plus-compaction
wall is still below the target throughput when counted synchronously, but the
tagging phase and the asynchronous convergence phase are now cleanly separated.

## Correctness caveat

Immediately after UPDATE, a normal table scan still sees the physical `BASE`
value, as expected before `COMPACT`.  After the optimized compaction completed,
the tag predicate returned exactly 1,000,000 rows and sample rows contained
`UPD1M` and `9.0000`.  The fresh 68-column million-row UPDATE → COMPACT → tag
predicate E2E correctness check is therefore **accepted**.

## Next actions

1. Keep the snapshot-copy fix and add a regression benchmark for a million-row
   tag snapshot so the redundant `Map.copyOf` path cannot return.
2. Reduce the compaction case to one or a few files/columns and compare row
   width, file size, and direct-I/O throughput against the now-known snapshot
   baseline.
3. Add RocksDB internal timing for batch construction versus `write()` on the
   warm exact UPDATE path.
4. Prototype vnode prewarm as a separately measurable change; do not change
   the tagging contract or merge fallback semantics.

