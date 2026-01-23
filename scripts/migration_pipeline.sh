#!/bin/bash

set -e

# Default values
CONFIG_PATH="config/config.yaml"
ENV="test"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --config)
      CONFIG_PATH="$2"
      shift 2
      ;;
    --env)
      ENV="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "Taking down containers..."
docker compose down

echo "Removing volumes..."
docker volume rm iotdb_test_mysql_data iotdb_test_iotdb_data

echo "Starting containers..."
docker compose up -d

echo "Waiting for MySQL to be ready..."
until docker exec iotdb_test-mysql-1 mysql -u user -ppassword testdb -e "SELECT 1" >/dev/null 2>&1; do
    echo "MySQL not ready, waiting..."
    sleep 2
done

echo "MySQL is ready."

if [ "$ENV" = "test" ]; then
  echo "Generating dummy data..."
  uv run scripts/generate_dummy_data.py --config "$CONFIG_PATH" --table record_ambience --num-rows 100
fi

echo "Copying config to container..."
docker cp "$CONFIG_PATH" iotdb_test-flink-jobmanager-1:/opt/flink/config/config.yaml

echo "Submitting Flink job..."
docker exec iotdb_test-flink-jobmanager-1 flink run -c com.example.MySQLToIoTDBBackfillJob /opt/flink/job/job.jar

echo "Migration completed."
