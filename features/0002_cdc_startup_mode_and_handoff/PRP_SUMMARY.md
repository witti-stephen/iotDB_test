# PRP Summary: CDC startup mode and handoff

## What shipped

- Config-driven CDC startup mode via YAML: `mysql.cdc.startup_mode`.
  - Verified modes: `latest`, `initial`.
  - Implemented but currently unverified: `specific` (binlog file/pos).
- CDC job applies the mode to the Flink MySQL CDC table options and validates config.
- Updated configs include the new `mysql.cdc` section.
- Scripts pass the config path into the CDC job for deterministic runs.

## How to use

- Start CDC (latest):
  - Set `mysql.cdc.startup_mode: latest`
  - Run `bash scripts/cdc_pipeline.sh --config <your-config>.yaml`

- Start CDC (initial snapshot + stream):
  - Set `mysql.cdc.startup_mode: initial`
  - Run `bash scripts/cdc_pipeline.sh --config <your-config>.yaml`

Runbook is in `features/0002_cdc_startup_mode_and_handoff/RUNBOOK.md`.

## Verification

- Verification configs are provided in `config/verify_002/`.
- `latest` test: only rows inserted after CDC start appear in IoTDB.
- `initial` test: existing MySQL rows appear (snapshot) and new inserts continue to appear.

