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
2. Rerun tag-aware COMPACT on a 10K/100K/1M ladder, recording resolve and
   primary-index publication metrics.
3. Run exact UPDATE at 100K and 1M on the 68-column table, followed by the
   visibility and restart checks.
4. Repeat warm INSERT with 1M and 10M rows, then test the 100M fixture.
5. Only after the ladder is stable, begin the 1B-row load and long-run test.
