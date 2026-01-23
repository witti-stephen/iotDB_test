package com.example;

import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class MigrationConfig {
    private final Map<String, Object> root;

    public MigrationConfig(Map<String, Object> root) {
        this.root = root;
    }

    public Map<String, Object> connections() {
        return (Map<String, Object>) root.get("connections");
    }

    public Map<String, Object> mysqlConnection() {
        return (Map<String, Object>) connections().get("mysql");
    }

    public Map<String, Object> iotdbConnection() {
        return (Map<String, Object>) connections().get("iotdb");
    }

    public Map<String, Object> mysqlConfig() {
        return (Map<String, Object>) root.get("mysql");
    }

    public Map<String, Object> iotdbConfig() {
        return (Map<String, Object>) root.get("iotdb");
    }

    public List<Map<String, Object>> mappings() {
        return (List<Map<String, Object>>) iotdbConfig().get("mappings");
    }

    public List<Map<String, Object>> mysqlTables() {
        return (List<Map<String, Object>>) mysqlConfig().get("tables");
    }

    public Map<String, Object> mysqlPartitioning() {
        return (Map<String, Object>) mysqlConfig().get("partitioning");
    }
}

