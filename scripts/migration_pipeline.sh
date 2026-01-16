#!/bin/bash

set -e

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

echo "Generating dummy data..."
uv run scripts/generate_dummy_data.py

echo "Submitting Flink job..."
docker exec iotdb_test-flink-jobmanager-1 flink run -c com.example.MySQLToIoTDBJob /opt/flink/job/job.jar

echo "Migration completed."