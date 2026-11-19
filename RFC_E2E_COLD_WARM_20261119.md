# RFC: 68-Column E2E Cold/Warm Validation — 2026-11-19

## Scope

This run validates the current tagging-first DML architecture on the isolated
`cds20` runtime.  The test deliberately separates:

1. first-write index initialization (cold path),
2. subsequent writes using already-open index state (warm path),
3. table-to-table `INSERT`, and
4. exact primary-key `UPDATE` without including `COMPACT` in its timing.

The test used the branch tip `07d86208` and the staged index instrumentation
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

## Correctness caveat

Immediately after UPDATE, a normal table scan still sees the physical `BASE`
value, as expected before `COMPACT`.  A tag predicate for `UPD1M` returned no
rows in this fresh table, so tag-point lookup correctness for this particular
new-table run is **not accepted** yet.  The next validation must inspect the
tag-index entry directly, confirm its primary-key-to-row-location resolution,
then run `COMPACT` separately and verify the post-compaction scan.  No claim of
full UPDATE E2E correctness is made from this run.

## Next actions

1. Add/execute a direct tag-index listing probe for table/index `411/408` and
   confirm whether `UPD1M` entries are present and resolve to regular files.
2. Re-run the tag-point query and then `COMPACT` after the direct probe.
3. Add RocksDB internal timing for batch construction versus `write()` on the
   warm exact UPDATE path.
4. Prototype vnode prewarm as a separately measurable change; do not change
   the tagging contract or merge fallback semantics.

