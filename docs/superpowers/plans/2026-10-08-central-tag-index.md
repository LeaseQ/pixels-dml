# Central Tag Index for Distributed Pixels DML Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make UPDATE tag state durable in the existing Pixels IndexServer so Trino Workers, point lookup, and Compaction observe one tag index while preserving Worker-direct INSERT and the current no-DELETE boundary.

**Architecture:** Extend the existing IndexService protocol and implementation with tag-index batch update/read operations backed by the existing `PixelsTagIndex` RocksDB column family. Trino UPDATE writes tag-to-primary-key changes through that existing service, point lookup reads a tag through it, and Compaction lists tags and resolves primary keys through the same IndexServer; no new transaction subsystem is introduced.

**Tech Stack:** Java, gRPC/protobuf, RocksDB, Trino Connector, Pixels CLI, JUnit.

## Global Constraints

- Preserve all existing uncommitted changes in both repositories.
- Keep INSERT as Worker-direct Pixels-file writing.
- Keep DELETE unsupported in the first release.
- Do not add a transaction subsystem or a separate RPC service; extend the existing IndexService only.
- Preserve latest-tag-wins semantics for a primary key.
- Metadata publication remains after successful file rewrite and index relocation; failed Compaction must not publish compact files.
- Validate module tests and the isolated Trino/Pixels E2E separately; do not call module tests E2E acceptance.

## File Map

- Modify `/Users/bytedance/Documents/ChatGPT/pixels/pixels-dml/proto/index.proto`: add tag-index update/read/list messages and RPCs to the existing IndexService.
- Modify `/Users/bytedance/Documents/ChatGPT/pixels/pixels-dml/pixels-common/src/main/java/io/pixelsdb/pixels/common/index/service/IndexService.java`: expose typed tag-index operations and primary resolution/relocation operations required by Compaction.
- Modify `/Users/bytedance/Documents/ChatGPT/pixels/pixels-dml/pixels-common/src/main/java/io/pixelsdb/pixels/common/index/service/RPCIndexService.java`: implement the existing-service RPC client methods.
- Modify `/Users/bytedance/Documents/ChatGPT/pixels/pixels-dml/pixels-daemon/src/main/java/io/pixelsdb/pixels/daemon/index/IndexServiceImpl.java`: persist tag updates, list/read tags, and return stable primary row IDs plus locations from IndexServer.
- Modify `/Users/bytedance/Documents/ChatGPT/pixels/pixels-dml/pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/CompactExecutor.java` and `PixelsTagCompactor.java`: consume the IndexService instead of local SinglePointIndexFactory/MainIndexFactory.
- Modify `/Users/bytedance/Documents/ChatGPT/pixels/pixels-trino-dml/connector/src/main/java/io/pixelsdb/pixels/trino/PixelsMergeSink.java` and `PixelsSplitManager.java`: write/read tags through the RPC service while retaining the current SQL routing behavior.
- Add focused tests beside the changed modules for latest-tag-wins, RPC round trip, Compaction primary resolution, and publication ordering.

### Task 1: Define the existing-service tag and primary-resolution contract

**Files:**
- Modify: `proto/index.proto`
- Modify: `pixels-common/src/main/java/io/pixelsdb/pixels/common/index/service/IndexService.java`
- Test: `pixels-common/src/test/java/io/pixelsdb/pixels/common/index/service/TagIndexServiceContractTest.java`

**Interfaces:**
- `appendTagIndexEntries(long tableId, long indexId, List<TagIndexUpdate> updates, IndexOption option)` writes one latest tag per primary key.
- `getTagIndexEntries(long tableId, long indexId, ByteString tag, IndexOption option)` returns primary-key bytes for one tag.
- `listTagIndexEntries(long tableId, long indexId, IndexOption option)` returns all tag-to-primary-key entries for Compaction.
- `resolvePrimaryEntries(long tableId, long indexId, List<IndexKey> keys, IndexOption option)` returns stable row IDs and current row locations in input order.
- `relocatePrimaryEntries(long tableId, long indexId, List<PrimaryIndexEntry> entries, IndexOption option)` updates existing row IDs to new file locations.

- [ ] **Step 1: Write failing contract tests** for latest-tag-wins, duplicate update idempotency, missing-tag empty result, and input-order-preserving primary resolution.
- [ ] **Step 2: Run the focused test and verify it fails because the contract methods/messages do not exist.**
- [ ] **Step 3: Add protobuf messages/RPCs to `IndexService` and Java interface signatures.**
- [ ] **Step 4: Regenerate protobuf classes through the module Maven generate-sources lifecycle.**
- [ ] **Step 5: Run the focused contract test and verify the remaining failures are implementation failures, not compilation failures.**

### Task 2: Implement IndexServer-backed tag storage and primary relocation

**Files:**
- Modify: `pixels-daemon/src/main/java/io/pixelsdb/pixels/daemon/index/IndexServiceImpl.java`
- Modify: `pixels-common/src/main/java/io/pixelsdb/pixels/common/index/service/RPCIndexService.java`
- Test: `pixels-daemon/src/test/java/io/pixelsdb/pixels/daemon/index/IndexServiceImplTagIndexTest.java`

- [ ] **Step 1: Add a failing daemon test** that sends two tags for one PK, verifies the PK is removed from the old tag and present once under the new tag, and verifies resolve/relocate preserves the row ID.
- [ ] **Step 2: Run the daemon test and verify it fails before the server implementation exists.**
- [ ] **Step 3: Implement tag operations by opening `PixelsTagIndex(tableId, indexId, TAG_VNODE_ID)` inside IndexServer and delegating latest-tag-wins batch updates/list/get to it.**
- [ ] **Step 4: Implement primary resolution so the server returns row IDs and locations from its local SinglePointIndex/MainIndex factories.**
- [ ] **Step 5: Implement relocation as a batch MainIndex update for the same row IDs and locations, without changing primary-key ownership.**
- [ ] **Step 6: Implement matching RPC client calls in `RPCIndexService`.**
- [ ] **Step 7: Run the focused daemon/common tests and verify they pass.**

### Task 3: Route Trino UPDATE and tag point lookup through IndexServer

**Files:**
- Modify: `pixels-trino-dml/connector/src/main/java/io/pixelsdb/pixels/trino/PixelsMergeSink.java`
- Modify: `pixels-trino-dml/connector/src/main/java/io/pixelsdb/pixels/trino/PixelsSplitManager.java`
- Test: `pixels-trino-dml/connector/src/test/java/io/pixelsdb/pixels/trino/PixelsMergeSinkTest.java`

- [ ] **Step 1: Add failing connector tests** proving UPDATE sends a tag/PK batch to the configured RPC IndexService and tag point lookup reads the same service rather than opening Worker-local RocksDB.
- [ ] **Step 2: Run the focused tests and verify they fail because the production path still instantiates `PixelsTagIndex` directly.**
- [ ] **Step 3: Inject/select the configured `IndexService` in `PixelsMergeSink` and batch tag updates at `finish()`, failing the UPDATE if the server does not acknowledge them.**
- [ ] **Step 4: Replace direct tag-index reads in `PixelsSplitManager` with the RPC tag lookup while retaining the existing primary lookup and final row predicate check.**
- [ ] **Step 5: Run the connector module tests and verify all existing tests plus the new routing tests pass.**

### Task 4: Route Compaction through the same IndexServer

**Files:**
- Modify: `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/CompactExecutor.java`
- Modify: `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/PixelsTagCompactor.java`
- Test: `pixels-cli/src/test/java/io/pixelsdb/pixels/cli/executor/PixelsTagCompactorIndexRoutingTest.java`
- Test: `pixels-cli/src/test/java/io/pixelsdb/pixels/cli/executor/CompactExecutorPublicationTest.java`

- [ ] **Step 1: Add a failing routing test** that supplies an IndexService test double and verifies Compaction requests all tags, resolves PKs through the service, and relocates stable row IDs; it must not instantiate local factories.
- [ ] **Step 2: Run the focused test and verify it fails at the current local `SinglePointIndexFactory`/`MainIndexFactory` path.**
- [ ] **Step 3: Inject the existing IndexService into `CompactExecutor`/`PixelsTagCompactor`, replacing local primary and main-index lookups with resolve/relocate calls.**
- [ ] **Step 4: Keep the current tag conflict check and latest-tag-wins input semantics; preserve the current failure before metadata publication.**
- [ ] **Step 5: Run CLI focused tests and verify successful rewrite, relocation, and failure publication guards.**

### Task 5: Validate clean module builds and real E2E

**Files:**
- No production files; use the existing isolated runtime configuration and test artifacts.

- [ ] **Step 1: Check both Git worktrees and confirm only intended changes plus the plan are present.**
- [ ] **Step 2: Run Pixels focused tests and Trino connector tests with the available JDK/dependency setup.**
- [ ] **Step 3: Rebuild the current Pixels daemon/CLI and Trino connector artifacts.**
- [ ] **Step 4: Restart only the isolated etcd/Coordinator/IndexServer/Trino E2E resources with separate host/container configs.**
- [ ] **Step 5: Execute clean SQL flow: CREATE TABLE, INSERT, ordinary scan, exact UPDATE, batch UPDATE, tag point lookup before/after Compaction, and DELETE rejection.**
- [ ] **Step 6: Run CLI Compaction and verify the new Pixels file, updated tag query results, full scan results, and metadata publication ordering.**
- [ ] **Step 7: Report source evidence, module-test evidence, runtime evidence, and any remaining gaps separately; do not commit or push unless explicitly requested.**

## Self-Review Checklist

- The plan preserves Worker-direct INSERT and DELETE rejection.
- The plan explicitly handles both the current tag-index locality problem and the current primary row-ID lookup problem.
- The plan uses the existing IndexServer rather than introducing a new service or transaction layer.
- Tests precede production changes for each implementation unit.
- E2E validation is separated from module-test results.
