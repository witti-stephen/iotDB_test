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

        // NOTE: connector name and options must match the Flink MySQL CDC connector version.
        // This is the minimal set of options; advanced options can be added via config later.
        tableEnv.executeSql(
                "CREATE TABLE " + tableName + " (" +
                        columnsDdl +
                        pkClause +
                        ") WITH (" +
                        "'connector' = 'mysql-cdc', " +
                        "'hostname' = '" + mysqlHost + "', " +
                        "'port' = '3306', " +
                        "'username' = '" + mysqlUser + "', " +
                        "'password' = '" + mysqlPassword + "', " +
                        "'database-name' = '" + mysqlDatabase + "', " +
                        "'table-name' = '" + tableName + "'" +
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
}
