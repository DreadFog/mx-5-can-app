package com.mx5.dashboard;

public final class DiagnosticReply {
    public final int canId;
    public final byte[] payload;

    public DiagnosticReply(int canId, byte[] payload) {
        if (canId < 0 || canId > 0x7FF || payload.length < 1 || payload.length > 256) {
            throw new IllegalArgumentException("Invalid diagnostic reply");
        }
        this.canId = canId;
        this.payload = payload.clone();
    }

    public static DiagnosticReply fromFrame(CanFrame frame) {
        if (frame.echoId != CanFrame.RECEIVED || frame.channel != 0 || frame.canId < 0
                || frame.canId > 0x7FF || frame.data.length < 2) return null;
        int length = frame.data[0] & 255;
        if (length < 1 || length > 7 || length + 1 > frame.data.length) return null;
        return new DiagnosticReply(frame.canId, java.util.Arrays.copyOfRange(frame.data, 1, length + 1));
    }
}