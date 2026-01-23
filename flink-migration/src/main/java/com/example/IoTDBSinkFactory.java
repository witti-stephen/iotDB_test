package com.example;

import org.apache.iotdb.flink.DefaultIoTSerializationSchema;
import org.apache.iotdb.flink.IoTDBSink;
import org.apache.iotdb.flink.IoTSerializationSchema;
import org.apache.iotdb.flink.options.IoTDBSinkOptions;

import java.util.ArrayList;
import java.util.Map;

public final class IoTDBSinkFactory {
    private IoTDBSinkFactory() {}

    public static IoTDBSink create(Map<String, Object> iotdbConn) {
        String host = (String) iotdbConn.get("host");
        int port = (Integer) iotdbConn.get("port");
        String user = (String) iotdbConn.get("user");
        String password = (String) iotdbConn.get("password");

        IoTDBSinkOptions options = new IoTDBSinkOptions();
        options.setHost(host);
        options.setPort(port);
        options.setUser(user);
        options.setPassword(password);
        options.setTimeseriesOptionList(new ArrayList<>());

        IoTSerializationSchema serializationSchema = new DefaultIoTSerializationSchema();
        return new IoTDBSink(options, serializationSchema)
                .withBatchSize(1000)
                .withSessionPoolSize(3);
    }
}

