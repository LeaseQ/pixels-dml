# Pixels DML Implementation Plan

> **For agentic workers:** Implement this plan task-by-task with a failing test before each production change. Keep the two repositories separate and commit each independently testable feature stage.

**Goal:** Add the first working SQL data-modification path for the Pixels system: Trino `INSERT` writes through Pixels Writer, and batch label `UPDATE` records tag-to-primary-key mappings in RocksDB for later compaction.

**Architecture:** `pixels-trino-dml` owns Trino SPI integration, transaction-facing operation handling, and the worker-side write/update orchestration. `pixels-dml` owns reusable Pixels Writer, RocksDB/index, and compaction-side data handling. `origin` remains the private downstream repository and `upstream` remains the corresponding public repository in each checkout.

**Tech Stack:** Java, Trino SPI 466, Maven, Pixels Writer, Pixels metadata/compaction services, RocksDB.

## Global Constraints

- Keep `pixels-dml` and `pixels-trino-dml` as separate repositories.
- Preserve the meeting scope: `INSERT` and label `UPDATE`; no new `DELETE` feature in the first milestone.
- `INSERT` bypasses Retina and writes with Pixels Writer in the Worker path.
- `UPDATE` records the tag value as the RocksDB key and the affected primary-key list as the value; do not perform in-place file updates in the first milestone.
- Reads during the tagging window do not require strict ACID freshness; Compaction is the convergence point for table scans.
- Every feature stage must have focused tests and its own commit; never use `--no-verify`.

---

### Task 1: Establish the Trino DML contract

**Repositories:** `pixels-trino-dml`

**Files:**
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsMetadata.java`
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsConnector.java`
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsModule.java`
- Create: `connector/src/test/java/io/pixelsdb/pixels/trino/PixelsDmlContractTest.java`

**Interfaces:**
- Consumes: Trino SPI 466 `ConnectorMetadata` and `Connector` extension points already used by the connector.
- Produces: A tested operation contract for INSERT and UPDATE that later write/index stages can consume without changing SQL semantics.

- [ ] Inspect the exact Trino 466 SPI signatures and the current connector transaction handle before editing.
- [ ] Write failing tests for the supported INSERT and UPDATE operation shapes and for explicit rejection of DELETE.
- [ ] Run the focused connector test and confirm it fails because the DML contract is not implemented.
- [ ] Add the minimum metadata/connector hooks needed to expose the contract.
- [ ] Run the focused connector test and confirm it passes.
- [ ] Commit the contract-only change in `pixels-trino-dml`.

### Task 2: Implement the direct Pixels Writer INSERT path

**Repositories:** `pixels-trino-dml`, with reusable writer APIs verified in `pixels-dml`

**Files:**
- Create: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsPageSink.java`
- Create: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsPageSinkProvider.java`
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsConnector.java`
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsModule.java`
- Create: `connector/src/test/java/io/pixelsdb/pixels/trino/PixelsPageSinkTest.java`
- Verify: `pixels-core/src/main/java/io/pixelsdb/pixels/core/PixelsWriter.java`
- Verify: `pixels-core/src/main/java/io/pixelsdb/pixels/core/PixelsWriterImpl.java`

**Interfaces:**
- Consumes: The Task 1 DML contract and the existing `PixelsWriter.addRowBatch`/close lifecycle.
- Produces: A worker-side page sink that flushes complete row groups and closes writers without routing INSERT through Retina.

- [ ] Write a failing page-sink test covering one page, row-group flush, close, and failure cleanup.
- [ ] Run the focused test and confirm it fails because the page sink/provider is absent.
- [ ] Implement the minimal page sink/provider binding and direct writer lifecycle.
- [ ] Run the focused test and the connector module test suite.
- [ ] Commit the INSERT path in `pixels-trino-dml`; commit only reusable core changes separately in `pixels-dml` if the audit proves they are required.

### Task 3: Add the tag-to-primary-key RocksDB persistence boundary

**Repositories:** `pixels-dml`

**Files:**
- Create: `pixels-index/pixels-index-rocksdb/src/main/java/io/pixelsdb/pixels/index/rocksdb/PixelsTagIndex.java`
- Modify: `pixels-index/pixels-index-rocksdb/pom.xml` only if the existing RocksDB dependency does not expose the required API.
- Create: `pixels-index/pixels-index-rocksdb/src/test/java/io/pixelsdb/pixels/index/rocksdb/TestPixelsTagIndex.java`
- Verify: `pixels-index/pixels-index-rocksdb/src/main/java/io/pixelsdb/pixels/index/rocksdb/RocksDBFactory.java`

**Interfaces:**
- Consumes: A tag value and an ordered collection of primary-key values.
- Produces: Durable put/read/iterate operations with explicit serialization, table isolation, and close semantics.

- [ ] Write failing tests for a new tag entry, multiple primary keys under one tag, reopen/readback, and table isolation.
- [ ] Run the focused RocksDB test and confirm the expected failures.
- [ ] Implement the smallest storage boundary on top of the existing RocksDB factory/lifecycle.
- [ ] Run the focused RocksDB tests and the existing RocksDB index tests.
- [ ] Commit the tag-index storage stage in `pixels-dml`.

### Task 4: Connect SQL UPDATE to the tag index

**Repositories:** `pixels-trino-dml`

**Files:**
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsMetadata.java`
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsConnector.java`
- Modify: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsTransactionHandle.java` if operation state must survive transaction callbacks.
- Create: `connector/src/main/java/io/pixelsdb/pixels/trino/PixelsUpdateExecutor.java`
- Create: `connector/src/test/java/io/pixelsdb/pixels/trino/PixelsUpdateExecutorTest.java`

**Interfaces:**
- Consumes: The Task 1 UPDATE contract and the Task 3 tag-index API through the published/local Pixels artifact boundary.
- Produces: Batch label updates that write the agreed tag-to-primary-key representation and report affected rows through Trino.

- [ ] Write failing tests for a non-contiguous primary-key batch, repeated keys, empty input, and an unsupported DELETE operation.
- [ ] Run the focused update test and confirm it fails before the executor exists.
- [ ] Implement the executor and transaction cleanup with no direct file mutation.
- [ ] Run focused update tests plus connector compilation against the matching Pixels artifacts.
- [ ] Commit the UPDATE path in `pixels-trino-dml`.

### Task 5: Integrate Compaction as the convergence step

**Repositories:** `pixels-dml`, then `pixels-trino-dml` only for the trigger/wiring

**Files:**
- Modify: `pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/CompactExecutor.java` only where the tag index must be consumed during rewrite.
- Verify: `pixels-core/src/main/java/io/pixelsdb/pixels/core/compactor/PixelsCompactor.java`
- Create: `pixels-index/pixels-index-rocksdb/src/test/java/io/pixelsdb/pixels/index/rocksdb/TestPixelsTagCompaction.java`
- Modify: `pixels-trino-dml/connector/src/main/java/io/pixelsdb/pixels/trino/PixelsUpdateExecutor.java` only if the agreed trigger is exposed through Connector.

**Interfaces:**
- Consumes: Existing compact layout/file registration APIs and the durable tag index.
- Produces: A compaction path that merges recorded labels into rewritten Pixels files and leaves point lookup/table-scan behavior consistent with the meeting design.

- [ ] Write a failing test for a source file plus tag index entries producing a rewritten file with updated label values.
- [ ] Run the focused compaction test and confirm the merge behavior is absent.
- [ ] Implement the narrowest merge and metadata publication path using existing compactor APIs.
- [ ] Run focused compaction tests and the affected module build.
- [ ] Commit the compaction stage in the repository that owns the implementation.

### Task 6: Run the first end-to-end acceptance path

**Repositories:** both repositories, with test fixtures kept in the owning repository

**Files:**
- Create: `pixels-trino-dml/connector/src/test/java/io/pixelsdb/pixels/trino/PixelsDmlIntegrationTest.java`
- Modify: `pixels-trino-dml/README.md` with the exact local test command and required Pixels artifact versions.
- Modify: `pixels-dml/docs/` with the tested data flow and known first-milestone limits.

**Interfaces:**
- Consumes: The completed INSERT, UPDATE, RocksDB, and Compaction stages.
- Produces: Evidence for `SQL INSERT -> Pixels Writer`, `SQL UPDATE -> RocksDB`, and `Compaction -> query-visible result`.

- [ ] Add the smallest deterministic fixture covering one inserted row group and a non-contiguous label update.
- [ ] Run the integration test and capture the exact command/output.
- [ ] Run focused module tests and builds separately from the integration result.
- [ ] Commit the acceptance test and documentation as the final stage.

