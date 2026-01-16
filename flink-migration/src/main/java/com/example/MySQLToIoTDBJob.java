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
import org.apache.iotdb.flink.DefaultIoTSerializationSchema;



import java.util.HashMap;
import java.util.Map;

public class MySQLToIoTDBJob {
    public static void main(String[] args) throws Exception {
        // Set up streaming environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        EnvironmentSettings settings = EnvironmentSettings.newInstance().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, settings);

        // Create JDBC table (inline, per connector docs)
        tableEnv.executeSql(
            "CREATE TABLE sensor_data (" +
            "device_id STRING, " +
            "`timestamp` TIMESTAMP(3), " +
            "temperature DOUBLE, " +
            "humidity DOUBLE" +
             ") WITH (" +
             "'connector' = 'jdbc', " +
             "'url' = 'jdbc:mysql://mysql:3306/testdb', " +
             "'table-name' = 'sensor_data', " +
             "'username' = 'user', " +
             "'password' = 'password', " +
             "'scan.fetch-size' = '1000', " +
             "'scan.partition.column' = 'id', " +
             "'scan.partition.lower-bound' = '1', " +
             "'scan.partition.upper-bound' = '100000', " +
             "'scan.partition.num' = '10'" +
            ")"
        );

        // Query table
        Table table = tableEnv.sqlQuery("SELECT device_id AS deviceId, UNIX_TIMESTAMP(CAST(`timestamp` AS STRING)) * 1000 AS ts, temperature, humidity FROM sensor_data");

        // Convert to DataStream
        DataStream<SensorRecord> stream = tableEnv.toDataStream(table, SensorRecord.class);

        // Transform to Map<String, String> for IoTDBSink
        DataStream<Map<String, String>> mapStream = stream.map(record -> convertToMap(record)).returns(Types.MAP(Types.STRING, Types.STRING));

        // Create IoTDBSink
    IoTDBSinkOptions options = new IoTDBSinkOptions();
    options.setHost("iotdb");
    options.setPort(6667);
    options.setUser("root");
    options.setPassword("root");
    options.setTimeseriesOptionList(new java.util.ArrayList<>());

        IoTSerializationSchema serializationSchema = new DefaultIoTSerializationSchema();
        IoTDBSink ioTDBSink = new IoTDBSink(options, serializationSchema)
            .withBatchSize(1000)
            .withSessionPoolSize(3);

        // Sink to IoTDB
        mapStream.addSink(ioTDBSink);

        env.execute("MySQL to IoTDB Migration");
    }

    private static Map<String, String> convertToMap(SensorRecord record) {
        Map<String, String> map = new HashMap<>();
        map.put("device", "root.device." + record.getDeviceId());
        map.put("timestamp", String.valueOf(record.getTs()));
        map.put("measurements", "temperature,humidity");
        map.put("types", "DOUBLE,DOUBLE");
        map.put("values", record.getTemperature() + "," + record.getHumidity());
        return map;
    }
}