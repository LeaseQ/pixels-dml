# RFC: Isolate `new-main-index` in Grouped INSERT

Date: 2026-11-25  
Branch: `codex/million-update-validation`  
Scope: grouped primary-index INSERT only; no tag or durability semantics changed.

## Question

The branch has two independent grouped-INSERT changes: avoiding the per-row
`List<Boolean>` result and the optional `new-main-index` path. The earlier
aggregate-result A/B kept `new-main-index=false`. This experiment checks whether
the latter is independently useful on the same clean runtime.

## Method and validity gate

Runtime: `e2e-clean-20261125`, Trino `8291`, isolated daemon `29988/29989`,
Retina `29990/28995`, stable full daemon/connector jars, 32 fixed writers,
`task_concurrency=64`, `redistribute_writes=false`, and the same one-million-row
68-column source. Each target was created with `pk=id, pk_scheme=rocksdb`.

A sample is accepted only when all of these hold:

- 1,000,000 rows and 1,000,000 distinct primary keys;
- exact source id range;
- all four representative tag predicates match all rows;
- Trino logs contain `PIXELS_INSERT_INDEX_METRIC` and daemon logs contain the
  grouped primary-index metric.

The earlier `e2e68_newmain_off2_20251125` result is explicitly excluded: it
passed the row/tag query but emitted no primary-index metric, so it was a
no-primary-index sample and not comparable to the indexed workload.

## Results

Stable full jar, with only the `new-main-index` property changed:

| property | target | wall time | rate | validity |
|---|---|---:|---:|---|
| `true` | `e2e68_newmain_control_20251125` | 14.555 s | 68.7K/s | full gate + index metric |
| `true` | `e2e68_newmain_control2_20251125` | 15.627 s | 64.0K/s | full gate + index metric |
| `true` | `e2e68_newmain_true3_20251125` | 14.414 s | 69.4K/s | full gate + index metric |
| `false` | `e2e68_newmain_off3_20251125` | 14.063 s | 71.1K/s | full gate + index metric |

The last, directly comparable samples differ by about 2.4% in favor of
`false`, which is within the observed run-to-run variation. The two earlier
`true` samples are also spread across the same range. Therefore this run does
not establish a standalone benefit for `new-main-index=true`.

For context, the aggregate-result candidate with `new-main-index=false` was
about 3.1% faster than stable across two warm pairs. The aggregate candidate
with `new-main-index=true` showed a larger signal in a separate pair, but that
combines two changes and cannot be attributed to `new-main-index` alone.

## Decision

Keep the default `pixels.index.grouped.primary.write.new-main-index=false`.
Do not claim or merge a default flip based on this A/B. The property remains a
useful experiment switch, but a stronger conclusion needs a randomized,
restart-controlled matrix with the same jar and more valid indexed repetitions.
The next optimization target remains the grouped primary-index/write path,
especially the long-tail `main_index_ms` and its allocation/locking profile.

