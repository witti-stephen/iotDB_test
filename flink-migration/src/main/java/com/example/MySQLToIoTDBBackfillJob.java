package com.example;

import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.iotdb.flink.IoTDBSink;

import java.util.Objects;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class MySQLToIoTDBBackfillJob {
    public static void main(String[] args) throws Exception {
        MigrationConfig config = ConfigLoader.load(args);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        EnvironmentSettings settings = EnvironmentSettings.newInstance().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, settings);

        IoTDBSink iotdbSink = IoTDBSinkFactory.create(config.iotdbConnection());

        for (Map<String, Object> mapping : config.mappings()) {
            processMapping(mapping, config, tableEnv, iotdbSink);
        }

        env.execute("MySQL to IoTDB Backfill");
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
        String mysqlHost = String.valueOf(mysqlConn.get("host"));
        String mysqlPort = String.valueOf(mysqlConn.getOrDefault("port", 3306));
        String mysqlDatabase = String.valueOf(mysqlConn.get("database"));

        String mysqlUrl = "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase;
        String mysqlUser = (String) mysqlConn.get("user");
        String mysqlPassword = (String) mysqlConn.get("password");

        Map<String, Object> tableConfig = config.mysqlTables().stream()
                .filter(t -> tableName.equals(t.get("name")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> columns = (List<Map<String, Object>>) tableConfig.get("columns");

        Map<String, Object> partitioning = config.mysqlPartitioning();
        String partitionColumn = partitioning != null ? (String) partitioning.get("column") : "id";
        String lowerBound = partitioning != null ? String.valueOf(partitioning.get("lower_bound")) : "1";
        String upperBound = partitioning != null ? String.valueOf(partitioning.get("upper_bound")) : "100000";
        String numPartitions = partitioning != null ? String.valueOf(partitioning.get("num")) : "10";

        String columnsDdl = SchemaBuilder.buildColumnsDdl(columns);

        tableEnv.executeSql(
                "CREATE TABLE " + tableName + " (" +
                        columnsDdl +
                        ") WITH (" +
                        "'connector' = 'jdbc', " +
                        "'url' = '" + mysqlUrl + "', " +
                        "'table-name' = '" + tableName + "', " +
                        "'username' = '" + mysqlUser + "', " +
                        "'password' = '" + mysqlPassword + "', " +
                        "'scan.fetch-size' = '1000', " +
                        "'scan.partition.column' = '" + partitionColumn + "', " +
                        "'scan.partition.lower-bound' = '" + lowerBound + "', " +
                        "'scan.partition.upper-bound' = '" + upperBound + "', " +
                        "'scan.partition.num' = '" + numPartitions + "'" +
                        ")"
        );

        String query = QueryBuilder.buildSelect(tableName, deviceField, timestampField, measurements);
        Table table = tableEnv.sqlQuery(query);

        DataStream<Row> rowStream = tableEnv.toDataStream(table);
        DataStream<Map<String, String>> mapStream = rowStream
                .map(row -> RowToIoTDBMapMapper.convertRowToMap(row, deviceTemplate, deviceField, measurements))
                .returns(new TypeHint<Map<String, String>>() {})
                .filter(Objects::nonNull);

        mapStream.addSink(iotdbSink);
    }
}
