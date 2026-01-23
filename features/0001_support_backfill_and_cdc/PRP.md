# PRP: 0001 Support Backfill and CDC

## Objective

Extend the Flink migration PoC to support two operational modes:

- **Backfill (bounded)**: migrate historical MySQL data into IoTDB and terminate.
- **CDC (unbounded)**: continuously replicate MySQL changes into IoTDB.

Both modes should reuse the existing YAML mapping model and run in the existing Docker-based Flink cluster.

## Background / Current State

- Existing job entrypoint: `com.example.MySQLToIoTDBJob`
- Code: `flink-migration/src/main/java/com/example/MySQLToIoTDBJob.java`
- Source connector: Flink `jdbc` connector (bounded snapshot scan)
- Sink connector: IoTDB Flink sink (`IoTDBSink` + `DefaultIoTSerializationSchema`)
- Config: YAML mounted at `/opt/flink/config/config.yaml` via `docker-compose.yml`

The current job builds a JDBC table per mapping, queries it, converts rows to a Map payload, and sinks to IoTDB.

## Proposed Design

### Entry points

Implement two entrypoints (two main classes) in the same Maven module/JAR:

- `com.example.MySQLToIoTDBBackfillJob`
- `com.example.MySQLToIoTDBCdcJob`

Rationale:

- Keeps operational intent explicit (one job ends; the other runs forever).
- Allows different runtime settings (checkpointing, restart strategy, parallelism) per mode.
- Still ships as one artifact; submission selects main via `flink run -c ...`.

### Configuration

- Continue to use a single YAML file as the source of truth.
- Add `mode: backfill|cdc` (optional if using two mains; still useful for validation/logging).
- Keep `iotdb.mappings[]` and `mysql.tables[]` reused in both modes.

### Backfill mode behavior

- Use the existing JDBC connector approach.
- Treat the job as bounded; it terminates after completing the scan.
- Preserve existing partitioning options to parallelize snapshot reads.

### CDC mode behavior

- Replace the bounded JDBC table with a CDC source table using Flink MySQL CDC connector.
- Convert the resulting Table to a **changelog stream** (not a plain append stream).
- Apply a defined policy for `INSERT/UPDATE/DELETE` events.

## CDC Semantics (initial)

Because CDC correctness depends on whether source rows are immutable, explicitly choose initial behavior:

- **Minimum requirement**: process `INSERT` events.
- **Optional**: treat `UPDATE_AFTER` as overwrite by writing the new values to IoTDB.
- **Out of scope (unless required)**: propagating deletes into IoTDB.

This policy must be documented and enforced in code.

## Operational Requirements

### Docker environment

- Keep using `docker-compose.yml` volume mounts:
  - job jar at `/opt/flink/job/job.jar`
  - YAML config at `/opt/flink/config/config.yaml`

### Checkpointing (CDC)

- CDC job must enable checkpointing and define a restart strategy.
- State/checkpoint storage must be configured appropriately for the target environment.

### MySQL prerequisites (CDC)

- MySQL binlog enabled and row-based (`log_bin`, `binlog_format=ROW`, `binlog_row_image=FULL`).
- CDC user privileges: `SELECT` + replication privileges.

## Deliverables

- New main class `MySQLToIoTDBBackfillJob`.
- New main class `MySQLToIoTDBCdcJob`.
- Updated build so both are packaged in the same JAR.
- Updated docs/scripts to show how to run each mode.

## Testing / Validation

- Backfill: run job once and verify IoTDB has expected data.
- CDC: insert new rows into MySQL after job starts and verify they appear in IoTDB.
- CDC (optional): update a row and verify overwrite behavior if enabled.

## Open Questions

## Open Questions (resolved assumptions)

- Source tables are treated as **append-only** (no updates/deletes required for this subfeature).
- `(deviceId, createdAt)` is assumed **unique and stable** and is used as the time-series key.
- CDC delivery guarantee target: **at-least-once** is acceptable.
