import com.mx5.dashboard.CanFrame;
import com.mx5.dashboard.Obd;
import com.mx5.dashboard.RpmAlert;
import com.mx5.dashboard.DiagnosticSignal;
import java.util.Arrays;

public class ProtocolTest {
    public static void main(String[] args) {
        DiagnosticSignal map = new DiagnosticSignal("MAP", "MAP", "volts", 0x7E0, 0x7E8,
            0x0301, false, 0, 16, 1, 1000, 0, false, null, null);
        check(Arrays.equals(map.request(3).data, new byte[]{3, 0x22, 3, 1, 0, 0, 0, 0}), "UDS request");
        CanFrame mapResponse = new CanFrame(-1, 0x7E8, 0, 0, new byte[]{5, 0x62, 3, 1, 4, (byte) 0xD2, 0, 0});
        check(map.decode(mapResponse) == 1.234, "big endian UDS numeric field");
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