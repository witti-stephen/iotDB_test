package com.example;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.iotdb.flink.IoTDBSink;

import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class MySQLToIoTDBCdcJob {
    public static void main(String[] args) throws Exception {
        MigrationConfig config = ConfigLoader.load(args);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(Duration.ofSeconds(30).toMillis(), CheckpointingMode.AT_LEAST_ONCE);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Duration.ofSeconds(10)));

        EnvironmentSettings settings = EnvironmentSettings.newInstance().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, settings);

        IoTDBSink iotdbSink = IoTDBSinkFactory.create(config.iotdbConnection());

        for (Map<String, Object> mapping : config.mappings()) {
            processMapping(mapping, config, tableEnv, iotdbSink);
        }

        env.execute("MySQL to IoTDB CDC");
    }

    private static void processMapping(
            Map<String, Object> mapping,
            MigrationConfig config,
            StreamTableEnvironment tableEnv,
            IoTDBSink iotdbSink
    ) {
        String tableName = (String) mapping.get("table");
        String deviceField = (String) mapping.get("device_field");
        String timestampField = (String) mapping.get("timestamp_field");
        String deviceTemplate = (String) mapping.get("device_template");
        List<Map<String, Object>> measurements = (List<Map<String, Object>>) mapping.get("measurements");

        Map<String, Object> mysqlConn = config.mysqlConnection();
        String mysqlHost = (String) mysqlConn.get("host");
        String mysqlUser = (String) mysqlConn.get("user");
        String mysqlPassword = (String) mysqlConn.get("password");
        String mysqlDatabase = (String) mysqlConn.get("database");

        Map<String, Object> tableConfig = config.mysqlTables().stream()
                .filter(t -> tableName.equals(t.get("name")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> columns = (List<Map<String, Object>>) tableConfig.get("columns");

        String columnsDdl = SchemaBuilder.buildColumnsDdl(columns);
        String primaryKeyColumn = findPrimaryKeyColumn(columns);
        String pkClause = primaryKeyColumn == null
                ? ""
                : ", PRIMARY KEY(`" + primaryKeyColumn + "`) NOT ENFORCED";

        Map<String, String> connectorOptions = buildCdcConnectorOptions(config, mysqlHost, mysqlUser, mysqlPassword, mysqlDatabase, tableName);

        // NOTE: connector name and options must match the Flink MySQL CDC connector version.
        // This is the minimal set of options; advanced options can be added via config later.
        tableEnv.executeSql(
                "CREATE TABLE " + tableName + " (" +
                        columnsDdl +
                        pkClause +
                        ") WITH (" +
                        renderWithOptions(connectorOptions) +
                        ")"
        );

        String query = QueryBuilder.buildSelect(tableName, deviceField, timestampField, measurements);
        Table table = tableEnv.sqlQuery(query);

        DataStream<Row> changelogStream = tableEnv.toChangelogStream(table);
        DataStream<Row> insertsOnly = changelogStream.filter(row -> row.getKind() == RowKind.INSERT);

        DataStream<Map<String, String>> mapStream = insertsOnly
                .map(row -> RowToIoTDBMapMapper.convertRowToMap(row, deviceTemplate, deviceField, measurements))
                .returns(new TypeHint<Map<String, String>>() {})
                .filter(Objects::nonNull);

        mapStream.addSink(iotdbSink);
    }

    private static String findPrimaryKeyColumn(List<Map<String, Object>> columns) {
        for (Map<String, Object> col : columns) {
            Object primary = col.get("primary");
            if (primary instanceof Boolean && (Boolean) primary) {
                return (String) col.get("name");
            }
        }
        return null;
    }

    private static Map<String, String> buildCdcConnectorOptions(
            MigrationConfig config,
            String mysqlHost,
            String mysqlUser,
            String mysqlPassword,
            String mysqlDatabase,
            String tableName
    ) {
        String startupMode = config.mysqlCdcStartupMode();
        validateStartupModeConfig(config, startupMode);

        Map<String, String> options = new LinkedHashMap<>();
        options.put("connector", "mysql-cdc");
        options.put("hostname", mysqlHost);
        options.put("port", "3306");
        options.put("username", mysqlUser);
        options.put("password", mysqlPassword);
        options.put("database-name", mysqlDatabase);
        options.put("table-name", tableName);

        // Flink MySQL CDC connector (3.x) startup options.
        options.put("scan.startup.mode", toConnectorStartupMode(startupMode));
        if ("specific".equals(startupMode)) {
            Map<String, Object> specific = config.mysqlCdcSpecificConfig();
            options.put("scan.startup.specific-offset.file", specific.get("file").toString());
            options.put("scan.startup.specific-offset.pos", String.valueOf(parsePositiveLong(specific.get("pos"), "mysql.cdc.specific.pos")));
        }

        return options;
    }

    private static void validateStartupModeConfig(MigrationConfig config, String startupMode) {
        if (!"latest".equals(startupMode) && !"initial".equals(startupMode) && !"specific".equals(startupMode)) {
            throw new IllegalArgumentException(
                    "Invalid mysql.cdc.startup_mode='" + startupMode + "'. Expected one of: latest|initial|specific"
            );
        }

        if ("specific".equals(startupMode)) {
            Map<String, Object> specific = config.mysqlCdcSpecificConfig();
            Object file = specific.get("file");
            if (file == null || file.toString().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "mysql.cdc.startup_mode=specific requires mysql.cdc.specific.file"
                );
            }
            parsePositiveLong(specific.get("pos"), "mysql.cdc.specific.pos");
        }
    }

    private static long parsePositiveLong(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required and must be > 0");
        }
        try {
            long parsed;
            if (value instanceof Number) {
                parsed = ((Number) value).longValue();
            } else {
                parsed = Long.parseLong(value.toString().trim());
            }
            if (parsed <= 0) {
                throw new IllegalArgumentException(fieldName + " must be > 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a number", e);
        }
    }

    private static String toConnectorStartupMode(String startupMode) {
        return switch (startupMode) {
            case "latest" -> "latest-offset";
            case "initial" -> "initial";
            case "specific" -> "specific-offset";
            default -> throw new IllegalArgumentException("Unsupported startup mode: " + startupMode);
        };
    }

    private static String renderWithOptions(Map<String, String> options) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append("'").append(escapeSql(entry.getKey())).append("' = '").append(escapeSql(entry.getValue())).append("'");
        }
        return builder.toString();
    }

    private static String escapeSql(String value) {
        return value.replace("'", "''");
    }
}
