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
    private Double minimum;
    private Double maximum;
    private java.util.Map<Long, String> mapping = java.util.Collections.emptyMap();

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
        if (obd) {
            if (identifier < 0 || identifier > 255) throw new IllegalArgumentException("Invalid OBD PID");
            return new CanFrame(echoId, requestId, 0, 0,
                    new byte[]{2, 1, (byte) identifier, 0, 0, 0, 0, 0});
        }
        return new CanFrame(echoId, requestId, 0, 0,
                new byte[]{3, 0x22, (byte) (identifier >>> 8), (byte) identifier, 0, 0, 0, 0});
    }

    public boolean matches(CanFrame frame) {
        return matches(DiagnosticReply.fromFrame(frame));
    }

    public boolean matches(DiagnosticReply reply) {
        if (reply == null || reply.canId != responseId) return false;
        byte[] data = reply.payload;
        if (negative(reply)) return true;
        return obd ? data.length >= 2 && (data[0] & 255) == 0x41 && (data[1] & 255) == identifier
                : data.length >= 3 && (data[0] & 255) == 0x62 && (((data[1] & 255) << 8) | (data[2] & 255)) == identifier;
    }

    public boolean negative(CanFrame frame) {
        return negative(DiagnosticReply.fromFrame(frame));
    }

    public boolean negative(DiagnosticReply reply) {
        return reply != null && reply.canId == responseId && reply.payload.length == 3
                && (reply.payload[0] & 255) == 0x7F && (reply.payload[1] & 255) == (obd ? 1 : 0x22);
    }

    public Double decode(CanFrame frame) {
        return decode(DiagnosticReply.fromFrame(frame));
    }

    public DiagnosticSignal withFormat(Double min, Double max, java.util.Map<Long, String> map) {
        minimum = min;
        maximum = max;
        mapping = new java.util.HashMap<>(map);
        return this;
    }

    private Long rawValue(DiagnosticReply reply) {
        if (!matches(reply) || negative(reply) || bitLength < 1 || bitLength > 32 || bitIndex < 0) return null;
        int header = obd ? 2 : 3;
        if (bitIndex + bitLength > (reply.payload.length - header) * 8) return null;
        long raw = 0;
        for (int bit = 0; bit < bitLength; bit++) {
            int index = bitIndex + bit;
            raw = (raw << 1) | ((reply.payload[header + index / 8] >>> (7 - index % 8)) & 1);
        }
        if (signed && (raw & (1L << (bitLength - 1))) != 0) raw -= 1L << bitLength;
        return raw;
    }

    public Double decode(DiagnosticReply reply) {
        Long raw = rawValue(reply);
        if (raw == null || divisor == 0) return null;
        double value = raw * multiplier / divisor + offset;
        if ((nullMin != null && value <= nullMin) || (nullMax != null && value >= nullMax)) return null;
        if (minimum != null) value = Math.max(minimum, value);
        if (maximum != null) value = Math.min(maximum, value);
        return value;
    }

    public String describe(DiagnosticReply reply) {
        Long raw = rawValue(reply);
        Double value = decode(reply);
        if (raw == null || value == null) return "Unavailable / invalid payload";
        if (!mapping.isEmpty()) return mapping.getOrDefault(raw, "Unknown") + " (" + raw + ")";
        if ("hex".equals(unit)) return String.format(java.util.Locale.US, "%0" + ((bitLength + 3) / 4) + "X", raw);
        if ("offon".equals(unit)) return (raw == 0 ? "Off" : "On") + " (" + raw + ")";
        if ("noyes".equals(unit)) return (raw == 0 ? "No" : "Yes") + " (" + raw + ")";
        if ("yesno".equals(unit)) return (raw == 0 ? "Yes" : "No") + " (" + raw + ")";
        return String.format(java.util.Locale.US, "%.3f %s", value, unit);
    }
}