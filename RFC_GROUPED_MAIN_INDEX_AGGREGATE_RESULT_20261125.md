# RFC: Avoid Per-Row Main-Index Result Lists in Grouped INSERT

Date: 2026-11-25
Branch: `codex/million-update-validation`
Scope: grouped primary-index INSERT path only; tag semantics and durability requirements unchanged.

## Motivation

`IndexServiceImpl.putPrimaryIndexEntries` used `MainIndex.putEntries` or
`putNewEntries`, which returned one boxed `Boolean` per input row. The caller
only needed the aggregate success result, so the grouped INSERT path paid for a
large temporary result list and its iteration on every batch.

## Change

- Add compatibility-preserving `putEntriesAndCheck` and `putNewEntriesAndCheck`
  methods to `MainIndex`; the default implementations preserve behavior for
  other implementations.
- Override both methods in `SqliteMainIndex`. They hold the existing write lock,
  insert rows directly, return `false` on the first failed insert, and avoid the
  per-row `ImmutableList<Boolean>` allocation.
- Use the aggregate methods only in the grouped primary-index branch of
  `IndexServiceImpl`. The ordinary single-point path is unchanged.
- Keep `pixels.index.grouped.primary.write.new-main-index=false`; this experiment
  isolates aggregate-result handling from the separate insert-only main-index
  candidate.

## Candidate provenance

The clean-runtime candidate fat jar used for the E2E comparison was:

`664f1ce6485930f08453b4a962b6d0f71ad25d690117f5873c06f6e73b4883d6`

The candidate bytecode was independently inspected before restoring the clean
runtime to stable. It invoked the two new aggregate methods only for the grouped
branch.

## Clean E2E A/B

Runtime: `e2e-clean-20261125`, Trino port `8291`, clean daemon ports `29988/29989/29990/29991`,
32 fixed writers, `task_concurrency=64`, `redistribute_writes=false`, one-million-row
source, full 68-column target with `pk=id, pk_scheme=rocksdb`.

Every sample passed the full gate: 1,000,000 rows, 1,000,000 distinct primary
keys, exact min/max range, and all rows matching `tag_1_type=U111`,
`tag_1_para=111.0000`, `tag_30_type=AAA`, and `tag_30_para=3.1400`.

| arm | query | wall time | rate |
|---|---|---:|---:|
| stable control (first/cold) | `20261125_122606_00003_5i88v` | 19.734 s | 50.7K/s |
| aggregate candidate (first/cold) | `20261125_122828_00006_5i88v` | 11.658 s | 85.8K/s |
| aggregate candidate 2 | `20261125_122934_00009_5i88v` | 13.297 s | 75.2K/s |
| stable control 2 | `20261125_123109_00012_5i88v` | 13.780 s | 72.6K/s |
| aggregate candidate 3 | `20261125_123301_00015_5i88v` | 11.479 s | 87.1K/s |
| stable control 3 | `20261125_123514_00018_5i88v` | 11.777 s | 84.9K/s |

The first pair is treated as a cold-start outlier and is not used for the
claim. Across the two warm reverse/third pairs, the candidate averaged 12.388 s
versus 12.779 s for stable, approximately **3.1% faster**. This is a modest,
repeatable signal, not a claim of reaching the overall 100K–1M rows/s goal.

## Decision and follow-up

The implementation is suitable to preserve as a default-safe optimization: it
does not alter tag/index contents, locking scope, failure handling, or the
`new-main-index` policy. The clean runtime was restored to the stable daemon
after the A/B. The next performance work should target the remaining grouped
primary-index and writer stages; this change alone leaves the clean E2E sample at
roughly 75K–87K rows/s.

Before any release claim, repeat the warm A/B after a restart and include the
existing tag-aware compaction/readback durability gate.
