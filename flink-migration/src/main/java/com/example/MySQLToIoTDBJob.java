package com.example;

/**
 * Backwards-compatible entrypoint.
 * <p>
 * Prefer using {@link MySQLToIoTDBBackfillJob} for bounded migration and
 * {@link MySQLToIoTDBCdcJob} for continuous replication.
 */
public class MySQLToIoTDBJob {
    public static void main(String[] args) throws Exception {
        MySQLToIoTDBBackfillJob.main(args);
    }
}
