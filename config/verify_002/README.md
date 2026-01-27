# Verify 0002: CDC startup mode and handoff

This folder contains ready-to-run configs for verifying PRP 0002 behavior.

## How to run

Start the stack:

```bash
docker compose up -d
```

Run CDC using one of the configs below:

```bash
bash scripts/cdc_pipeline.sh --config config/verify_002/config-cdc-<mode>.yaml
```

Where `<mode>` is one of `latest`, `initial`, `specific`.

## What to expect

### `latest` (stream only)

- Correct:
  - After the job starts, only rows inserted *after* startup show up in IoTDB.
  - No historical rows are replayed.
- Wrong:
  - A large burst of historical data appears immediately after starting CDC (you probably started with `initial` semantics).
  - New inserts do not appear (CDC not running, binlog config issue, or mapping issue).

### `initial` (snapshot then stream)

- Correct:
  - Shortly after startup, IoTDB receives existing historical rows (snapshot).
  - After snapshot completes, new inserts continue to appear.
- Wrong:
  - No historical rows appear (likely not snapshotting).
  - Snapshot happens repeatedly on restarts (misconfigured state/checkpointing or job restarts without state).

### `specific` (start from binlog file/pos)

- Correct:
  - CDC starts at/after the specified position.
  - Inserts before the offset do not show up; inserts after do.
- Wrong:
  - Job fails at startup with unsupported option or invalid offset.
  - CDC starts from “now” or from the beginning (offset not applied).

