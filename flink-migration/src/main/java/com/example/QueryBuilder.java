package com.example;

import java.util.List;
import java.util.Map;

public final class QueryBuilder {
    private QueryBuilder() {}

    public static String buildSelect(
            String tableName,
            String deviceField,
            String timestampField,
            List<Map<String, Object>> measurements
    ) {
        StringBuilder queryBuilder = new StringBuilder(
                "SELECT `" + deviceField + "` AS deviceId, `" + timestampField + "` AS ts"
        );

        for (Map<String, Object> meas : measurements) {
            String field = (String) meas.getOrDefault("field", meas.get("name"));
            queryBuilder.append(", `").append(field).append("`");
        }
        queryBuilder.append(" FROM ").append(tableName);
        return queryBuilder.toString();
    }
}

