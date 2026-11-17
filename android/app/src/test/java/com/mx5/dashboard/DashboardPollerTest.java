package com.mx5.dashboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DashboardPollerTest {
    private final List<DiagnosticSignal> sent = new ArrayList<>();
    private final DiagnosticSignal rpm = signal("OBD_RPM", 12, true);
    private final DiagnosticSignal coolant = signal("ECT", 5, true);
    private final DashboardPoller.Transport transport = new DashboardPoller.Transport() {
        @Override public int send(DiagnosticSignal signal) { sent.add(signal); return -1; }
        @Override public void log(String message) { }
    };

    @Test public void switchingPagesDrainsOnlyThePendingRequest() {
        DashboardPoller poller = new DashboardPoller(Arrays.asList(rpm, coolant), transport);
        poller.tick(0, DashboardTelemetry.Page.MAIN, true);
        poller.tick(100, DashboardTelemetry.Page.DETAILS, true);
        assertEquals(1, sent.size());
        poller.accept(new DiagnosticReply(0x7E8, new byte[]{0x41, 12, 1}), 110);
        poller.tick(110, DashboardTelemetry.Page.DETAILS, true);
        assertSame(coolant, sent.get(1));
        poller.accept(new DiagnosticReply(0x7E8, new byte[]{0x41, 5, 80}), 120);
        poller.tick(210, DashboardTelemetry.Page.DETAILS, true);
        assertEquals(2, sent.size());
    }

    @Test public void rateLimitAndTimeoutBackoffPreventFlooding() {
        DashboardPoller poller = new DashboardPoller(Arrays.asList(rpm, coolant), transport);
        poller.tick(0, DashboardTelemetry.Page.MAIN, true);
        poller.accept(new DiagnosticReply(0x7E8, new byte[]{0x41, 12, 1}), 10);
        poller.tick(99, DashboardTelemetry.Page.DETAILS, true);
        assertEquals(1, sent.size());
        poller.tick(100, DashboardTelemetry.Page.DETAILS, true);
        poller.tick(2600, DashboardTelemetry.Page.DETAILS, true);
        poller.tick(10000, DashboardTelemetry.Page.DETAILS, true);
        assertEquals(2, sent.size());
        poller.tick(62600, DashboardTelemetry.Page.DETAILS, true);
        assertEquals(3, sent.size());
    }

    @Test public void simulatorWaitsForBothReplyAndAcknowledgement() {
        DashboardPoller poller = new DashboardPoller(Arrays.asList(rpm), new DashboardPoller.Transport() {
            @Override public int send(DiagnosticSignal signal) { return 42; }
            @Override public void log(String message) { }
        });
        poller.tick(0, DashboardTelemetry.Page.MAIN, true);
        poller.accept(new DiagnosticReply(0x7E8, new byte[]{0x41, 12, 1}), 10);
        assertTrue(poller.busy());
        assertFalse(poller.acknowledge(43, true, 20));
        assertTrue(poller.acknowledge(42, true, 20));
        assertFalse(poller.busy());
    }

    @Test public void sharedWheelRepliesProduceOnlyOneRequestPerDid() {
        DashboardPoller poller = new DashboardPoller(Arrays.asList(signal("MX5_TP_FL", 0xC901, false),
                signal("MX5_TP_FR", 0xC901, false), coolant), transport);
        assertEquals(2, poller.requests(DashboardTelemetry.Page.DETAILS).size());
        poller.tick(0, DashboardTelemetry.Page.MAIN, false);
        assertTrue(sent.isEmpty());
    }

    @Test public void unsupportedGearIsNeverDisplayedAndReadingsExpire() {
        DiagnosticSignal gear = signal("GEAR_ACT", 0xA4, true);
        DiagnosticSignal support = new DiagnosticSignal("GEAR_ACT_SUP", "supported", "", 0x7E0, 0x7E8,
                0xA4, true, 0, 1, 1, 1, 0, false, null, null);
        DashboardTelemetry.configureSignals(Arrays.asList(gear, support));
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8, new byte[]{0x41, (byte) 0xA4, 3}), 100, true);
        assertNull(DashboardTelemetry.snapshot(100).reading("GEAR_ACT"));
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8, new byte[]{0x41, (byte) 0xA4, (byte) 131}), 200, true);
        assertNotNull(DashboardTelemetry.snapshot(200).reading("GEAR_ACT"));
        assertNull(DashboardTelemetry.snapshot(10201).reading("GEAR_ACT"));
        DashboardTelemetry.clear("test complete");
    }

    private static DiagnosticSignal signal(String id, int identifier, boolean obd) {
        return new DiagnosticSignal(id, id, "", 0x7E0, 0x7E8, identifier, obd, 0, 8,
                1, 1, 0, false, null, null);
    }
}