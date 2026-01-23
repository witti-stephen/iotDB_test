package com.example;

import java.util.List;
import java.util.Map;

public final class SchemaBuilder {
    private SchemaBuilder() {}

    public static String buildColumnsDdl(List<Map<String, Object>> columns) {
        StringBuilder columnsBuilder = new StringBuilder();
        for (Map<String, Object> col : columns) {
            String name = (String) col.get("name");
            String type = (String) col.get("type");
            String flinkType = mapToFlinkType(type);
            if (columnsBuilder.length() > 0) {
                columnsBuilder.append(", ");
            }
            columnsBuilder.append("`").append(name).append("` ").append(flinkType);
        }
        return columnsBuilder.toString();
    }

    public static String mapToFlinkType(String mysqlType) {
        if (mysqlType == null) {
            return "STRING";
        }

        String normalized = mysqlType.toUpperCase();
        return switch (normalized) {
            case "BIGINT" -> "BIGINT";
            case "TIMESTAMP" -> "TIMESTAMP(3)";
            case "DATETIME(3)" -> "TIMESTAMP(3)";
            case "INT" -> "INT";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "DECIMAL(10,2)" -> "DECIMAL(10,2)";
            default -> {
                if (normalized.startsWith("VARCHAR(")) {
                    yield "STRING";
                }
                yield "STRING";
            }
        };
    }
}

