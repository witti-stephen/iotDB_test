# Design: 0001 Support Backfill and CDC

## Scope

Add support for two Flink operational modes while keeping the existing YAML mapping model:

- **Backfill (bounded)**: migrate historical MySQL data into IoTDB and terminate.
- **CDC (unbounded)**: continuously replicate MySQL inserts into IoTDB.

Assumptions (from PRP):

- Events are treated as **append-only**.
- `(deviceId, createdAt)` is assumed **unique/stable** and is used as the time-series key.
- CDC delivery guarantee target: **at-least-once** is acceptable.

## Current Implementation

The existing job (`com.example.MySQLToIoTDBJob`) is effectively a backfill job:

- Creates a Flink JDBC table per mapping using schema from `mysql.tables[].columns`.
- Queries with `SELECT deviceField AS deviceId, tsField AS ts, ...measurement columns...`.
- Converts to `DataStream<Row>` then to `Map<String,String>` for IoTDB.
- Sinks via `IoTDBSink` + `DefaultIoTSerializationSchema`.

## Proposed Code Structure

### Entry points

Create two explicit entrypoint classes (same Maven module, one built JAR):

- `com.example.MySQLToIoTDBBackfillJob`
- `com.example.MySQLToIoTDBCdcJob`

This keeps operational intent explicit (one finishes; one runs forever) while still allowing submission selection via:

`flink run -c <MainClass> /opt/flink/job/job.jar`

### Shared utilities

Refactor shared logic into small helper classes to avoid duplication:

- `ConfigLoader`
  - Load YAML from `/opt/flink/config/config.yaml` (or from arg0 if provided).
  - Return the config as typed objects or existing `Map` structures.
- `SchemaBuilder`
  - Convert `mysql.tables[].columns` to Flink SQL column DDL.
  - Own `mapToFlinkType`.
- `IoTDBSinkFactory`
  - Build `IoTDBSink` from `connections.iotdb`.
- `RowToIoTDBMapMapper`
  - Own the current `convertRowToMap(...)` logic.

## Backfill Mode

Implementation: largely the current logic.

1. For each mapping:
   - `CREATE TABLE <tableName> (...) WITH ('connector'='jdbc', ...)`
   - `Table t = tableEnv.sqlQuery(<select query>)`
   - `DataStream<Row> rows = tableEnv.toDataStream(t)`
   - `DataStream<Map<String,String>> out = rows.map(convertRowToMap)`
   - `out.addSink(iotdbSink)`
2. `env.execute(...)` terminates when bounded sources complete.

## CDC Mode

### Source connector

Replace the bounded JDBC source table with a CDC source table using Flink MySQL CDC connector.

Per mapping:

- `CREATE TABLE <tableName> (...) WITH ('connector'='mysql-cdc', ...)`

Notes:

- CDC sources are changelog sources.
- The CDC connector typically benefits from a declared primary key, e.g.
  `PRIMARY KEY(id) NOT ENFORCED`.
  Even though this subfeature assumes append-only, declaring the key helps the planner.

### Consuming events

Use changelog stream conversion:

- `DataStream<Row> changes = tableEnv.toChangelogStream(table)`

Then filter to append-only semantics:

- Keep only `RowKind.INSERT` (optional: also allow `RowKind.UPDATE_AFTER` as overwrite if desired later).

Finally:

- Map Row 6 IoTDB payload using the same row-to-map logic.
- Sink with the existing `IoTDBSink`.

### Checkpointing and restart

CDC job must enable checkpointing and restart strategy.

Minimum settings (exact values TBD):

- `env.enableCheckpointing(intervalMs)`
- checkpointing mode: at-least-once

Restart policy:

- For Flink 2.x, restart strategy should be configured via Flink `config.yaml` (cluster-level). The legacy `RestartStrategies` APIs are removed in 2.0.

## Configuration Changes

Keep the YAML structure as-is.

Optional additions:

- `mode: backfill|cdc` (for validation/logging)
- `mysql.cdc.*` (if connector requires extra settings like server-id range or startup options)

The existing `connections.mysql` and `connections.iotdb` are reused for both modes.

## Build / Packaging

- Add the Flink MySQL CDC connector dependency compatible with Flink 1.18 to `flink-migration/pom.xml`.
- Ensure the shaded jar contains both entrypoints.

## Scripts / Operations

- Keep current pipeline for backfill.
- Add a second run mode (or a second script) for CDC submission:
  - Backfill: `-c com.example.MySQLToIoTDBBackfillJob`
  - CDC: `-c com.example.MySQLToIoTDBCdcJob`

## Validation Plan

- Backfill:
  - Run the backfill job once; verify expected points exist in IoTDB.
- CDC:
  - Start CDC job; insert new rows into MySQL; verify new points appear in IoTDB.
