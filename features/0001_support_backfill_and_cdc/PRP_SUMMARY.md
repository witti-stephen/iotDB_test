# PRP Summary: 0001 Support Backfill and CDC

## Context

This repo is a feasibility study using Apache Flink to move data from MySQL (source) to IoTDB (target).

We need both:

- **Backfill** (bounded migration) for historical data
- **CDC** (continuous replication) while MySQL and IoTDB coexist

## Decision

- Use **Flink 1.20.x** runtime for this PoC because Flink 2.x removes legacy APIs (`SinkFunction`, `SourceFunction`, `RestartStrategies`) and the current IoTDB Flink connector depends on them.
- Implement **two explicit entrypoints** (two main classes) packaged in a single job JAR:
  - `com.example.MySQLToIoTDBBackfillJob`
  - `com.example.MySQLToIoTDBCdcJob`
- CDC mode uses the **MySQL CDC connector** (`mysql-cdc`) and reads a changelog stream, but currently filters to **INSERT-only** events (append-only assumption).

## Implementation (what changed)

### New entrypoints

- `flink-migration/src/main/java/com/example/MySQLToIoTDBBackfillJob.java`
- `flink-migration/src/main/java/com/example/MySQLToIoTDBCdcJob.java`

`flink-migration/src/main/java/com/example/MySQLToIoTDBJob.java` remains as a backwards-compatible entrypoint and delegates to backfill.

### Shared helpers

- `flink-migration/src/main/java/com/example/ConfigLoader.java`
- `flink-migration/src/main/java/com/example/MigrationConfig.java`
- `flink-migration/src/main/java/com/example/SchemaBuilder.java`
- `flink-migration/src/main/java/com/example/QueryBuilder.java`
- `flink-migration/src/main/java/com/example/IoTDBSinkFactory.java`
- `flink-migration/src/main/java/com/example/RowToIoTDBMapMapper.java`

### Null-handling for IoTDB sink

The IoTDB sink serialization fails when any measurement value is NULL (it tries to parse the string "null").

- Implemented **skip-null-measurement** behavior:
  - Null-valued measurements are omitted from the `measurements/types/values` lists.
  - If all measurements are null, the row is dropped.

Files:

- `flink-migration/src/main/java/com/example/RowToIoTDBMapMapper.java`
- `flink-migration/src/main/java/com/example/MySQLToIoTDBBackfillJob.java`
- `flink-migration/src/main/java/com/example/MySQLToIoTDBCdcJob.java`

### Build and scripts

- `flink-migration/pom.xml`
  - Adds MySQL CDC connector dependency
  - Sets shaded jar default `mainClass` to `MySQLToIoTDBBackfillJob`
- `scripts/migration_pipeline.sh` submits backfill via `MySQLToIoTDBBackfillJob`
- `scripts/cdc_pipeline.sh` submits CDC via `MySQLToIoTDBCdcJob`

## Runtime requirements

### Flink runtime

- Use Flink **1.20.x** images for JobManager/TaskManager.
- Ensure the MySQL CDC connector jar is available to the cluster classpath (mounted into Flink libs).

### MySQL CDC prerequisites

- MySQL must have binlog enabled and the CDC user must be able to run `SHOW MASTER STATUS`.
- Quick path for PoC: run CDC using MySQL `root` credentials (privilege-heavy but unblocks testing).

## How to validate

1) Start CDC job and confirm it stays RUNNING in Flink UI.
2) Insert a new row into MySQL `record_ambience`.
3) Query IoTDB (e.g. `SELECT * FROM root.kanban.device_<deviceId>`) and confirm the new point exists.

