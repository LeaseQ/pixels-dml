# RFC: Correct the INSERT Baseline Attribution

Date: 2026-11-25  
Branch: `codex/million-update-validation`  
Scope: clean-runtime INSERT performance evidence and candidate attribution.

## Finding

The earlier aggregate-result A/B was not a single-variable comparison. The
clean runtime stable jar still contained the older `MainIndexBuffer` bytecode:
its `insert()` scanned the per-file buffers and attempted a `HashMap.remove` for
each row. The aggregate candidate jar also contained the later lazy reverse
lookup implementation from commit `71eda92a`, in addition to the aggregate
result methods. Therefore the earlier approximately 3.1% candidate improvement
cannot be attributed to aggregate result handling and is withdrawn.

This was verified from the deployed jars with `javap`: stable had no
`insertNew()` method and the candidate had both the lazy `insert()` bytecode and
`insertNew()`.

## Controlled follow-up

The same clean runtime used the same one-million-row, 68-column source,
`pk=id, pk_scheme=rocksdb`, 32 fixed writers, `task_concurrency=64`,
`redistribute_writes=false`, and the full row/distinct/range/tag gate.

| arm | first/cold | second/warm | validity |
|---|---:|---:|---|
| current branch candidate (lazy `MainIndexBuffer` + aggregate methods) | 17.236 s / 58.0K/s | 12.523 s / 79.9K/s | full gate + index metric |
| lazy-only overlay (current `MainIndexBuffer`, old stable remaining classes) | 16.447 s / 60.8K/s | 11.901 s / 84.0K/s | full gate + index metric |

The candidate fat jar was
`664f1ce6485930f08453b4a962b6d0f71ad25d690117f5873c06f6e73b4883d6`; the
lazy-only overlay was
`5efcad8f8174c1e2ab4e5c97a049887ffbc4685f3ebf60f65edf0724cf15dec1`.

The sequence is not a randomized release benchmark, so the 5.2% warm
lazy-only advantage is directional rather than a final causal estimate. It is
nevertheless consistent with the Retina JFR evidence: the old path spent about
50% of Java execution samples in `HashMap.removeNode`, while the call chain
ended at `MainIndexBuffer.insert`.

## Decision

- Withdraw the previous claim that aggregate result handling alone was a
  measured 3.1% improvement.
- Treat the lazy `MainIndexBuffer` path as the leading current INSERT
  improvement; its E2E warm result is about 84K rows/s on this clean setup.
- Keep the aggregate methods as an experimental, semantics-preserving change,
  but do not claim a performance gain or use it as the next default decision
  without a randomized same-baseline A/B.
- Keep the `new-main-index` default false; its standalone A/B remains
  inconclusive.

