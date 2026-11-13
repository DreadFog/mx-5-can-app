import com.mx5.dashboard.CanFrame;
import com.mx5.dashboard.Obd;
import com.mx5.dashboard.RpmAlert;
import com.mx5.dashboard.DiagnosticSignal;
import com.mx5.dashboard.ModelYearFilter;
import com.mx5.dashboard.DashboardTelemetry;
import com.mx5.dashboard.TransmissionFilter;
import com.mx5.dashboard.ObdPidDiscovery;
import com.mx5.dashboard.DiagnosticReply;
import com.mx5.dashboard.IsoTpReceiver;
import java.util.Arrays;

public class ProtocolTest {
    public static void main(String[] args) {
        DiagnosticSignal standard = new DiagnosticSignal("VPWR", "Voltage", "volts", 0x7E0, 0x7E8,
            0x42, true, 0, 16, 1, 1000, 0, false, null, null);
        check(standard.request(0).data[2] == 0x42, "discovered PID request");
        check(standard.decode(new DiagnosticReply(0x7E8, new byte[]{0x41, 0x42, 0x36, 0x2E})) == 13.87, "SAE voltage fixture");
        DiagnosticSignal odometer = new DiagnosticSignal("ODO", "Odometer", "km", 0x7E0, 0x7E8,
            0xA6, true, 0, 32, 1, 10, 0, false, null, null);
        check(odometer.decode(new DiagnosticReply(0x7E8, new byte[]{0x41, (byte) 0xA6, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})) == 429496729.5, "unsigned 32-bit SAE value");
        DiagnosticSignal longField = new DiagnosticSignal("FRT_B", "Fuel temp", "celsius", 0x7E0, 0x7E8,
            0x6D, true, 80, 8, 1, 1, -40, false, null, null);
        check(longField.decode(new DiagnosticReply(0x7E8, new byte[]{0x41, 0x6D, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 90})) == 50, "SAE field beyond single CAN frame");
        IsoTpReceiver receiver = new IsoTpReceiver(longField);
        check(receiver.accept(new CanFrame(-1, 0x7E8, 0, 0,
            new byte[]{0x10, 13, 0x41, 0x6D, 0, 0, 0, 0})) == null && receiver.needsFlowControl(), "ISO-TP first frame");
        CanFrame flow = receiver.flowControl(5);
        check(flow.canId == 0x7E0 && flow.data[0] == 0x30 && flow.echoId == 5, "ISO-TP flow control targets requested ECU");
        DiagnosticReply assembled = receiver.accept(new CanFrame(-1, 0x7E8, 0, 0,
            new byte[]{0x21, 0, 0, 0, 0, 0, 0, 90}));
        check(assembled != null && longField.decode(assembled) == 50, "ISO-TP assembled long field");
        IsoTpReceiver wrong = new IsoTpReceiver(longField);
        wrong.accept(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{0x10, 13, 0x41, 0x6D, 0, 0, 0, 0}));
        wrong.flowControl(0);
        try {
            wrong.accept(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{0x22, 0, 0, 0, 0, 0, 0, 0}));
            throw new AssertionError("bad ISO-TP sequence accepted");
        } catch (IllegalArgumentException expected) { }
        IsoTpReceiver unrelated = new IsoTpReceiver(longField);
        unrelated.accept(new CanFrame(-1, 0x7E9, 0, 0, new byte[]{0x10, 13, 0x41, 0x6D, 0, 0, 0, 0}));
        check(!unrelated.needsFlowControl(), "no flow control for unrelated ECU");
        DiagnosticSignal enumSignal = new DiagnosticSignal("FUEL", "Fuel", "", 0x7E0, 0x7E8,
            0x51, true, 0, 8, 1, 1, 0, false, null, null).withFormat(null, null, java.util.Collections.singletonMap(1L, "GAS"));
        check(enumSignal.describe(new DiagnosticReply(0x7E8, new byte[]{0x41, 0x51, 1})).equals("GAS (1)"), "SAE enum mapping");
        check(!TransmissionFilter.allows(true, 0x7E1, 0x1E24), "manual hides torque converter");
        check(!TransmissionFilter.allows(true, 0x7E1, 0x1E1C), "manual hides automatic temperature");
        check(TransmissionFilter.allows(false, 0x7E1, 0x1EC7), "automatic keeps torque read");
        check(TransmissionFilter.allows(true, 0x7E0, 0xA211), "manual does not hide clutch signal");
        check(TransmissionFilter.allows(true, 0x7E1, 0x1234), "unknown transmission signal not blindly hidden");
        ObdPidDiscovery discovery = new ObdPidDiscovery();
        check(discovery.request().request(0).canId == 0x7E0, "support queries address engine ECU");
        check(Arrays.equals(discovery.request().request(0).data, new byte[]{2, 1, 0, 0, 0, 0, 0, 0}), "support request bytes");
        CanFrame mask = new CanFrame(-1, 0x7E8, 0, 0, new byte[]{6, 0x41, 0, (byte) 0x80, 0x18, 0, 1, 0});
        check(!discovery.accept(new CanFrame(-1, 0x7E9, 0, 0, mask.data)), "wrong ECU support mask ignored");
        check(!discovery.accept(new CanFrame(0, 0x7E8, 0, 0, mask.data)), "support echo ignored");
        check(discovery.accept(mask), "support mask accepted");
        check(discovery.getSupportedPids().equals(Arrays.asList(1, 12, 13)), "support bitmap MSB ordering");
        check(discovery.request().identifier == 0x20, "continuation follows next support block");
        check(discovery.accept(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{6, 0x41, 0x20, 0, 0, 0, 0, 0})), "empty final mask accepted");
        check(discovery.isComplete() && discovery.getBlocksRead() == 2, "discovery terminates without continuation");
        ObdPidDiscovery bounded = new ObdPidDiscovery();
        for (int base = 0; base <= 0xE0; base += 0x20) {
            check(bounded.accept(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{6, 0x41, (byte) base, 0, 0, 0, 1, 0})), "bounded support chain");
        }
        check(bounded.isComplete() && bounded.getBlocksRead() == 8, "maximum eight support blocks");
        DashboardTelemetry.clear("No telemetry");
        DashboardTelemetry.configure(4000, 2024);
        check(DashboardTelemetry.snapshot(1000).rpm == null, "Auto starts without invented RPM");
        DashboardTelemetry.accept(new CanFrame(-1, 0x7E8, 0, 0,
            new byte[]{4, 0x41, 0x0C, 0x0B, 0x40, 0, 0, 0}), 1000, true);
        DashboardTelemetry.accept(new CanFrame(-1, 0x7E8, 0, 0,
            new byte[]{3, 0x41, 0x0D, 42, 0, 0, 0, 0}), 2000, false);
        DashboardTelemetry.Snapshot auto = DashboardTelemetry.snapshot(4000);
        check(auto.rpm == 720 && auto.speed == 42, "Auto receives real decoded values");
        check(auto.rpmSimulated && !auto.speedSimulated, "Auto keeps source per measurement");
        check(auto.threshold == 4000 && auto.modelYear == 2024, "Auto shares configuration");
        check(DashboardTelemetry.snapshot(4001).rpm == null, "Auto RPM expires after three seconds");
        check(DashboardTelemetry.snapshot(4001).speed == 42, "Auto speed expires independently");
        DashboardTelemetry.clear("Phone dashboard closed");
        check(DashboardTelemetry.snapshot(4001).speed == null, "Auto clears closed session");
        check(ModelYearFilter.matches(2024, null, null, new int[0]), "unfiltered year allowed");
        check(ModelYearFilter.matches(2016, 2016, null, new int[0]), "from boundary inclusive");
        check(!ModelYearFilter.matches(2015, 2016, null, new int[0]), "year before from excluded");
        check(ModelYearFilter.matches(2015, null, 2015, new int[0]), "to boundary inclusive");
        check(!ModelYearFilter.matches(2024, null, 2015, new int[0]), "old-generation signals excluded");
        check(ModelYearFilter.matches(2024, null, null, new int[]{2017, 2024}), "explicit year allowed");
        check(!ModelYearFilter.matches(2023, null, null, new int[]{2017, 2024}), "unlisted year excluded");
        check(ModelYearFilter.matches(2024, 2016, 2005, new int[0]), "disjoint ranges union newer");
        check(ModelYearFilter.matches(2005, 2016, 2005, new int[0]), "disjoint ranges union older");
        check(!ModelYearFilter.matches(2010, 2016, 2005, new int[0]), "gap between ranges excluded");
        check(ModelYearFilter.matches(2010, 2016, 2005, new int[]{2010}), "explicit year fills gap");
        DiagnosticSignal map = new DiagnosticSignal("MAP", "MAP", "volts", 0x7E0, 0x7E8,
            0x0301, false, 0, 16, 1, 1000, 0, false, null, null);
        check(Arrays.equals(map.request(3).data, new byte[]{3, 0x22, 3, 1, 0, 0, 0, 0}), "UDS request");
        CanFrame mapResponse = new CanFrame(-1, 0x7E8, 0, 0, new byte[]{5, 0x62, 3, 1, 4, (byte) 0xD2, 0, 0});
        check(map.decode(mapResponse) == 1.234, "big endian UDS numeric field");
        map.withFormat(0.0, 4.0, java.util.Collections.emptyMap());
        check(map.decode(new DiagnosticReply(0x7E8, new byte[]{0x62, 3, 1, 0x10, 0})) == 4, "OBDb max value clamp");
        check(map.decode(new CanFrame(-1, 0x7E9, 0, 0, mapResponse.data)) == null, "wrong ECU rejected");
        check(map.decode(new CanFrame(3, 0x7E8, 0, 0, mapResponse.data)) == null, "UDS echo rejected");
        check(map.decode(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{4, 0x62, 3, 1, 4, 0, 0, 0})) == null, "short UDS payload");
        CanFrame negative = new CanFrame(-1, 0x7E8, 0, 0, new byte[]{3, 0x7F, 0x22, 0x31, 0, 0, 0, 0});
        check(map.matches(negative) && map.negative(negative) && map.decode(negative) == null, "UDS negative response");
        DiagnosticSignal brake = new DiagnosticSignal("BRAKE", "Brake", "offon", 0x7E0, 0x7E8,
            0xA211, false, 6, 1, 1, 1, 0, false, null, null);
        check(brake.decode(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{4, 0x62, (byte) 0xA2, 0x11, 2, 0, 0, 0})) == 1, "MSB-indexed brake bit");
        DiagnosticSignal pressure = new DiagnosticSignal("PRESSURE", "Pressure", "kPa", 0x7E0, 0x7E8,
            0x0415, false, 0, 16, 1, 1, 0, true, null, null);
        check(pressure.decode(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{5, 0x62, 4, 0x15, (byte) 0xFF, (byte) 0xFE, 0, 0})) == -2, "signed UDS field");
        check(RpmAlert.progress(4000, 6000, true) == 0, "no alert below approach band");
        check(RpmAlert.progress(4500, 6000, true) == 0, "alert starts at 75 percent");
        check(Math.abs(RpmAlert.progress(5400, 6000, true) - 0.6f) < 0.0001f, "orange at 90 percent");
        check(RpmAlert.progress(6000, 6000, true) == 1, "bright red at exact threshold");
        check(RpmAlert.progress(7500, 6000, true) == 1, "brightness clamped above threshold");
        check(RpmAlert.progress(7500, 6000, false) == 0, "stale reading clears alert");
        check(RpmAlert.progress(Double.NaN, 6000, true) == 0, "invalid RPM clears alert");
        CanFrame rpm = new CanFrame(-1, 0x7E8, 0, 0, new byte[]{4, 0x41, 0x0C, 0x0B, 0x45, 0, 0, 0});
        check(Obd.decode(CanFrame.decode(rpm.encode(), 20), Obd.RPM) == 721.25, "recorded RPM");
        CanFrame speed = new CanFrame(-1, 0x7E8, 0, 0, new byte[]{3, 0x41, 0x0D, 42});
        check(Obd.decode(speed, Obd.SPEED) == 42, "speed");
        check(Obd.decode(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{3, 0x41, 0x0D, (byte) 255}), Obd.SPEED) == null, "invalid speed sentinel");
        check(Obd.decode(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{4, 0x41, 0x0C, (byte) 255, (byte) 255}), Obd.RPM) == null, "invalid RPM sentinel");
        check(Obd.decode(new CanFrame(0, 0x7E8, 0, 0, rpm.data), Obd.RPM) == null, "echo rejected");
        check(Obd.decode(new CanFrame(-1, 0x7E8, 0, 0, new byte[]{4, 0x41, 0x0C, 0x0B}), Obd.RPM) == null, "short response rejected");
        check(Arrays.equals(Obd.request(Obd.RPM, 2).data, new byte[]{2, 1, 12, 0, 0, 0, 0, 0}), "request bytes");
        try {
            Obd.request(4, 0);
            throw new AssertionError("unsupported PID accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            CanFrame.decode(new byte[13], 12);
        } catch (IllegalArgumentException error) {
            throw new AssertionError(error);
        }
        byte[] invalid = new byte[20];
        invalid[8] = 9;
        try {
            CanFrame.decode(invalid, invalid.length);
            throw new AssertionError("invalid DLC accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Protocol tests passed");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}