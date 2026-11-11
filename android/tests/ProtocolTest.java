import com.mx5.dashboard.CanFrame;
import com.mx5.dashboard.Obd;
import java.util.Arrays;

public class ProtocolTest {
    public static void main(String[] args) {
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