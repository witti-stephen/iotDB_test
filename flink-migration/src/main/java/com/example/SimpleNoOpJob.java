package com.example;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class SimpleNoOpJob {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Simple No-Op Job Started ===");
        System.out.println("Args: " + java.util.Arrays.toString(args));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.fromElements(1, 2, 3).print();

        System.out.println("Job submitted, executing...");
        env.execute("Simple No-Op Job");
        System.out.println("=== Simple No-Op Job Completed ===");
    }
}
