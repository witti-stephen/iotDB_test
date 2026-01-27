#!/bin/bash

set -e

# Default values
CONFIG_PATH="config/config.yaml"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --config)
      CONFIG_PATH="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "Starting containers..."
docker compose up -d

echo "Waiting for MySQL to be ready..."
until docker exec iotdb_test-mysql-1 mysql -u user -ppassword testdb -e "SELECT 1" >/dev/null 2>&1; do
    echo "MySQL not ready, waiting..."
    sleep 2
done

echo "Copying config to container (optional; config is volume-mounted)..."
docker cp "$CONFIG_PATH" iotdb_test-flink-jobmanager-1:/opt/flink/config/config.yaml || true

echo "Submitting Flink CDC job..."
docker exec iotdb_test-flink-jobmanager-1 flink run -c com.example.MySQLToIoTDBCdcJob /opt/flink/job/job.jar /opt/flink/config/config.yaml
