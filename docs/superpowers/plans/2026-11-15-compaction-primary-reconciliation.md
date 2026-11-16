# Compaction Primary-Index Reconciliation Implementation Plan

> **For agentic workers:** Execute this plan task-by-task with a failing test before each production change. Preserve all existing dirty work and do not commit or push unless explicitly requested.

**Goal:** Make tag-aware Compaction relocate every row rewritten into the new file while keeping UPDATE tag application and the existing IndexServer architecture unchanged.

**Architecture:** The Trino exact UPDATE path and the shared Tag Index remain unchanged. During tag-aware Compaction, the CLI will derive primary-key bytes from each source row, resolve them through the existing batched IndexServer RPC, and batch the resulting stable row IDs and new locations through the existing primary-index write RPC before metadata atomic swap.

**Tech Stack:** Java 8-compatible Pixels CLI, Pixels core vector batches, gRPC/protobuf IndexService, JUnit 5, Maven.

## Global Constraints

- Preserve all existing uncommitted changes in `pixels-dml` and `pixels-trino-dml`.
- Do not add a new service, transaction layer, SQL route, or DELETE implementation.
- Keep IndexServer RPCs bounded; no million-row protobuf request is required for Compaction.
- Keep metadata publication after successful file rewrite and primary-index relocation.
- Test the exact changed modules with `-Dpixels.skip.tests=false`; report missing native build tools separately.

### Task 1: Lock the all-row relocation contract

**Files:**
- Create: `pixels-cli/src/test/java/io/pixelsdb/pixels/cli/executor/PixelsTagCompactorRelocationTest.java`
- Modify: `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/PixelsTagCompactor.java`

- [ ] Add a test proving that an ordered batch of resolved primary entries produces relocation records for both tagged and untagged rows, preserving row ID, primary key, and output position alignment.
- [ ] Run the focused test and verify it fails because the all-row relocation helper is absent.
- [ ] Add the smallest package-private helper used by the compactor to record resolved rows and reject mismatched response sizes.
- [ ] Run the focused test and verify it passes.

### Task 2: Resolve source rows in bounded batches during tag-aware Compaction

**Files:**
- Modify: `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/CompactExecutor.java`
- Modify: `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/PixelsTagCompactor.java`

- [ ] Pass primary-key column names/types from metadata into `PixelsTagCompactor` and derive source-vector ordinals from the file schema.
- [ ] Accumulate source-row primary keys and their output locations up to a bounded RPC batch size.
- [ ] Call `IndexService.resolvePrimaryEntries` for each batch and record all returned row IDs and primary keys, not only rows present in Tag Index.
- [ ] Keep existing Tag Index update-map construction and latest-tag semantics unchanged.
- [ ] Run CLI focused tests and the row-location million-row probe.

### Task 3: Verify performance and publication behavior

**Files:**
- Modify: `pixels-cli/src/test/java/io/pixelsdb/pixels/cli/executor/CompactExecutorIndexRelocationTest.java` only if the new all-row contract needs coverage.

- [ ] Run the CLI package build with tests enabled.
- [ ] Run the daemon gRPC probe and Trino exact/fallback probes separately.
- [ ] Run `git diff --check` and inspect the final two-repository status.
- [ ] Record whether a real Trino SQL E2E is blocked by missing local Trino/etcd/daemon runtime rather than by code.
