package com.mx5.dashboard;

public final class TransmissionFilter {
    private TransmissionFilter() { }

    public static boolean allows(boolean manual, int requestId, int did) {
        if (!manual || requestId != 0x7E1) return true;
        return did != 0x17B3 && did != 0x1E1C && did != 0x1E24 && did != 0x1EC7 && did != 0x1EE4;
    }
}