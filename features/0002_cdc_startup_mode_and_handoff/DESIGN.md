# DESIGN: CDC startup mode and backfill→CDC handoff

## Scope

Implement `mysql.cdc.startup_mode` as a config-driven switch that controls how the Flink MySQL CDC source starts consuming data, and provide a repeatable operational handoff from bounded backfill to unbounded CDC.

This design covers the minimal PoC behavior needed for deterministic runs in Docker:

- Read startup mode from YAML.
- Translate it into Flink MySQL CDC connector table options.
- Validate config and fail fast with actionable errors.
- Provide a recommended runbook + scriptable sequence for backfill→CDC.

Non-goals (for this PRP):

- Exactly-once end-to-end guarantees.
- Delete propagation or update semantics beyond current insert-only filtering.
- Production-grade offset capture.
- GTID-based offsets.

## Current State (as-is)

- Entry points:
  - Backfill: `com.example.MySQLToIoTDBBackfillJob`
  - CDC: `com.example.MySQLToIoTDBCdcJob`
- `MySQLToIoTDBCdcJob` creates a Flink SQL table with connector `'mysql-cdc'` and minimal connection options.
- The CDC stream is converted to changelog and filtered to `RowKind.INSERT`.
- YAML is loaded via SnakeYAML into `MigrationConfig` (a thin wrapper around `Map<String,Object>`).
- Scripts submit jobs via Docker exec:
  - `scripts/migration_pipeline.sh`
  - `scripts/cdc_pipeline.sh`

## Requirements Mapping

### YAML schema

Add a new optional section under `mysql`:

```yaml
mysql:
  cdc:
    startup_mode: latest   # latest|initial|specific
    # required only when startup_mode=specific
    specific:
      file: mysql-bin.000003
      pos: 123456
```

Defaults:

- If `mysql.cdc.startup_mode` is omitted, default to `latest` (safest for “don’t backfill twice”).

Status note:

- `latest` and `initial` are the supported/verified modes for this PoC.
- `specific` is implemented but considered **unverified** in the current workflow.

### Behavior

`startup_mode` controls the Debezium startup behavior via Flink MySQL CDC connector options:

- `latest`:
  - Start streaming new changes from “now”, without snapshot.
- `initial`:
  - Do an initial snapshot of existing tables, then continue streaming.
- `specific`:
  - Start from a known binlog file/pos.

Note: the Flink MySQL CDC connector options are version-dependent; this repo uses `flink-sql-connector-mysql-cdc-3.5.0.jar`.
The design below assumes the connector supports `scan.startup.mode` and (for `specific`) `scan.startup.specific-offset.file`/`scan.startup.specific-offset.pos`.
If the connector uses different option names, we should adjust after checking the runtime error message / docs.

## Implementation Design

### 1) Config accessors

Extend `MigrationConfig` with safe accessors:

- `mysqlCdcConfig(): Map<String,Object>` (may be empty)
- `mysqlCdcStartupMode(): String` (normalized to lowercase, default `latest`)
- `mysqlCdcSpecificOffset(): record(file, pos)` or `Map` accessor

Rationale:

- Keep config parsing centralized and avoid scattered `(Map<String,Object>)` casting.
- Make it easy to validate and to unit test in isolation later.

### 2) Startup mode parsing + validation

In CDC job startup (before table DDL), validate:

- `startup_mode` must be one of `latest|initial|specific`.
- If `specific`:
  - `mysql.cdc.specific.file` must be a non-empty string.
  - `mysql.cdc.specific.pos` must be a positive integer (accept YAML int/long as well as string).

Failure mode:

- Throw `IllegalArgumentException` with an error message that includes the invalid value and the expected schema.

### 3) Table DDL option mapping

Update `MySQLToIoTDBCdcJob.processMapping(...)` to include connector options for startup mode.

Proposed option mapping:

- Always include:
  - `'scan.startup.mode' = '<mode>'`
- If mode = `specific`, also include:
  - `'scan.startup.specific-offset.file' = '<file>'`
  - `'scan.startup.specific-offset.pos' = '<pos>'`

Where to apply:

- In the `CREATE TABLE ... WITH (...)` DDL built in `MySQLToIoTDBCdcJob`.

Design note:

- Build the `WITH (...)` options as a `Map<String,String>` and render it to SQL.
  This keeps quoting consistent and makes it easier to add future options.

### 4) Scripts and runbook

Add a short runbook (in this feature folder) and implement the minimal script support.

Recommended PoC handoff pattern: **Pattern A (single-job)** by default.

- For simplest deterministic behavior in a demo environment, run only CDC with `startup_mode=initial`.
- The “two-job handoff” remains documented as an option, but is only best-effort unless we implement `specific` offsets or enforce idempotence.

Because `specific` is currently unverified, the recommended two-job handoff remains **best-effort** using `latest` plus (optionally) a short write pause.

Scripts:

- Keep `scripts/migration_pipeline.sh` (backfill) and `scripts/cdc_pipeline.sh` (cdc).
- Add a new `scripts/handoff_backfill_to_cdc.sh` (optional but recommended) which:
  1. Runs backfill.
  2. Starts CDC in a chosen mode.
  3. Prints operator guidance: “write pause / wait for lag / cutover”.

If we keep PR scope minimal, we can skip the new script and instead:

- Extend `scripts/cdc_pipeline.sh` to accept a config file that includes the new section.
- Add a documented sequence in the runbook.

## Operational Runbooks

### Runbook A: Single-job (recommended for PoC)

Goal: simplest operator experience, no separate backfill.

1. Ensure MySQL binlog prerequisites are enabled.
2. Set `mysql.cdc.startup_mode: initial`.
3. Run `bash scripts/cdc_pipeline.sh --config config/config.yaml`.
4. Validate:
   - historical rows appear in IoTDB (snapshot)
   - new inserts continue to replicate

### Runbook B: Two-job handoff (best-effort until `specific` is used)

Goal: backfill quickly, then tail changes.

1. Run backfill job.
2. Start CDC with `startup_mode: latest`.
3. Expectation:
   - Potential duplicates for the overlapping window.
   - Potential gap if writes occur between “backfill finished” and “CDC started”.
4. Mitigation options:
   - Pause application writes during the handoff window.
   - (Future) Use `startup_mode: specific` with a known binlog position captured at backfill boundary.

## Validation Plan

- `latest`:
  - Start CDC.
  - Insert a new row into MySQL.
  - Verify it arrives in IoTDB.
  - Verify historical rows are not replayed.

- `initial`:
  - Start CDC.
  - Verify it snapshots existing rows then continues streaming.

- `specific`:
  - Implemented but **not required for this PoC**; treat as unverified unless you explicitly test it.

## Future Extensions

- GTID startup offsets.
- Configurable handling of `UPDATE`/`DELETE` events.
- Idempotence strategy (dedupe key / overwrite semantics) for two-job handoff.
