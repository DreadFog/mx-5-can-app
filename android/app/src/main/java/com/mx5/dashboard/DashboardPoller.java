package com.mx5.dashboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DashboardPoller {
    public interface Transport {
        int send(DiagnosticSignal signal) throws Exception;
        void log(String message);
    }

    private static final String[] MAIN = {"OBD_RPM", "OBD_SPEED", "GEAR_ACT", "MX5_EOT", "MX5_FLV"};
    private static final String[] DETAILS = {"MX5_TP_FL", "MX5_TP_FR", "MX5_TP_RL", "MX5_TP_RR",
            "MX5_TT_FL", "MX5_TT_FR", "MX5_TT_RL", "MX5_TT_RR", "ECT", "VPWR", "IAT", "MX5_EOP"};
    private final List<DiagnosticSignal> signals;
    private final Transport transport;
    private final Map<String, Long> due = new HashMap<>();
    private DiagnosticSignal pending;
    private int sequence = -1;
    private boolean acknowledged;
    private boolean replied;
    private long deadline;
    private long nextSend;

    public DashboardPoller(List<DiagnosticSignal> signals, Transport transport) {
        this.signals = new ArrayList<>(signals);
        this.transport = transport;
    }

    public void tick(long now, DashboardTelemetry.Page page, boolean enabled) {
        if (pending != null && now >= deadline) fail(now, "timeout");
        if (!enabled || pending != null || now < nextSend) return;
        DiagnosticSignal selected = null;
        long earliest = Long.MAX_VALUE;
        for (DiagnosticSignal signal : requests(page)) {
            long at = due.getOrDefault(key(signal), 0L);
            if (at <= now && at < earliest) {
                selected = signal;
                earliest = at;
            }
        }
        if (selected == null) return;
        pending = selected;
        acknowledged = false;
        replied = false;
        deadline = now + 2500;
        nextSend = now + 100;
        due.put(key(selected), now + period(selected));
        try {
            sequence = transport.send(selected);
            acknowledged = sequence < 0;
        } catch (Exception error) {
            fail(now, error.getMessage());
        }
    }

    public List<DiagnosticSignal> requests(DashboardTelemetry.Page page) {
        List<DiagnosticSignal> selected = new ArrayList<>();
        for (String id : page == DashboardTelemetry.Page.MAIN ? MAIN : DETAILS) {
            for (DiagnosticSignal signal : signals) {
                if (!signal.id.equals(id)) continue;
                boolean duplicate = false;
                for (DiagnosticSignal existing : selected) {
                    if (key(existing).equals(key(signal))) duplicate = true;
                }
                if (!duplicate) selected.add(signal);
                break;
            }
        }
        return selected;
    }

    public boolean acknowledge(int receivedSequence, boolean accepted, long now) {
        if (pending == null || sequence < 0 || sequence != receivedSequence) return false;
        if (!accepted) fail(now, "simulator rejected request");
        else {
            acknowledged = true;
            if (replied) finish();
        }
        return true;
    }

    public void accept(DiagnosticReply reply, long now) {
        if (pending == null || reply == null || !pending.matches(reply)) return;
        if (pending.negative(reply)) {
            if ((reply.payload[2] & 255) != 0x78) fail(now, "ECU refusal");
            return;
        }
        if (pending.decode(reply) == null) {
            fail(now, "invalid response");
            return;
        }
        replied = true;
        if (acknowledged) finish();
    }

    public boolean busy() { return pending != null; }

    public void reset() {
        finish();
        due.clear();
        nextSend = 0;
    }

    private void fail(long now, String reason) {
        due.put(key(pending), now + 60000);
        transport.log("Dashboard " + pending.id + ": " + reason + "; retry in 60 s");
        finish();
    }

    private void finish() {
        pending = null;
        sequence = -1;
    }

    private static String key(DiagnosticSignal signal) {
        return signal.requestId + ":" + signal.responseId + ":" + signal.obd + ":" + signal.identifier;
    }

    private static long period(DiagnosticSignal signal) {
        if (signal.id.equals("OBD_RPM")) return 200;
        if (signal.id.equals("OBD_SPEED") || signal.id.equals("GEAR_ACT")) return 500;
        return 2000;
    }
}