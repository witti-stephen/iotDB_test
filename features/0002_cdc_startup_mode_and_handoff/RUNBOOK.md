# RUNBOOK: Backfill → CDC handoff

This runbook standardizes how to run bounded backfill and then start unbounded CDC with minimal operator error.

## Prerequisites

- Docker running.
- MySQL binlog is enabled and row-based.
- Config includes `mysql.cdc.startup_mode`.

## Pattern A (Recommended PoC): CDC-only

Use this when you want the simplest repeatable workflow.

1. Set in YAML:

```yaml
mysql:
  cdc:
    startup_mode: initial
```

2. Run:

```bash
bash scripts/cdc_pipeline.sh --config config/config.yaml
```

Expected:

- Existing rows are snapshotted into IoTDB.
- New MySQL inserts continue to replicate.

## Pattern B: Two-job handoff (Backfill then CDC)

Use this when you want to backfill quickly and then tail changes.

### Option B1 (Best-effort): backfill → `latest`

1. Ensure application writes are paused during the handoff window (recommended).
2. Run backfill:

```bash
bash scripts/migration_pipeline.sh --config config/config.yaml
```

3. Set in YAML:

```yaml
mysql:
  cdc:
    startup_mode: latest
```

4. Start CDC:

```bash
bash scripts/cdc_pipeline.sh --config config/config.yaml
```

Notes:

- If writes are not paused, there is a risk of gaps (writes that happen after backfill finishes but before CDC begins).
- If you do not have idempotence in the sink, you may see duplicates.

### Option B2 (More deterministic): backfill → `specific`

This is intended for when you can capture a binlog boundary.

Status note: `specific` is implemented in code but is currently **unverified** in this repo's day-to-day workflow. Prefer Option B1 (`latest`) for now.

1. Determine the binlog file/position you want CDC to start from.
2. Set in YAML:

```yaml
mysql:
  cdc:
    startup_mode: specific
    specific:
      file: mysql-bin.000003
      pos: 123456
```

3. Start CDC:

```bash
bash scripts/cdc_pipeline.sh --config config/config.yaml
```

## Automated handoff

For a scripted sequence (still requiring optional write-pause discipline), use:

```bash
bash scripts/handoff_backfill_to_cdc.sh --config config/config.yaml --cdc-mode latest
```
