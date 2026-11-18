# RFC: 68-Column Tagging Benchmark

Status: active benchmark specification

Date: 2026-11-19

## Purpose

Make the 68-column tagging workload the primary end-to-end benchmark for the
million-row UPDATE and INSERT goal. TPC-H is out of scope for this validation
track. The workload is based on the supplied data-set design: one primary key,
one capture timestamp, six dimensions, and thirty `tag_type` / `tag_para`
pairs.

The benchmark must preserve the original correctness order:

1. load the base rows;
2. apply tag updates through Trino;
3. make the updates visible through the supported compaction path;
4. verify tag predicates and row counts;
5. restart the isolated runtime and verify recovery.

## Data contract

The logical schema has 68 columns:

- `id bigint`, unique and monotonically increasing;
- `cap_time timestamp`, monotonically generated with repeated values allowed;
- `dim_1..dim_3 integer`;
- `dim_4..dim_6 decimal(38,2)`;
- `tag_1..tag_30_type varchar(10)`;
- `tag_1..tag_30_para decimal(18,4)`.

The target scale is 1,000,000,000 rows. The staged acceptance ladder is
1M, 10M, 100M, and 1B rows. The first load contains values only in the first
eight columns. Tag columns must be represented as SQL NULL in the benchmark
contract; empty strings are not equivalent for tag predicates.

## Workload stages and metrics

Each stage is timed independently. A result is not reported as a single
"INSERT/UPDATE throughput" number when it includes another stage.

| Stage | Operation | Required metrics |
| --- | --- | --- |
| Base load | CSV -> Pixels CLI `LOAD` | rows, wall time, rows/s, files, bytes |
| Online INSERT | Trino `INSERT INTO ... SELECT` or generated rows | rows, wall time, rows/s, sink/index metrics |
| Exact tag UPDATE | finite primary-key predicate | rows, wall time, route, RPC count, tag-index metrics |
| Tag visibility | `COMPACT` after UPDATE | wall time, resolve/index metrics, output rows |
| Tag SELECT | tag predicate plus time predicate | result rows, wall time, correctness |
| Recovery | restart metadata/index/Trino services | count, min/max id, tag counts |

The cold base load and warm online INSERT are different measurements. The
first cold load includes creation of vnode/index state and must be reported
separately from steady-state throughput.

## cds20 baseline captured on 2026-11-19

The data already present on `cds20` is:

```text
/home/pixels/projects/pixels-benchmark/68col-generator/data-1b
```

It contains 128 CSV shards, 1,000,000,000 rows, approximately 121 GiB, and
each sampled row has 68 fields. The current generator emits trailing empty CSV
fields. The loader maps empty text fields to an empty string and empty numeric
fields to NULL, so the generator/fixture must be corrected to emit `\\N` for
strict SQL NULL semantics before the final acceptance run.

An isolated `bench68.tag_events` table was created under the validation
runtime. The following measurements are exploratory and are retained as a
baseline, not as an acceptance result:

| Operation | Rows | Wall time | Approx. throughput | Notes |
| --- | ---: | ---: | ---: | --- |
| CSV base load | 1M | 181.868 s | 5.5k rows/s | 16 loader threads; cold 68-column/index setup |
| table-to-table INSERT | 1M | 19.092 s | 52.4k rows/s | warm table, IDs offset by 1M |
| exact tag UPDATE | 1M | 16.260 s | 61.5k rows/s | two tag assignments; UPDATE reports 1M rows |
| tag-aware COMPACT | 10K | 5.708 s | 1.75k rows/s | single `tag_1_type` assignment; visibility passed |
| tag-aware COMPACT | 90K | 15.347 s | 5.86k rows/s | incremental rewrite only; multi-column case, not accepted |

The daemon metric for the UPDATE recorded two tag-index updates with one
million primary keys each. The tag-index service time was about 5.50 s, of
which storage accounted for about 3.64 s. This is not the whole UPDATE wall
time, but it confirms that the tag-index write path is observable at this
scale.

Before tag-aware compaction, a tag predicate returned zero materialized rows
because the base files still contained the original empty tag values. This is
expected for the current UPDATE contract and makes the separate compaction and
visibility stage mandatory. The first 2M-row tag-aware compaction attempt was
started separately and stopped before completion; it must be rerun with timing
and the compaction metrics captured.

## Multi-column tag correctness finding

The original deployed contract had one configured tag-index column
(`dml.tag.column=tag_1_type`). A single-column UPDATE and compaction were
validated on a 10K-row 68-column table:

- `UPDATE ... SET tag_1_type='AAA' ...`: 10,000 rows in 4.466 s;
- tag-aware COMPACT: 5.708 s;
- `WHERE tag_1_type='AAA'`: 10,000 rows, with the physical value `AAA`.

The same test shape with both `tag_1_type='AAA'` and `tag_1_para=3.14` exposed
a correctness gap: both assignments were appended to the same single-column
Tag Index. Compaction then had no column identity for the two values and the
materialized tag value was corrupted.

This is fixed on the validation branches. Tag Index entries and lookup
requests now carry `columnName`; compaction accepts
`dml.tag.columns=tag_1_type,tag_1_para` and applies independent updates to the
same physical row. Decimal writeback supports both short and long decimal
vectors. The Trino UPDATE handle also converts native unscaled Decimal
constants back to a plain SQL decimal string before canonical encoding; this
prevents `3.1400` from becoming `31400.0000`.

Relevant commits:

- `pixels-dml`: `bc094956`, `93fc3c70`, `892b9b1f`;
- `pixels-trino-dml`: `0d27988`, `1973c10`.

## Clean 10K end-to-end result on 2026-11-19

The final clean run used the real dataset shape, limited to the first 10,000
rows of `part-00000.csv` as a controlled fixture. The source table was
`bench68_v2.tag_source`; the indexed target was
`bench68_v2.tag_events_fixed`. The target was newly created so no abandoned
physical files or old compaction outputs were present.

Exact sequence:

```sql
INSERT INTO pixels.bench68_v2.tag_events_fixed
SELECT * FROM pixels.bench68_v2.tag_source;

UPDATE pixels.bench68_v2.tag_events_fixed
SET tag_1_type = 'CCC', tag_1_para = 3.1400
WHERE id BETWEEN 1 AND 10000;
```

Then:

```text
COMPACT -s bench68_v2 -t tag_events_fixed -n no -c 4
```

Observed results:

| Stage | Rows | Wall time | Approx. rate | Evidence |
| --- | ---: | ---: | ---: | --- |
| CSV -> source table | 10,000 | 3.3 s | 3.0k rows/s | `LOAD` completed successfully |
| table-to-table INSERT | 10,000 | 254 s | 39 rows/s | cold primary-index vnode creation; `primary_put_ms=250147` |
| exact UPDATE | 10,000 | 4 s shell wall | about 2.5k rows/s | MPP route, one RPC; connector total 2.245 s |
| tag-aware COMPACT | 10,000 | 6 s shell wall | about 1.7k rows/s | physical rewrite 1.06 s; resolve 0.17 s; relocation 128 rows/groups |

The Trino route log recorded `executor=MPP exact_eligible=true`, and the
UPDATE metric recorded `rows=10000`, `rpc_count=1`, `request_bytes=200061`.
The final query returned:

```text
rows=10000, tag_1_type='CCC'=10000, tag_1_para=3.1400=10000,
both predicates=10000, min(tag_1_para)=3.1400, max(tag_1_para)=3.1400
```

This proves the complete correctness path for two tag columns, including a
decimal tag value. It is not yet an acceptance throughput result: the 10K
INSERT includes a one-time cold initialization of 128 primary-index vnodes.
The prior warm 1M baselines remain the better steady-state indicators
(approximately 52.4k INSERT rows/s and 61.5k UPDATE rows/s), both below the
hundreds-of-thousands target.

This finding is kept separate from throughput numbers. The current 68-column
benchmark acceptance gate is single configured tag-column correctness first;
the 30-pair tag model remains the target capability to implement and validate.

## Acceptance rules

The benchmark is considered valid only if all of the following are true:

1. every load file has exactly 68 fields;
2. base row count and primary-key min/max are correct;
3. exact UPDATE routing is visible in Trino logs and does not silently fall
   back to row-wise merge;
4. UPDATE timing excludes COMPACT timing;
5. after COMPACT, tag predicates return the expected rows;
6. after restart, row and tag counts remain correct;
7. every result records commit IDs, runtime paths, configuration, and the
   exact SQL used.

## Next experiments

1. Correct the generator null representation and regenerate only the 1M/10M
   fixtures needed for controlled tests.
2. Warm the primary index once, then rerun table-to-table INSERT at 1M and
   10M so cold vnode creation is excluded from steady-state throughput.
3. Rerun the two-column tag E2E on the 10K/100K/1M ladder, recording resolve,
   rewrite, relocation, and publish metrics.
4. Run exact UPDATE at 100K and 1M on the 68-column table, followed by the
   visibility and restart checks.
5. Investigate the primary-index cold-start cost and the remaining warm-path
   gap to the hundreds-of-thousands rows/s target.
6. Only after the ladder is stable, begin the 1B-row load and long-run test.
