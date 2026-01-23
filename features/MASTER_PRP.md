# MASTER PRP: Flink-based MySQL → IoTDB migration (Backfill + CDC)

## Background

This repository is a feasibility study for using Apache Flink as a migration/replication “gear” between:

- **Source**: MySQL
- **Target**: Apache IoTDB

The current implementation is a **one-off/bounded** migration job:

- Main class: `com.example.MySQLToIoTDBJob`
- Code: `flink-migration/src/main/java/com/example/MySQLToIoTDBJob.java`
- It creates a Flink **JDBC** source table and runs a snapshot `SELECT ... FROM <table>`; the job completes after scanning the table.

Runtime environment is Docker-based:

- `docker-compose.yml` mounts:
  - `./flink-job` → `/opt/flink/job` (job JAR)
  - `./config` → `/opt/flink/config` (YAML configs)

The repo also includes a pipeline script that repeatedly resets containers/volumes, optionally generates dummy data, and submits the Flink job.

## Current Behavior (as-is)

- Pipeline submits the job with `flink run -c com.example.MySQLToIoTDBJob /opt/flink/job/job.jar`.
- Job reads YAML config (default `/opt/flink/config/config.yaml`).
- For each `iotdb.mappings[]` entry, it:
  - declares a Flink JDBC table using `mysql.tables[].columns`
  - runs a `SELECT` that projects `device_field`, `timestamp_field`, and measurement fields
  - converts rows into IoTDB sink maps and writes via `IoTDBSink`

## Discussion Summary

### One-off migration vs Continuous replication

- **One-off migration** is good for backfilling historical rows but does not keep IoTDB current as MySQL changes.
- **Continuous replication** in Flink requires an **unbounded source**. In practice this means using **MySQL CDC** (binlog-based, Debezium-powered connector), plus checkpointing/state for correctness.

### Why row immutability matters

MySQL CDC emits change events that include `INSERT`, `UPDATE_BEFORE`, `UPDATE_AFTER`, and `DELETE`. Complexity depends on whether the source rows are immutable:

- **Immutable / append-only** (preferred for simplest PoC):
  - CDC stream is effectively “new events” only.
  - Sink can primarily handle `INSERT` (and optionally treat `UPDATE_AFTER` as overwrite).

- **Mutable rows** (updates/deletes happen):
  - We must define **update semantics** in IoTDB:
    - overwrite values at the same timestamp
    - propagate deletes (requires delete-capable sink logic)
    - or version corrections under a different path

This is the core decision behind “how CDC should behave”.

### “Best of both”: Backfill + CDC

Recommended migration pattern:

1. **Backfill (bounded)**: migrate historical MySQL data into IoTDB quickly.
2. **CDC (unbounded)**: keep IoTDB in sync while MySQL and IoTDB coexist.
3. **Cutover**: briefly pause/queue MySQL writes, wait until CDC lag reaches 0, then switch the application to write IoTDB-only and retire MySQL.

### Job packaging (one JAR vs two JARs)

We can keep a **single built artifact** (`job.jar`) while still maintaining clarity by providing **two entrypoints** (two main classes):

- `MySQLToIoTDBBackfillJob` (bounded JDBC scan)
- `MySQLToIoTDBCdcJob` (unbounded CDC)

Flink submission picks the entrypoint with `flink run -c <MainClass> ...`.

Alternative: one main with `--mode backfill|cdc`, but two mains is usually easier to operate and tune independently.

### Flink Web UI submission + config

Because both config and JAR are already volume-mounted via `docker-compose.yml`, jobs can be submitted either by:

- CLI (`docker exec ... flink run ...`)
- Flink Web UI / REST (referencing `/opt/flink/job/job.jar` and `/opt/flink/config/...`)

**Conclusion**: use a **config file as the source of truth** (YAML), and rely on platform mounting mechanisms.

- Docker: `docker-compose.yml` already mounts `./config` → `/opt/flink/config`.
- Kubernetes (future): mount the YAML at `/opt/flink/config/config.yaml`.
  - Preferred (no refactor): store the whole YAML as a **Secret**.
  - Optional refinement (requires code/deploy templating): split non-secret config in a ConfigMap and secrets in a Secret.

To avoid ambiguity, introduce an explicit mode switch in the config file:

- YAML: `mode: backfill|cdc`

## Proposed Decisions

### D1: Adopt Backfill + CDC strategy

- Keep a bounded backfill job for historical migration.
- Add a CDC job for ongoing changes during coexistence.

### D2: Ship one artifact with two entrypoints

- One Maven module can build one `job.jar` containing both mains.
- Submission selects `BackfillJob` or `CdcJob` via `-c`.

### D3: Make update semantics explicit

- Document whether each table is treated as append-only.
- If updates/deletes exist, define how they map into IoTDB (overwrite/delete/version).

### D4: Prefer config-file driven operation

- Keep YAML config file as the primary interface.
- CLI args remain optional; not required for normal Docker/K8s deployments.

## Requirements

### Functional

- **Backfill mode**:
  - Migrate all historical rows from mapped MySQL tables and terminate.
- **CDC mode**:
  - Continuously replicate MySQL changes into IoTDB.
  - At minimum support `INSERT` events.
  - `UPDATE`/`DELETE` behavior must be explicitly defined.
- **Config-driven mappings**:
  - Reuse existing YAML mapping structure (`iotdb.mappings`) for both modes.

### Operational

- Jobs must run in the Docker-based Flink cluster.
- Config and JAR must remain usable via both CLI and Web UI submissions.
- CDC mode must enable checkpointing and define restart behavior.
- K8s target: YAML mounted as a file (Secret preferred if avoiding refactor).

### MySQL CDC prerequisites

- MySQL must have binlogs enabled and row-based:
  - `log_bin=ON`
  - `binlog_format=ROW`
  - `binlog_row_image=FULL`
- CDC user must have replication and select privileges.
- Binlog retention must be long enough to support recovery.

## Open Questions

- Are source tables strictly append-only, or do updates/deletes occur?
- Is `(deviceId, createdAt)` unique and stable enough to be treated as the time-series key in IoTDB?
- What delivery guarantee is required in CDC mode (at-least-once acceptable vs exactly-once required)?
