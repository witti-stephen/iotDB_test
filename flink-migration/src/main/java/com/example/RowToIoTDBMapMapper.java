package com.example;

import org.apache.flink.types.Row;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RowToIoTDBMapMapper {
    private RowToIoTDBMapMapper() {}

    public static Map<String, String> convertRowToMap(
            Row row,
            String deviceTemplate,
            String deviceField,
            List<Map<String, Object>> measurements
    ) {
        Map<String, String> map = new HashMap<>();

        String deviceIdStr = String.valueOf(row.getField(0));
        String placeholder = "{" + deviceField + "}";
        map.put("device", deviceTemplate.replace(placeholder, deviceIdStr));

        Object tsObj = row.getField(1);
        long tsMs;
        if (tsObj instanceof LocalDateTime) {
            tsMs = ((LocalDateTime) tsObj).toInstant(ZoneOffset.UTC).toEpochMilli();
        } else {
            tsMs = Long.parseLong(String.valueOf(tsObj));
        }
        map.put("timestamp", String.valueOf(tsMs));

        StringBuilder measurementsStr = new StringBuilder();
        StringBuilder typesStr = new StringBuilder();
        StringBuilder valuesStr = new StringBuilder();

        int written = 0;
        for (int i = 0; i < measurements.size(); i++) {
            Map<String, Object> meas = measurements.get(i);
            String name = (String) meas.get("name");
            String type = (String) meas.get("type");

            Object value = row.getField(2 + i);
            if (value == null) {
                continue;
            }

            if (written > 0) {
                measurementsStr.append(",");
                typesStr.append(",");
                valuesStr.append(",");
            }

            measurementsStr.append(name);
            typesStr.append(type);
            valuesStr.append(String.valueOf(value));
            written++;
        }

        if (written == 0) {
            return null;
        }

        map.put("measurements", measurementsStr.toString());
        map.put("types", typesStr.toString());
        map.put("values", valuesStr.toString());
        return map;
    }
}
