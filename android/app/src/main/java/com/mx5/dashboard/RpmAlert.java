package com.mx5.dashboard;

public final class RpmAlert {
    private RpmAlert() { }

    public static float progress(double rpm, int threshold, boolean fresh) {
        if (!fresh || threshold <= 0 || !Double.isFinite(rpm)) return 0;
        return (float) Math.max(0, Math.min(1, (rpm / threshold - 0.75) / 0.25));
    }
}