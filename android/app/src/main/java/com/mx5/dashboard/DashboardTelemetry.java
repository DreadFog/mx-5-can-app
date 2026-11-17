package com.mx5.dashboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DashboardTelemetry {
    public enum Page { MAIN, DETAILS }

    public static final class Reading {
        public final double value;
        public final boolean simulated;
        public final String unit;

        private Reading(double value, boolean simulated, String unit) {
            this.value = value;
            this.simulated = simulated;
            this.unit = unit;
        }
    }

    public static final class Snapshot {
        public final Double rpm;
        public final Double speed;
        public final boolean rpmSimulated;
        public final boolean speedSimulated;
        public final String state;
        public final int threshold;
        public final int modelYear;
        private final Map<String, Reading> readings;

        private Snapshot(Double rpm, Double speed, boolean rpmSimulated, boolean speedSimulated,
            String state, int threshold, int modelYear, Map<String, Reading> readings) {
            this.rpm = rpm;
            this.speed = speed;
            this.rpmSimulated = rpmSimulated;
            this.speedSimulated = speedSimulated;
            this.state = state;
            this.threshold = threshold;
            this.modelYear = modelYear;
            this.readings = readings;
        }

        public Reading reading(String id) { return readings.get(id); }
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
    private static Page page = Page.MAIN;
    private static boolean autoVisible;
    private static List<DiagnosticSignal> signals = new ArrayList<>();
    private static final Map<String, Reading> readings = new HashMap<>();
    private static final Map<String, Long> updatedAt = new HashMap<>();

    private DashboardTelemetry() { }

    public static synchronized void configure(int rpmThreshold, int year) {
        threshold = rpmThreshold;
        modelYear = year;
    }

    public static synchronized void setState(String value) {
        state = value;
    }

    public static synchronized void configureSignals(List<DiagnosticSignal> catalogue) {
        signals = new ArrayList<>(catalogue);
        readings.clear();
        updatedAt.clear();
    }

    public static synchronized void showAutoPage(Page selected, boolean visible) {
        page = visible ? selected : Page.MAIN;
        autoVisible = visible;
    }

    public static synchronized Page page() { return page; }
    public static synchronized boolean autoVisible() { return autoVisible; }

    public static synchronized void accept(CanFrame frame, long now, boolean simulated) {
        acceptReply(DiagnosticReply.fromFrame(frame), now, simulated);
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

    public static synchronized void acceptReply(DiagnosticReply reply, long now, boolean simulated) {
        if (reply == null) return;
        for (DiagnosticSignal signal : signals) {
            if (!signal.matches(reply) || signal.negative(reply)) continue;
            Double value = signal.decode(reply);
            for (DiagnosticSignal flag : signals) {
                if (flag.id.equals(signal.id + "_SUP") && flag.matches(reply)) {
                    Double supported = flag.decode(reply);
                    if (supported == null || supported == 0) value = null;
                    break;
                }
            }
            if (value == null) {
                readings.remove(signal.id);
                updatedAt.remove(signal.id);
            } else {
                readings.put(signal.id, new Reading(value, simulated, signal.unit));
                updatedAt.put(signal.id, now);
            }
        }
    }

    public static synchronized Snapshot snapshot(long now) {
        Map<String, Reading> fresh = new HashMap<>();
        for (Map.Entry<String, Reading> entry : readings.entrySet()) {
            long at = updatedAt.get(entry.getKey());
            long lifetime = entry.getKey().equals("GEAR_ACT") ? 3000 : 10000;
            if (now >= at && now - at <= lifetime) fresh.put(entry.getKey(), entry.getValue());
        }
        return new Snapshot(rpm != null && now >= rpmAt && now - rpmAt <= 3000 ? rpm : null,
                speed != null && now >= speedAt && now - speedAt <= 3000 ? speed : null,
                rpmSimulated, speedSimulated, state, threshold, modelYear, fresh);
    }

    public static synchronized void clear(String value) {
        rpm = null;
        speed = null;
        readings.clear();
        updatedAt.clear();
        state = value;
    }
}