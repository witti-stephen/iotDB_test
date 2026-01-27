# Repository Guidelines

## Overview
This repo contains a small MySQL → IoTDB migration demo using Apache Flink. It includes a Java Flink job (built with Maven), a Python helper for generating sample data, and a `docker-compose.yml` for running the stack locally.

## Project Structure
- `flink-migration/`: Maven project containing Flink jobs (`src/main/java/...`).
- `flink-job/`: Built job artifact(s) used by Docker (`job.jar`).
- `flink-lib/`: Connector JARs mounted into the Flink container.
- `config/`: Runtime YAML configs (e.g. `config/config.yaml`).
- `scripts/`: Local helper scripts (pipelines + dummy data generator).
- `features/`: Design notes / PRPs.

## Build, Test, and Development Commands
- `docker compose up -d`: Starts MySQL/Flink/IoTDB services (see `docker-compose.yml`).
- `bash scripts/migration_pipeline.sh`: Runs a backfill migration pipeline end-to-end.
- `bash scripts/cdc_pipeline.sh`: Submits the Flink CDC job.
- `uv run scripts/generate_dummy_data.py --config config/config.yaml --table <name> --num-rows 100`: Generates MySQL dummy rows.
- `cd flink-migration && ./mvnw -DskipTests package`: Builds the Flink job JAR.

Environment note: this workspace expects `JAVA_HOME=/home/witti/.jdks/openjdk-23.0.2`.

## Coding Style & Naming Conventions
- **Java**: follow existing package layout `com.example.*`; prefer `UpperCamelCase` classes and `lowerCamelCase` methods/fields.
- **Python**: target Python 3.12; use `snake_case` for functions/files.
- **Shell**: keep scripts POSIX-ish and executable via `bash scripts/<name>.sh`.

## Testing Guidelines
This repo currently has no dedicated test suite configured (no `src/test/*` or pytest setup). When adding tests:
- Java: add JUnit tests under `flink-migration/src/test/java` and wire Maven Surefire.
- Python: add pytest tests under `scripts/tests/` and document `uv run pytest`.

## Commit & Pull Request Guidelines
- **Commits**: use short, imperative subjects similar to history (e.g. `rename`, `update config to cover ...`).
- **PRs**: include a brief description of the migration scenario, relevant config changes (files under `config/`), and how to run/verify (commands + expected outcome).

## Configuration & Security
- Do not commit real credentials. Prefer a checked-in example (like `config/config-example-*.yaml`) and keep real secrets in local-only config.


# Execution info about the environment
remember JAVA_HOME=/home/witti/.jdks/openjdk-23.0.2