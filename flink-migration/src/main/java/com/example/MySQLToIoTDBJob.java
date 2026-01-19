package com.example;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.iotdb.flink.IoTDBSink;
import org.apache.iotdb.flink.options.IoTDBSinkOptions;
import org.apache.iotdb.flink.IoTSerializationSchema;
import org.apache.iotdb.flink.DefaultIoTSerializationSchema;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.types.Row;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class MySQLToIoTDBJob {
    public static void main(String[] args) throws Exception {
        // Load config
        Yaml yaml = new Yaml();
        String configPath = args.length > 0 ? args[0] : "/opt/flink/config/config.yaml";
        InputStream inputStream = new FileInputStream(configPath);
        Map<String, Object> config = yaml.load(inputStream);

        // Connections
        Map<String, Object> connections = (Map<String, Object>) config.get("connections");
        Map<String, Object> mysqlConn = (Map<String, Object>) connections.get("mysql");
        Map<String, Object> iotdbConn = (Map<String, Object>) connections.get("iotdb");

        Map<String, Object> iotdbConfig = (Map<String, Object>) config.get("iotdb");
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) iotdbConfig.get("mappings");

        // Set up streaming environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        EnvironmentSettings settings = EnvironmentSettings.newInstance().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, settings);

        // Process each mapping
        for (Map<String, Object> mapping : mappings) {
            processMapping(mapping, config, mysqlConn, iotdbConn, tableEnv, env);
        }

        env.execute("MySQL to IoTDB Migration");
    }

    private static void processMapping(Map<String, Object> mapping, Map<String, Object> config, Map<String, Object> mysqlConn, Map<String, Object> iotdbConn, StreamTableEnvironment tableEnv, StreamExecutionEnvironment env) throws Exception {
        String tableName = (String) mapping.get("table");
        String deviceField = (String) mapping.get("device_field");
        String timestampField = (String) mapping.get("timestamp_field");
        List<Map<String, Object>> measurements = (List<Map<String, Object>>) mapping.get("measurements");
        String deviceTemplate = (String) mapping.get("device_template");
        String storageGroup = deviceTemplate.split("\\.")[0] + "." + deviceTemplate.split("\\.")[1];


        Map<String, Object> mysqlConfig = (Map<String, Object>) config.get("mysql");
        List<Map<String, Object>> tables = (List<Map<String, Object>>) mysqlConfig.get("tables");
        Map<String, Object> tableConfig = tables.stream().filter(t -> tableName.equals(t.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> columns = (List<Map<String, Object>>) tableConfig.get("columns");

        // Connections
        String mysqlUrl = "jdbc:mysql://" + mysqlConn.get("host") + ":3306/" + mysqlConn.get("database");
        String mysqlUser = (String) mysqlConn.get("user");
        String mysqlPassword = (String) mysqlConn.get("password");

        String iotdbHost = (String) iotdbConn.get("host");
        int iotdbPort = (Integer) iotdbConn.get("port");
        String iotdbUser = (String) iotdbConn.get("user");
        String iotdbPassword = (String) iotdbConn.get("password");

        // Partitioning config
        Map<String, Object> partitioning = (Map<String, Object>) mysqlConfig.get("partitioning");
        String partitionColumn = partitioning != null ? (String) partitioning.get("column") : "id";
        String lowerBound = partitioning != null ? String.valueOf(partitioning.get("lower_bound")) : "1";
        String upperBound = partitioning != null ? String.valueOf(partitioning.get("upper_bound")) : "100000";
        String numPartitions = partitioning != null ? String.valueOf(partitioning.get("num")) : "10";

        // Build columns for Flink table
        StringBuilder columnsBuilder = new StringBuilder();
        for (Map<String, Object> col : columns) {
            String name = (String) col.get("name");
            String type = (String) col.get("type");
            String flinkType = mapToFlinkType(type);
            if (columnsBuilder.length() > 0) columnsBuilder.append(", ");
            columnsBuilder.append("`").append(name).append("` ").append(flinkType);
        }

        // Create JDBC table (inline, per connector docs)
        tableEnv.executeSql(
            "CREATE TABLE " + tableName + " (" +
            columnsBuilder.toString() +
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



        // Query table
        StringBuilder queryBuilder = new StringBuilder("SELECT `" + deviceField + "` AS deviceId, `" + timestampField + "` AS ts");
        for (Map<String, Object> meas : measurements) {
            String field = (String) meas.getOrDefault("field", meas.get("name"));
            queryBuilder.append(", `").append(field).append("`");
        }
        queryBuilder.append(" FROM ").append(tableName);
        String query = queryBuilder.toString();
        Table table = tableEnv.sqlQuery(query);

        // Convert to DataStream
        DataStream<Row> rowStream = tableEnv.toDataStream(table);

        // Transform to Map<String, String> for IoTDBSink
        DataStream<Map<String, String>> mapStream = rowStream.map(row -> convertRowToMap(row, deviceTemplate, deviceField, measurements)).returns(new TypeHint<Map<String, String>>() {});

        // Create IoTDBSink
        IoTDBSinkOptions options = new IoTDBSinkOptions();
        options.setHost(iotdbHost);
        options.setPort(iotdbPort);
        options.setUser(iotdbUser);
        options.setPassword(iotdbPassword);
        options.setTimeseriesOptionList(new java.util.ArrayList<>());

        IoTSerializationSchema serializationSchema = new DefaultIoTSerializationSchema();
        IoTDBSink ioTDBSink = new IoTDBSink(options, serializationSchema)
            .withBatchSize(1000)
            .withSessionPoolSize(3);

        // Sink to IoTDB
        mapStream.addSink(ioTDBSink);
    }

    private static String mapToFlinkType(String mysqlType) {
        switch (mysqlType.toUpperCase()) {
            case "BIGINT": return "BIGINT";
            case "VARCHAR(50)": return "STRING";
            case "VARCHAR(60)": return "STRING";
            case "TIMESTAMP": return "TIMESTAMP(3)";
            case "DATETIME(3)": return "TIMESTAMP(3)";
            case "INT": return "INT";
            case "FLOAT": return "FLOAT";
            case "DOUBLE": return "DOUBLE";
            case "DECIMAL(10,2)": return "DECIMAL(10,2)";
            default: return "STRING"; // fallback
        }
    }

    private static Map<String, String> convertRowToMap(Row row, String deviceTemplate, String deviceField, List<Map<String, Object>> measurements) {
        Map<String, String> map = new HashMap<>();
        // Assuming order: deviceId, ts, reading, type
        String deviceIdStr = String.valueOf(row.getField(0));
        String placeholder = "{" + deviceField + "}";
        map.put("device", deviceTemplate.replace(placeholder, deviceIdStr));

        // Convert timestamp to epoch milli
        Object tsObj = row.getField(1);
        long tsMs;
        if (tsObj instanceof LocalDateTime) {
            tsMs = ((LocalDateTime) tsObj).toInstant(ZoneOffset.UTC).toEpochMilli();
        } else {
            // Fallback, assume string or long
            tsMs = Long.parseLong(String.valueOf(tsObj));
        }
        map.put("timestamp", String.valueOf(tsMs));

        // Store measurements dynamically
        StringBuilder measurementsStr = new StringBuilder();
        StringBuilder typesStr = new StringBuilder();
        StringBuilder valuesStr = new StringBuilder();
        for (int i = 0; i < measurements.size(); i++) {
            Map<String, Object> meas = measurements.get(i);
            String name = (String) meas.get("name");
            String type = (String) meas.get("type");
            if (i > 0) {
                measurementsStr.append(",");
                typesStr.append(",");
                valuesStr.append(",");
            }
            measurementsStr.append(name);
            typesStr.append(type);
            valuesStr.append(String.valueOf(row.getField(2 + i)));
        }
        map.put("measurements", measurementsStr.toString());
        map.put("types", typesStr.toString());
        map.put("values", valuesStr.toString());
        return map;
    }


}