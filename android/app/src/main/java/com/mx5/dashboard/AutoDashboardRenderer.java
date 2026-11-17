package com.mx5.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import java.util.Locale;

public final class AutoDashboardRenderer {
    private static final int PANEL = Color.rgb(29, 39, 39);
    private static final int MUTED = Color.rgb(163, 183, 179);
    private static final int ACCENT = Color.rgb(202, 226, 151);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final NavigationTileRenderer navigationRenderer = new NavigationTileRenderer();
    private final Drawable engine;
    private final Drawable fuel;
        private final Context context;
        private CarPaint carPaint;
        private Drawable car;

    public AutoDashboardRenderer(Context context) {
                this.context = context;
        engine = context.getDrawable(R.drawable.ic_engine).mutate();
        fuel = context.getDrawable(R.drawable.ic_fuel).mutate();
                carPaint = CarPaint.load(context);
                car = carPaint.drawable(context);
    }

    public void draw(Canvas canvas, RectF area, DashboardTelemetry.Snapshot telemetry,
            NavigationState.Snapshot navigation, DashboardTelemetry.Page page) {
        float padding = Math.max(6, Math.min(area.width(), area.height()) * 0.035f);
        RectF inner = new RectF(area);
        inner.inset(padding, padding);
        if (inner.width() <= 0 || inner.height() <= 0) return;
        if (page == DashboardTelemetry.Page.MAIN) {
            float headerHeight = Math.min(76, inner.height() * 0.22f);
            float miniWidth = (inner.width() - 2 * padding) / 3;
            mini(canvas, new RectF(inner.left, inner.top, inner.left + miniWidth, inner.top + headerHeight),
                    "OIL", telemetry.reading("MX5_EOT"), "C", engine, false);
            mini(canvas, new RectF(inner.left + miniWidth + padding, inner.top,
                    inner.left + 2 * miniWidth + padding, inner.top + headerHeight),
                    "FUEL", telemetry.reading("MX5_FLV"), "L", fuel, false);
            mini(canvas, new RectF(inner.left + 2 * (miniWidth + padding), inner.top, inner.right,
                    inner.top + headerHeight), "GEAR", telemetry.reading("GEAR_ACT"), "", null, true);
            inner.top += headerHeight + padding;
        }
        float half = (inner.width() - padding) / 2;
        RectF left = new RectF(inner.left, inner.top, inner.left + half, inner.bottom);
        RectF right = new RectF(left.right + padding, inner.top, inner.right, inner.bottom);
        if (page == DashboardTelemetry.Page.DETAILS) {
            wheels(canvas, left, telemetry);
            details(canvas, right, telemetry);
        } else {
            navigationRenderer.draw(canvas, left, navigation);
            engine(canvas, right, telemetry);
        }
    }

    private void mini(Canvas canvas, RectF tile, String label, DashboardTelemetry.Reading reading,
            String unit, Drawable icon, boolean gear) {
        panel(canvas, tile);
        if (icon != null) {
            int size = (int) (tile.height() * 0.90f);
            icon.setBounds((int) (tile.centerX() - size / 2f), (int) (tile.centerY() - size / 2f),
                    (int) (tile.centerX() + size / 2f), (int) (tile.centerY() + size / 2f));
            icon.setAlpha(35);
            icon.draw(canvas);
        }
        String value = reading == null ? "--" : gear && reading.value == 0 ? "N"
                : String.format(Locale.US, "%.0f", reading.value);
        text(canvas, tile, label, tile.top + tile.height() * 0.27f, tile.height() * 0.19f, MUTED);
        text(canvas, tile, value + (unit.isEmpty() ? "" : " " + unit),
                tile.top + tile.height() * 0.73f, tile.height() * 0.38f, Color.WHITE);
        if (reading != null && reading.simulated) {
            text(canvas, tile, "SIM", tile.bottom - tile.height() * 0.04f, tile.height() * 0.14f, ACCENT);
        }
    }

    private void engine(Canvas canvas, RectF tile, DashboardTelemetry.Snapshot telemetry) {
        panel(canvas, tile);
        text(canvas, tile, "ENGINE", tile.top + tile.height() * 0.16f, tile.height() * 0.075f, MUTED);
        text(canvas, tile, telemetry.rpm == null ? "--" : String.format(Locale.US, "%.0f", telemetry.rpm),
                tile.top + tile.height() * 0.56f, tile.height() * 0.34f, Color.WHITE);
        text(canvas, tile, "rpm" + (telemetry.rpm == null ? "" : telemetry.rpmSimulated ? " / SIM" : " / CAN"),
                tile.top + tile.height() * 0.73f, tile.height() * 0.07f, MUTED);
        RectF speed = new RectF(tile.centerX(), tile.top + tile.height() * 0.78f, tile.right, tile.bottom);
        text(canvas, speed, (telemetry.speed == null ? "--" : String.format(Locale.US, "%.0f", telemetry.speed)) + " km/h"
                        + (telemetry.speed != null && telemetry.speedSimulated ? " / SIM" : ""),
                tile.bottom - tile.height() * 0.07f, tile.height() * 0.09f, Color.WHITE);
    }

    private void wheels(Canvas canvas, RectF tile, DashboardTelemetry.Snapshot telemetry) {
                CarPaint selected = CarPaint.load(context);
                if (selected != carPaint) {
                        carPaint = selected;
                        car = selected.drawable(context);
                }
        panel(canvas, tile);
        text(canvas, tile, "TYRES", tile.top + tile.height() * 0.12f, tile.height() * 0.065f, MUTED);
        float carHeight = tile.height() * 0.66f;
        float carWidth = Math.min(carHeight * 120 / 260, tile.width() * 0.32f);
        carHeight = carWidth * 260 / 120;
        float carTop = tile.top + (tile.height() - carHeight) / 2;
        car.setBounds((int) (tile.centerX() - carWidth / 2), (int) carTop,
                (int) (tile.centerX() + carWidth / 2), (int) (carTop + carHeight));
        car.draw(canvas);
        float labelWidth = (tile.width() - carWidth) / 2 - tile.width() * 0.04f;
        float labelHeight = tile.height() * 0.25f;
        wheel(canvas, new RectF(tile.left + 4, tile.top + tile.height() * 0.22f,
                tile.left + labelWidth, tile.top + tile.height() * 0.22f + labelHeight), "FL", telemetry);
        wheel(canvas, new RectF(tile.right - labelWidth, tile.top + tile.height() * 0.22f,
                tile.right - 4, tile.top + tile.height() * 0.22f + labelHeight), "FR", telemetry);
        wheel(canvas, new RectF(tile.left + 4, tile.top + tile.height() * 0.59f,
                tile.left + labelWidth, tile.top + tile.height() * 0.59f + labelHeight), "RL", telemetry);
        wheel(canvas, new RectF(tile.right - labelWidth, tile.top + tile.height() * 0.59f,
                tile.right - 4, tile.top + tile.height() * 0.59f + labelHeight), "RR", telemetry);
    }

    private void wheel(Canvas canvas, RectF area, String position, DashboardTelemetry.Snapshot telemetry) {
        DashboardTelemetry.Reading pressure = telemetry.reading("MX5_TP_" + position);
        DashboardTelemetry.Reading temperature = telemetry.reading("MX5_TT_" + position);
        text(canvas, area, position, area.top + area.height() * 0.18f, area.height() * 0.18f, MUTED);
        text(canvas, area, formatted(pressure, pressure == null ? 1 : "psi".equals(pressure.unit) ? 0.0689476 : 1, 0, "bar"),
                area.top + area.height() * 0.53f, area.height() * 0.24f, Color.WHITE);
        boolean fahrenheit = temperature != null && "fahrenheit".equals(temperature.unit);
        text(canvas, area, formatted(temperature, fahrenheit ? 5.0 / 9 : 1,
                        fahrenheit ? -32 * 5.0 / 9 : 0, "C"),
                area.top + area.height() * 0.83f, area.height() * 0.22f, Color.WHITE);
        if ((pressure != null && pressure.simulated) || (temperature != null && temperature.simulated)) {
            text(canvas, area, "SIM", area.bottom, area.height() * 0.13f, ACCENT);
        }
    }

    private void details(Canvas canvas, RectF tile, DashboardTelemetry.Snapshot telemetry) {
        panel(canvas, tile);
        text(canvas, tile, "VEHICLE", tile.top + tile.height() * 0.12f, tile.height() * 0.065f, MUTED);
        metric(canvas, tile, 0, "COOLANT", telemetry.reading("ECT"), 1, "C");
        metric(canvas, tile, 1, "VOLTAGE", telemetry.reading("VPWR"), 1, "V");
        metric(canvas, tile, 2, "INTAKE AIR", telemetry.reading("IAT"), 1, "C");
        metric(canvas, tile, 3, "OIL PRESSURE", telemetry.reading("MX5_EOP"), 0.01, "bar");
    }

    private void metric(Canvas canvas, RectF tile, int row, String label, DashboardTelemetry.Reading reading,
            double multiplier, String unit) {
        float top = tile.top + tile.height() * (0.20f + row * 0.19f);
        RectF area = new RectF(tile.left + tile.width() * 0.07f, top,
                tile.right - tile.width() * 0.07f, top + tile.height() * 0.16f);
        RectF name = new RectF(area.left, area.top, area.centerX(), area.bottom);
        RectF value = new RectF(area.centerX(), area.top, area.right, area.bottom);
        text(canvas, name, label, area.centerY(), tile.height() * 0.048f, MUTED);
        text(canvas, value, formatted(reading, multiplier, 0, unit), area.centerY(), tile.height() * 0.075f, Color.WHITE);
        if (reading != null && reading.simulated) {
            text(canvas, value, "SIM", area.bottom, tile.height() * 0.035f, ACCENT);
        }
    }

    private static String formatted(DashboardTelemetry.Reading reading, double multiplier, double offset, String unit) {
        return (reading == null ? "--" : String.format(Locale.US,
                "bar".equals(unit) || "V".equals(unit) ? "%.1f" : "%.0f", reading.value * multiplier + offset)) + " " + unit;
    }

    private void panel(Canvas canvas, RectF tile) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PANEL);
        canvas.drawRoundRect(tile, 8, 8, paint);
    }

    private void text(Canvas canvas, RectF area, String text, float baseline, float size, int color) {
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        float width = paint.measureText(text);
        if (width > area.width() * 0.92f) paint.setTextSize(size * area.width() * 0.92f / width);
        canvas.drawText(text, area.centerX(), baseline, paint);
    }
}