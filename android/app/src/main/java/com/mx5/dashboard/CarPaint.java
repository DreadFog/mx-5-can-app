package com.mx5.dashboard;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

public enum CarPaint {
    SOUL_RED_CRYSTAL("Soul Red Crystal Metallic", 0xFFB51F35),
    MACHINE_GRAY("Machine Gray Metallic", 0xFF62666A),
    AERO_GRAY("Aero Gray Metallic", 0xFFBCC5C8),
    DEEP_CRYSTAL_BLUE("Deep Crystal Blue Mica", 0xFF253B59),
    JET_BLACK("Jet Black Mica", 0xFF252629),
    SNOWFLAKE_WHITE("Snowflake White Pearl Mica", 0xFFF0F1ED),
    ZIRCON_SAND("Zircon Sand Metallic", 0xFFAAA58D);

    static final String PREFERENCES = "dashboard_preferences";
    static final String KEY = "car_paint";
    public final String label;
    public final int color;

    CarPaint(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public static CarPaint load(Context context) {
        String saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "");
        for (CarPaint paint : values()) {
            if (paint.name().equals(saved)) return paint;
        }
        return SOUL_RED_CRYSTAL;
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, name()).apply();
    }

    public Drawable drawable(Context context) {
        Drawable body = context.getDrawable(R.drawable.mx5_roadster_body).mutate();
        body.setTint(color);
        return new LayerDrawable(new Drawable[]{body, context.getDrawable(R.drawable.mx5_roadster_top).mutate()});
    }

    @Override public String toString() { return label; }
}