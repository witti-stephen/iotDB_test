#!/bin/bash

set -e

CONFIG_PATH="config/config.yaml"
CDC_MODE="latest"

while [[ $# -gt 0 ]]; do
  case $1 in
    --config)
      CONFIG_PATH="$2"
      shift 2
      ;;
    --cdc-mode)
      CDC_MODE="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "Handoff starting (config=$CONFIG_PATH, cdc-mode=$CDC_MODE)"
echo "Recommended: pause application writes during handoff."

echo "Running backfill..."
bash scripts/migration_pipeline.sh --config "$CONFIG_PATH"

echo "Backfill finished."
echo "Next: set mysql.cdc.startup_mode to '$CDC_MODE' in $CONFIG_PATH"
echo "Then start CDC with: bash scripts/cdc_pipeline.sh --config $CONFIG_PATH"

