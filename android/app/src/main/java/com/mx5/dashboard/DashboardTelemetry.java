package com.mx5.dashboard;

public final class DashboardTelemetry {
    public static final class Snapshot {
        public final Double rpm;
        public final Double speed;
        public final boolean rpmSimulated;
        public final boolean speedSimulated;
        public final String state;
        public final int threshold;
        public final int modelYear;

        private Snapshot(Double rpm, Double speed, boolean rpmSimulated, boolean speedSimulated,
                String state, int threshold, int modelYear) {
            this.rpm = rpm;
            this.speed = speed;
            this.rpmSimulated = rpmSimulated;
            this.speedSimulated = speedSimulated;
            this.state = state;
            this.threshold = threshold;
            this.modelYear = modelYear;
        }
    }

    private static Double rpm;
    private static Double speed;
    private static long rpmAt;
    private static long speedAt;
    private static boolean rpmSimulated;
    private static boolean speedSimulated;
    private static String state = "No telemetry";
    private static int threshold = 6000;
    private static int modelYear = 2024;

    private DashboardTelemetry() { }

    public static synchronized void configure(int rpmThreshold, int year) {
        threshold = rpmThreshold;
        modelYear = year;
    }

    public static synchronized void setState(String value) {
        state = value;
    }

    public static synchronized void accept(CanFrame frame, long now, boolean simulated) {
        Double decodedRpm = Obd.decode(frame, Obd.RPM);
        Double decodedSpeed = Obd.decode(frame, Obd.SPEED);
        if (decodedRpm != null) {
            rpm = decodedRpm;
            rpmAt = now;
            rpmSimulated = simulated;
        }
        if (decodedSpeed != null) {
            speed = decodedSpeed;
            speedAt = now;
            speedSimulated = simulated;
        }
    }

    public static synchronized Snapshot snapshot(long now) {
        return new Snapshot(rpm != null && now >= rpmAt && now - rpmAt <= 3000 ? rpm : null,
                speed != null && now >= speedAt && now - speedAt <= 3000 ? speed : null,
                rpmSimulated, speedSimulated, state, threshold, modelYear);
    }

    public static synchronized void clear(String value) {
        rpm = null;
        speed = null;
        state = value;
    }
}