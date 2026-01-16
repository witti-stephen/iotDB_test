package com.example;

public class SensorRecord {
    public String deviceId;
    public long ts;
    public double temperature;
    public double humidity;

    public SensorRecord() {}

    public SensorRecord(String deviceId, long ts, double temperature, double humidity) {
        this.deviceId = deviceId;
        this.ts = ts;
        this.temperature = temperature;
        this.humidity = humidity;
    }

    // Getters and setters if needed
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public double getHumidity() { return humidity; }
    public void setHumidity(double humidity) { this.humidity = humidity; }
}