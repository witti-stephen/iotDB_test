# Objective: Migration Test with Real Schema

## Tasks
1. Create test config based on config-real.yaml with test MySQL connections. ✅
2. Generate dummy data using real schema into test MySQL. ✅
3. Run the migration pipeline. ✅
4. Verify IoTDB results. ✅
5. Update lessons learnt with new insights.

## Progress
- Started: Mon Jan 19 2026
- Completed: Mon Jan 19 2026

## Insights
- Migration pipeline works with real schema.
- Dynamic data generation handles various MySQL types.
- IoTDB receives data with multiple measurements per device per timestamp.
- Note: Storage group in config (root.kanban) differs from actual (root.devices); investigate job code.