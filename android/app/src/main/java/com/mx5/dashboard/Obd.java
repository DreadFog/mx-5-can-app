package com.mx5.dashboard;

public final class Obd {
    public static final int RPM = 0x0C;
    public static final int SPEED = 0x0D;

    public static CanFrame request(int pid, int echoId) {
        if (pid != RPM && pid != SPEED && !isSupportPid(pid)) throw new IllegalArgumentException("Unsupported PID");
        return new CanFrame(echoId, 0x7DF, 0, 0, new byte[]{2, 1, (byte) pid, 0, 0, 0, 0, 0});
    }

    public static boolean isSupportPid(int pid) {
        return pid >= 0 && pid <= 0xE0 && pid % 0x20 == 0;
    }

    public static Double decode(CanFrame frame, int pid) {
        byte[] data = frame.data;
        if (frame.echoId != CanFrame.RECEIVED || frame.canId < 0x7E8 || frame.canId > 0x7EF
                || data.length < 4 || data[0] < 3 || data[0] > 7 || data[0] + 1 > data.length
                || (data[1] & 0xFF) != 0x41 || (data[2] & 0xFF) != pid) return null;
        if (pid == RPM && data[0] >= 4) {
            double rpm = ((data[3] & 0xFF) * 256 + (data[4] & 0xFF)) / 4.0;
            return rpm >= 16383.5 ? null : rpm;
        }
        if (pid == SPEED) return (data[3] & 0xFF) == 255 ? null : (double) (data[3] & 0xFF);
        return null;
    }
}