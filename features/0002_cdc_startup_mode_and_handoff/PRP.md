# PRP: 0003 CDC startup mode and backfill→CDC handoff

## Objective

Make the transition from historical migration (backfill) to continuous replication (CDC) deterministic and repeatable by:

- Adding an explicit CDC startup mode (e.g. start from latest vs start with initial snapshot).
- Documenting and scripting the recommended backfill→CDC handoff procedure to avoid gaps and minimize duplicates.

## Background

This repo implements:

- Backfill entrypoint: `com.example.MySQLToIoTDBBackfillJob`
- CDC entrypoint: `com.example.MySQLToIoTDBCdcJob`

CDC is powered by the MySQL CDC connector (Debezium-based) and (currently) filters to insert-only semantics. The sink is IoTDB Flink sink with dynamic measurement mapping.

## Problem Statement

Without an explicit CDC startup configuration and a clear handoff procedure, it is easy to:

- Start CDC “too early” (duplicate historical writes).
- Start CDC “too late” (lose changes between the end of backfill and the start of CDC).
- Have different operators run different sequences, leading to inconsistent results.

We need a config-driven, documented procedure that can be executed via scripts.

## Proposed Decisions

### D1: Make CDC startup mode explicit in YAML

Add a `mysql.cdc` section:

```yaml
mysql:
  cdc:
    startup_mode: latest   # latest|initial|specific
    # optional when startup_mode=specific
    # specific:
    #   file: mysql-bin.000003
    #   pos: 123456
```

Notes:

- `latest` means: start streaming new changes from “now”, without snapshot.
- `initial` means: do an initial snapshot then continue streaming.
- `specific` means: start from a known binlog position (or GTID if we add that later).

### D2: Standardize on one recommended handoff

For this PoC, prefer one of these operational patterns (to be selected):

- **Pattern A (single-job)**: use CDC `startup_mode=initial` and do not run separate backfill.
- **Pattern B (two-job handoff)**: run backfill, then start CDC with `startup_mode=latest` and accept potential overlap (requires idempotence on `(deviceId, createdAt)`), or enhance later to start from a specific offset.

## Requirements

### Functional

- CDC job reads `mysql.cdc.startup_mode` from YAML and applies it to the CDC table options.
- Provide validation and error handling for invalid startup modes.

### Operational

- Provide a documented runbook for backfill→CDC handoff.
- Provide scripts that make the handoff repeatable.

### Compatibility

- Keep the current backfill behavior unchanged.
- Do not require changes to the IoTDB mapping model.

## Deliverables

- Update `MySQLToIoTDBCdcJob` to accept/startup mode from YAML.
- Update config example(s) to include the new `mysql.cdc` section.
- Add/extend scripts:
  - `scripts/migration_pipeline.sh` (backfill)
  - `scripts/cdc_pipeline.sh` (cdc)
  - optional: `scripts/handoff_backfill_to_cdc.sh` to automate the sequence.
- Add a short “handoff runbook” doc under `features/0003_cdc_startup_mode_and_handoff/`.

## Validation

- `latest`:
  - Start CDC; insert new row in MySQL; verify it arrives in IoTDB.
  - Verify CDC does not backfill historical rows.
- `initial`:
  - Start CDC; verify it snapshots existing rows then continues.
- Handoff procedure:
  - Run backfill; start CDC in the chosen mode; verify new rows inserted after backfill are replicated.

## Open Questions

- For the two-job handoff, do we require a strict “no duplicates/no gaps” guarantee, or is “best-effort with idempotent sink” acceptable?
- Should we support GTID-based offsets (recommended for production MySQL) or keep binlog file/pos only for PoC?

