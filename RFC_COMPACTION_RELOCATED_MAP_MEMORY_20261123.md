# RFC: Avoid the Compaction Relocation Map Copy

Status: validated on the isolated `cds20` E2E runtime

## Summary

Tag-aware COMPACT stores two million-row maps while it rewrites a 2M-row
table. `PixelsTagCompactor.getRelocatedLocations()` then called
`Map.copyOf(relocatedLocations)` before returning the result to
`CompactExecutor`. The caller immediately transfers that map into
`CompactionResult` and only reads it during publication; the copy therefore
provided no required isolation and increased peak allocation and old-generation
pressure.

The change returns the existing map and documents the ownership transfer. It
does not change the RPC protocol, batching, compaction layout, file naming,
metadata transaction, or index publication order. The default tag-aware path
and non-tag compactor behavior are unchanged apart from avoiding this copy.

## Why this is safe

The getter is package-private and is called once, immediately after
`PixelsTagCompactor.compact()` returns. The compactor instance is not used after
that point. `CompactionResult` keeps the returned map and only iterates it while
publishing the compacted file. No caller mutates the map. The companion
`primaryKeysByRowId` map already follows this direct ownership-transfer
pattern.

Rollback is one line: restore `return Map.copyOf(relocatedLocations);`.

## E2E method

Each candidate used the same isolated runtime and schema shape:

- 68 columns: `id`, six dimensions, and 30 `(tag_N_type, tag_N_para)` pairs;
- explicit `pk='id'`, `pk_scheme='rocksdb'`, and an independent table root;
- two table-to-table INSERT statements of 1,000,000 rows each, with the second
  range offset by 1,000,000,000;
- one 1,000,000-row UPDATE of `tag_1_type` and `tag_1_para`;
- tag-aware `COMPACT -n no -c 8`, with primary resolve and relocation
  parallelism both set to 8.

The main `cds20` runtime was not changed. All daemon, Trino, and Retina
processes used for these tests were in the isolated runtime under
`runtime/e2e-fresh-20261122`.

## Evidence

| Candidate | Change | Result | Correctness |
|---|---|---:|---|
| candidate39 | baseline getter with `Map.copyOf` | timed out at 300 s; only one of two output files completed | not used as a post-COMPACT result |
| candidate40 | direct map ownership | 17 s total; rewrite wall 11.539 s; publish 5.789 s | passed |
| candidate41 | direct map ownership, repeat | 17 s total; rewrite wall 11.934 s; publish 5.375 s | passed |

Candidate40 metrics:

```text
tagged_rows=1000000 resolve_rpc_count=62 resolve_ms=1842.370877
physical_rewrite outputs=2 worker_ms=18058.344589 worker_wall_ms=11538.527736
resolve rows=2000000 resolve_rpc_count=144 resolve_ms=18325.414028
relocated_rows=2000000 relocation_rpc_count=256
publish outputs=2 publish_ms=5788.636911
```

Candidate41 metrics:

```text
tagged_rows=1000000 resolve_rpc_count=62 resolve_ms=1819.699775
physical_rewrite outputs=2 worker_ms=18898.953106 worker_wall_ms=11934.126779
resolve rows=2000000 resolve_rpc_count=144 resolve_ms=18620.883208
relocated_rows=2000000 relocation_rpc_count=256
publish outputs=2 publish_ms=5374.674526
```

Both successful candidates returned the same verification tuple:

```text
count=2000000
distinct_id=2000000
min_id=7000000001
max_id=8001000000
updated_tag_type=1000000
updated_tag_para=1000000
tag_2_type_is_null=2000000
```

The instrumented candidate40 run also recorded approximately 4.6 GB peak RSS
for the CLI and old-generation occupancy near 99%, but completed without a
long tail. The failed baseline run had approximately 4.8 GB RSS, old generation
near 99%, and 0% I/O wait; this supports memory pressure as a plausible cause
of the previous long tail, but is not presented as a complete heap profile.

## Rejected neighboring experiments

- Splitting 48 source files into six tasks (`source-cap=8`) increased rewrite
  wall from about 35 s to 92.6 s and total COMPACT from about 55 s to 104 s.
- Increasing the primary resolve batch from 16,384 to 32,768 reduced tag
  snapshot RPCs but did not reduce resolve time or end-to-end wall time.
- Cross-source pending-batch merging reduced source resolve RPCs from roughly
  146 to 121–122, but repeated end-to-end wall times were not stable. It is not
  included in this change.

## Artifacts

Remote logs:

```text
runtime/e2e-fresh-20261122/logs/compact-candidate39-default-instrumented-20261123.log
runtime/e2e-fresh-20261122/logs/monitor-candidate39-default-20261123.raw
runtime/e2e-fresh-20261122/logs/compact-candidate40-map-no-copy-instrumented-20261123.log
runtime/e2e-fresh-20261122/logs/monitor-candidate40-map-no-copy-20261123.jstat
runtime/e2e-fresh-20261122/logs/compact-candidate41-map-no-copy-20261123.log
```

The isolated validation jar containing this change had SHA256:

```text
d9b769b3bed5964369a73dcc27f41d95ea0e8512859733b03ec8eca345324c28
```

Targeted CLI tests also passed: 6 tests, 0 failures, 0 errors, using
`-Dpixels.skip.tests=false` for `PixelsTagCompactorSnapshotTest`,
`PixelsTagCompactorRelocationTest`, `PixelsTagCompactorOutputPositionTest`,
and `PixelsTagCompactorIndexRoutingTest`.
