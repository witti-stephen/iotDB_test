package com.example;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class ConfigLoader {
    /**
     * Default location for Kubernetes deployments in this repo.
     *
     * The job submission script copies configs into Flink's {@code usrlib}.
     */
    private static final String DEFAULT_CONFIG_PATH = "/opt/flink/usrlib/config.yaml";

    private ConfigLoader() {}

    public static MigrationConfig load(String[] args) throws IOException {
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG_PATH;

        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(configPath)) {
            Map<String, Object> root = yaml.load(inputStream);
            if (root == null) {
                throw new IOException("Empty config: " + configPath);
            }
            return new MigrationConfig(root);
        }
    }
}
