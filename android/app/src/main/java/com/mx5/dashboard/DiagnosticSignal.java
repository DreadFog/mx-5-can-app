package com.mx5.dashboard;

public final class DiagnosticSignal {
    public final String id;
    public final String name;
    public final String unit;
    public final int requestId;
    public final int responseId;
    public final int identifier;
    public final boolean obd;
    public final int bitIndex;
    public final int bitLength;
    public final double multiplier;
    public final double divisor;
    public final double offset;
    public final boolean signed;
    public final Double nullMin;
    public final Double nullMax;

    public DiagnosticSignal(String id, String name, String unit, int requestId, int responseId,
            int identifier, boolean obd, int bitIndex, int bitLength, double multiplier,
            double divisor, double offset, boolean signed, Double nullMin, Double nullMax) {
        this.id = id;
        this.name = name;
        this.unit = unit;
        this.requestId = requestId;
        this.responseId = responseId;
        this.identifier = identifier;
        this.obd = obd;
        this.bitIndex = bitIndex;
        this.bitLength = bitLength;
        this.multiplier = multiplier;
        this.divisor = divisor;
        this.offset = offset;
        this.signed = signed;
        this.nullMin = nullMin;
        this.nullMax = nullMax;
    }

    public CanFrame request(int echoId) {
        if (obd) return Obd.request(identifier, echoId);
        return new CanFrame(echoId, requestId, 0, 0,
                new byte[]{3, 0x22, (byte) (identifier >>> 8), (byte) identifier, 0, 0, 0, 0});
    }

    public boolean matches(CanFrame frame) {
        byte[] data = frame.data;
        if (frame.echoId != CanFrame.RECEIVED || frame.canId != responseId || data.length < 4) return false;
        int length = data[0] & 255;
        if (length < 3 || length > 7 || length + 1 > data.length) return false;
        if (negative(frame)) return true;
        return obd ? (data[1] & 255) == 0x41 && (data[2] & 255) == identifier
                : (data[1] & 255) == 0x62 && (((data[2] & 255) << 8) | (data[3] & 255)) == identifier;
    }

    public boolean negative(CanFrame frame) {
        byte[] data = frame.data;
        return data.length >= 4 && data[0] == 3 && (data[1] & 255) == 0x7F
                && (data[2] & 255) == (obd ? 1 : 0x22);
    }

    public Double decode(CanFrame frame) {
        if (!matches(frame) || negative(frame)) return null;
        if (obd) return Obd.decode(frame, identifier);
        int payloadBits = ((frame.data[0] & 255) - 3) * 8;
        if (bitIndex + bitLength > payloadBits) return null;
        long raw = 0;
        for (int bit = 0; bit < bitLength; bit++) {
            int index = bitIndex + bit;
            raw = (raw << 1) | ((frame.data[4 + index / 8] >>> (7 - index % 8)) & 1);
        }
        if (signed && (raw & (1L << (bitLength - 1))) != 0) raw -= 1L << bitLength;
        double value = raw * multiplier / divisor + offset;
        if ((nullMin != null && value <= nullMin) || (nullMax != null && value >= nullMax)) return null;
        return value;
    }
}