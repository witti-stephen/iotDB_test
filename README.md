# MySQL to IoTDB Migration with Apache Flink

A demonstration project for migrating data from MySQL to Apache IoTDB using Apache Flink. This project supports both **backfill** (historical data migration) and **CDC** (Change Data Capture for continuous replication).

## Table of Contents

- [Project Overview](#project-overview)
- [Directory Structure](#directory-structure)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Local Development with Docker Compose](#local-development-with-docker-compose)
- [Local Development with k3d](#local-development-with-k3d)
- [Production Deployment](#production-deployment)
- [Key Scripts](#key-scripts)
- [Development Guide](#development-guide)
- [Design Documentation](#design-documentation)

## Project Overview

This project implements a data migration pipeline that:

1. **Reads data from MySQL** using JDBC connections
2. **Transforms rows into IoTDB format** (device, timestamp, measurements)
3. **Writes to Apache IoTDB** time-series database
4. **Supports both bounded (backfill) and unbounded (CDC) pipelines**

### Key Features

- **Backfill Mode**: Migrate historical data from MySQL to IoTDB in a single batch job
- **CDC Mode**: Continuously replicate changes from MySQL binlog to IoTDB
- **Config-Driven**: All mappings and transformations defined in YAML configuration
- **Kubernetes-Native**: Deployable to K8s with proper resource management
- **Dual Entrypoints**: Single JAR with separate main classes for backfill and CDC

## Directory Structure

```
iotDB_test/
├── config/                          # Runtime YAML configurations
│   ├── config.yaml                  # Main config (test environment)
│   ├── config-local.yaml            # Local k3d environment config
│   ├── config-dev.yaml              # Dev environment config
│   ├── config-real.yaml             # Production config template
│   ├── config-test-real-schema.yaml # Schema verification config
│   ├── config-example-dummy-data.yaml
│   └── verify_002/                  # Verification test configs
├── scripts/                         # Helper scripts
│   ├── generate_dummy_data.py       # Python data generator
│   ├── migration_pipeline.sh        # Docker backfill pipeline
│   ├── cdc_pipeline.sh              # Docker CDC pipeline
│   ├── handoff_backfill_to_cdc.sh   # Handoff script
│   └── k8s/
│       └── run_migration_job.sh     # K8s job submission
├── flink-migration/                 # Maven Java project
│   ├── pom.xml
│   └── src/main/java/com/example/
│       ├── MySQLToIoTDBBackfillJob.java
│       ├── MySQLToIoTDBCdcJob.java
│       ├── ConfigLoader.java
│       ├── MigrationConfig.java
│       ├── IoTDBSinkFactory.java
│       ├── RowToIoTDBMapMapper.java
│       ├── SchemaBuilder.java
│       └── QueryBuilder.java
├── flink-job/                       # Built artifacts
│   └── job.jar
├── flink-lib/                       # Connector JARs (Docker Compose mounts)
│   ├── flink-sql-connector-mysql-cdc-3.5.0.jar
│   └── mysql-connector-j-8.0.33.jar
├── k8s/                             # Kubernetes manifests
│   ├── base/
│   │   ├── kustomization.yaml
│   │   ├── flink.yaml
│   │   └── iotdb.yaml
│   └── overlays/
│       ├── local/                   # Local k3d overlay
│       │   ├── kustomization.yaml
│       │   ├── mysql.yaml
│       │   ├── dummy-data-job.yaml
│       │   ├── ingressroute-tcp.yaml
│       │   └── traefik-helmchartconfig.yaml
│       ├── dev/                     # Dev overlay
│       ├── staging/                 # Staging overlay
│       └── prod/                    # Production overlay
├── features/                        # Design documentation
├── llm_log/                         # Notes from LLM-assisted sessions
├── docker-compose.yml
├── pyproject.toml
└── README.md
```

## Architecture

```
                    +----------------+
                    |    MySQL       |
                    | (Source DB)    |
                    +----------------+
                           |
                           | Binlog / JDBC
                           v
                    +----------------+
                    | Apache Flink   |
                    | - JobManager   |
                    | - TaskManager  |
                    +----------------+
                           |
                    JDBC / IoTDB Protocol
                           v
                    +----------------+
                    |  Apache IoTDB  |
                    +----------------+
```

## Prerequisites

- Docker 20.10+ (for Docker Compose)
- k3d 5.0+, kubectl, Helm 3.0+ (for K8s)
- Java 17+, Python 3.12+
- uv (Python package manager)
- Maven 3.8+

```bash
export JAVA_HOME=/home/witti/.jdks/openjdk-23.0.2
uv sync
```

## Configuration

```yaml
env: verify_002
connections:
  mysql:
    host: mysql
    user: root
    password: rootpassword
    database: testdb
  iotdb:
    host: iotdb
    port: 6667
    user: root
    password: root

mysql:
  cdc:
    startup_mode: initial
  partitioning:
    column: id
    lower_bound: 1
    upper_bound: 20000
    num: 100
  tables:
    - name: record_ambience
      columns:
        - name: id
          type: BIGINT
          primary: true
          auto_increment: true
        - name: createdAt
          type: DATETIME(3)
        - name: lineId
          type: INT
        - name: deviceId
          type: INT
        - name: type
          type: INT
        - name: reading
          type: FLOAT
        - name: source
          type: VARCHAR(60)
        - name: tenId
          type: INT
        - name: flowRate
          type: DECIMAL(10,2)

iotdb:
  storage_groups:
    - root.kanban
  mappings:
    - table: record_ambience
      device_field: deviceId
      timestamp_field: createdAt
      device_template: root.kanban.device_{deviceId}
      measurements:
        - name: reading
          type: DOUBLE
          field: reading
        - name: type
          type: INT32
          field: type
        - name: lineId
          type: INT32
          field: lineId
        - name: source
          type: TEXT
          field: source
        - name: tenId
          type: INT32
          field: tenId
        - name: flowRate
          type: DOUBLE
          field: flowRate
```

## Local Development with Docker Compose

```bash
# Start stack
docker compose up -d

# NOTE: docker-compose.yml expects an external Docker network named `iotdb`.
# Create it once with:
docker network create iotdb

# Run backfill pipeline
bash scripts/migration_pipeline.sh

# Run CDC pipeline
bash scripts/cdc_pipeline.sh

# Generate dummy data
uv run scripts/generate_dummy_data.py --config config/config.yaml --table record_ambience --num-rows 100
```

## Local Development with k3d

### Setup
```bash
bash scripts/setup_k3d_local.sh
```

This creates k3d cluster `iotdb-dev` with:
- `8081:8081` (Flink UI)
- `3306:3306` (MySQL)
- `6667:6667` (IoTDB)

### Access Services
```bash
# MySQL
mysql -h 127.0.0.1 -P 3306 -u root -prootpassword testdb

# IoTDB
localhost:6667

# Flink Web UI
http://localhost:8081

# Check pods
kubectl get pods -n iotdb-dev
```

### Run Migration Jobs
```bash
# Backfill
bash scripts/k8s/run_migration_job.sh --job backfill

# CDC
bash scripts/k8s/run_migration_job.sh --job cdc
```

### Connector JARs in Kubernetes

In Docker Compose, Flink connector JARs are mounted from `flink-lib/`.

In Kubernetes, connectors are downloaded by an `initContainer` (see `k8s/base/flink.yaml`) into an `emptyDir` volume, then mounted into `/opt/flink/lib`.

## Production Deployment

```bash
# Apply production overlay
kubectl apply -k k8s/overlays/prod

# Create secrets
kubectl create secret generic mysql-credentials \
  --from-literal=root-password=${MYSQL_ROOT_PASSWORD} \
  --from-literal=user-password=${MYSQL_PASSWORD} \
  -n iotdb-prod
```

## Key Scripts

| Script | Purpose |
|--------|---------|
| `scripts/setup_k3d_local.sh` | Create k3d cluster with all services |
| `scripts/migration_pipeline.sh` | Docker Compose backfill pipeline |
| `scripts/cdc_pipeline.sh` | Docker Compose CDC pipeline |
| `scripts/k8s/run_migration_job.sh` | K8s job submission |
| `scripts/generate_dummy_data.py` | Generate test data |

## Development

### Add a New Table

1. Update `config/config.yaml` with table schema and IoTDB mapping
2. Generate test data: `uv run scripts/generate_dummy_data.py --table new_table --num-rows 1000`
3. Build: `cd flink-migration && ./mvnw -DskipTests package`

### Modify Flink Job

Java sources in `flink-migration/src/main/java/com/example/`:
- `MySQLToIoTDBBackfillJob.java` - Bounded batch migration
- `MySQLToIoTDBCdcJob.java` - Unbounded CDC stream
- `ConfigLoader.java` - YAML config parser
- `IoTDBSinkFactory.java` - IoTDB sink creation
- `RowToIoTDBMapMapper.java` - Row transformation

## Design Documentation

See `features/` directory for:
- `MASTER_PRP.md` - Core requirements
- `0001_support_backfill_and_cdc/` - Backfill and CDC implementation
- `0002_cdc_startup_mode_and_handoff/` - CDC modes and handoff

### CDC Startup Modes
| Mode | Behavior |
|------|----------|
| `initial` | Snapshot all data, then stream |
| `latest` | Only stream new changes |
| `specific` | Start from specific binlog position |

## Troubleshooting

```bash
# MySQL issues
docker logs iotdb_test-mysql-1

# Flink issues
docker logs iotdb_test-flink-jobmanager-1

# k3d reset
k3d cluster delete iotdb-dev
bash scripts/setup_k3d_local.sh
```
