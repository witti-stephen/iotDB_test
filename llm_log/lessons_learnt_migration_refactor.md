# Lessons Learnt: IoTDB Migration Refactor

## Project Context
This project involves migrating MySQL data to IoTDB using Flink, with dynamic config loading. The refactor focused on flexible field mappings for multiple readings per IoTDB record.

## Key Lessons and Detailed Procedures

### 1. Config Structure Updates
**Lesson**: Always add optional fields to configs for flexibility, remove unused ones to avoid confusion. Use 'field' keys for source mappings to decouple names.

**Steps for Future Config Updates**:
- Review existing mappings for unused fields (e.g., normalized, type_field, value_field).
- Add 'field' key to measurements: `field: source_column` (defaults to measurement 'name' if missing).
- Ensure backward compatibility by making new keys optional.
- Update both real and dummy configs consistently.
- Test YAML parsing after changes.

### 2. Java Code Refactoring
**Lesson**: Use Maven wrapper with explicit JAVA_HOME export. Remove unused code early. Modify queries dynamically for flexibility.

**Steps for Java Refactoring**:
- Export JAVA_HOME: `export JAVA_HOME=/home/witti/.jdks/openjdk-23.0.2`
- Compile with Maven: `./mvnw compile` (check for warnings).
- Remove unused variables from processMapping method.
- Update query building: Use `meas.getOrDefault("field", meas.get("name"))` for source columns.
- Ensure convertRowToMap handles dynamic measurements without hardcoded indices.
- Build shaded JAR: `./mvnw package -DskipTests`
- Copy JAR to flink-job/job.jar for Docker deployment.

### 3. Python Script Dynamic Generation
**Lesson**: Hardcoded data generation limits testing; make it schema-driven. Handle types generically.

**Steps for Dynamic Data Generation**:
- Add import re for type parsing.
- Create generate_value function: Map MySQL types (BIGINT, VARCHAR, TIMESTAMP, etc.) to random generators.
- Modify generate_data: Iterate over non-auto_increment columns, generate values per type.
- Remove hardcoded device/time ranges; rely on config schema.
- Test syntax: `python3 -m py_compile script.py`
- Run with uv if needed: `uv run python script.py --args`

### 4. Docker and Pipeline Testing
**Lesson**: Docker Compose handles volumes; wait for services. Pipeline script automates setup.

**Steps for Testing**:
- Build JAR and copy to flink-job/.
- Run `docker compose up -d` to start MySQL, IoTDB, Flink.
- Execute pipeline: `bash scripts/migration_pipeline.sh --config config/file.yaml --env test`
- Monitor logs: Job submission, runtime, success.
- Verify IoTDB data via Docker exec if needed.
- Clean up: Script handles down and volume removal.

### 5. General Best Practices
- Log progress in llm_log for resumability.
- Use subagents for complex tasks (e.g., explore configs, refactor code).
- Maintain backward compatibility in configs.
- Test incrementally: Compile, syntax check, then full pipeline.
- Document changes in session logs for future reference.

## 6. Verification Steps
**Lesson**: Verify migration by running pipeline, inspecting series, and confirming data structure.
- Run pipeline with dummy config: `bash scripts/migration_pipeline.sh --config config/config-example-dummy-data.yaml --env test`
- Inspect IoTDB timeseries: Use CLI `show timeseries;` to confirm devices with multiple measurements (e.g., reading, type).
- Query sample data: `select * from root.devices.{device_id} limit 5;` to verify multiple measurements per timestamp.
- Confirm multiple readings per record: Each MySQL row maps to multiple IoTDB measurements, supporting flexible field mappings.

## 7. Migration Test Insights (Jan 19 2026)
**Lesson**: Full pipeline testing with real schema confirms end-to-end functionality. Dynamic data generation scales to 20k rows efficiently. Fixes for device_template parsing and type consistency resolved issues.

**Key Findings**:
- Pipeline successfully migrates MySQL record_ambience table to IoTDB with multiple measurements (reading, type, lineId, etc.) per timestamp.
- Docker setup with volumes allows repeatable testing without data persistence issues.
- Storage group mismatch: Fixed by parsing device_template in Java code to set IoTDBSinkOptions storage group.
- Incomplete measurements: All 5 measurements (reading, type, lineId, source, tenId) inserted correctly.
- Type mismatch: Changed reading from FLOAT to DOUBLE in config for consistency.
- Device naming: Uses device_{deviceId} format as specified.
- Generate script handles DECIMAL, DATETIME, VARCHAR types correctly.

**Recommendations**:
- Ensure YAML indentation is consistent to avoid parsing errors.
- Parse device_template to extract storage group dynamically in job code.
- Verify all measurements are included in config mappings.
- Use DOUBLE for floating point readings in IoTDB.

## Reusability
This can be referenced for similar refactors: config updates, code changes, dynamic generation, Docker testing. For fresh sessions, read this to skip context building.