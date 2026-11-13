package com.mx5.dashboard;

public final class IsoTpReceiver {
    private final DiagnosticSignal signal;
    private byte[] buffer;
    private int count;
    private int sequence = 1;
    private boolean flowControlPending;

    public IsoTpReceiver(DiagnosticSignal signal) {
        this.signal = signal;
    }

    public boolean needsFlowControl() { return flowControlPending; }

    public CanFrame flowControl(int echoId) {
        if (!flowControlPending) throw new IllegalStateException("No ISO-TP flow control pending");
        flowControlPending = false;
        return new CanFrame(echoId, signal.requestId, 0, 0, new byte[]{0x30, 0, 5, 0, 0, 0, 0, 0});
    }

    public DiagnosticReply accept(CanFrame frame) {
        if (frame.echoId != CanFrame.RECEIVED || frame.channel != 0 || frame.canId != signal.responseId
                || frame.data.length < 2) return null;
        int type = (frame.data[0] & 255) >>> 4;
        if (type == 0) {
            DiagnosticReply reply = DiagnosticReply.fromFrame(frame);
            return signal.matches(reply) ? reply : null;
        }
        if (type == 1) {
            int length = ((frame.data[0] & 15) << 8) | (frame.data[1] & 255);
            if (length <= 7 || length > 256) throw new IllegalArgumentException("Unsupported ISO-TP reply length");
            byte[] prefix = java.util.Arrays.copyOfRange(frame.data, 2, frame.data.length);
            if (prefix.length < 2 || !signal.matches(new DiagnosticReply(frame.canId, prefix))) return null;
            buffer = new byte[length];
            count = Math.min(prefix.length, length);
            System.arraycopy(prefix, 0, buffer, 0, count);
            sequence = 1;
            flowControlPending = true;
            return null;
        }
        if (type != 2 || buffer == null || flowControlPending) return null;
        if ((frame.data[0] & 15) != sequence) {
            buffer = null;
            throw new IllegalArgumentException("ISO-TP consecutive-frame sequence mismatch");
        }
        sequence = (sequence + 1) & 15;
        int received = Math.min(frame.data.length - 1, buffer.length - count);
        System.arraycopy(frame.data, 1, buffer, count, received);
        count += received;
        if (count < buffer.length) return null;
        DiagnosticReply reply = new DiagnosticReply(frame.canId, buffer);
        buffer = null;
        return signal.matches(reply) ? reply : null;
    }
}