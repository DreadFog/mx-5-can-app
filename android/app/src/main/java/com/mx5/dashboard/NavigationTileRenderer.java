package com.mx5.dashboard;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

public final class NavigationTileRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void draw(Canvas canvas, RectF tile, NavigationState.Snapshot state) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(29, 39, 39));
        canvas.drawRoundRect(tile, 8, 8, paint);
        if (!state.hasCue()) {
            text(canvas, tile, statusLabel(state.status), tile.top + tile.height() * 0.43f, tile.height() * 0.12f, Color.WHITE);
            text(canvas, tile, state.status == NavigationState.Status.IDLE ? "Destination on phone" : state.message,
                    tile.top + tile.height() * 0.63f, tile.height() * 0.065f, Color.rgb(163, 183, 179));
            return;
        }
        text(canvas, tile, state.status == NavigationState.Status.ARRIVED ? "0 m" : state.distance + " m",
                tile.top + tile.height() * 0.19f, tile.height() * 0.14f, Color.WHITE);
        canvas.save();
        canvas.translate(tile.centerX(), tile.top + tile.height() * 0.51f);
        float size = Math.min(tile.width() * 0.40f, tile.height() * 0.29f);
        canvas.scale(size, size);
        drawSign(canvas, state.turn);
        canvas.restore();
        text(canvas, tile, label(state.turn, state.exit), tile.top + tile.height() * 0.90f,
                tile.height() * 0.095f, Color.rgb(202, 226, 151));
    }

    private void drawSign(Canvas canvas, NavigationRoute.Turn turn) {
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.13f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (turn == NavigationRoute.Turn.ARRIVE) {
            canvas.drawLine(-0.45f, 0.9f, -0.45f, -0.85f, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 4; column++) {
                    if ((row + column) % 2 == 0) canvas.drawRect(-0.4f + column * 0.25f, -0.85f + row * 0.25f,
                            -0.15f + column * 0.25f, -0.6f + row * 0.25f, paint);
                }
            }
        } else if (turn == NavigationRoute.Turn.ROUNDABOUT) {
            canvas.drawLine(0, 0.9f, 0, 0.55f, paint);
            canvas.drawArc(new RectF(-0.55f, -0.55f, 0.55f, 0.55f), 80, 285, false, paint);
            arrowHead(canvas, 0.55f, 0.05f, 0, 1);
        } else if (turn == NavigationRoute.Turn.U_TURN) {
            Path path = new Path();
            path.moveTo(-0.45f, 0.9f);
            path.lineTo(-0.45f, -0.35f);
            path.cubicTo(-0.45f, -1, 0.45f, -1, 0.45f, -0.35f);
            path.lineTo(0.45f, 0.4f);
            canvas.drawPath(path, paint);
            arrowHead(canvas, 0.45f, 0.4f, 0, 1);
        } else {
            boolean left = turn == NavigationRoute.Turn.LIGHT_LEFT || turn == NavigationRoute.Turn.MEDIUM_LEFT
                    || turn == NavigationRoute.Turn.SHARP_LEFT;
            boolean light = turn == NavigationRoute.Turn.LIGHT_LEFT || turn == NavigationRoute.Turn.LIGHT_RIGHT;
            boolean sharp = turn == NavigationRoute.Turn.SHARP_LEFT || turn == NavigationRoute.Turn.SHARP_RIGHT;
            float direction = left ? -1 : 1;
            float endX = turn == NavigationRoute.Turn.STRAIGHT ? 0 : direction * 0.85f;
            float endY = turn == NavigationRoute.Turn.STRAIGHT || light ? -0.9f : sharp ? 0.25f : -0.35f;
            Path path = new Path();
            path.moveTo(0, 0.9f);
            path.lineTo(0, -0.35f);
            path.lineTo(endX, endY);
            canvas.drawPath(path, paint);
            float deltaX = endX;
            float deltaY = endY + 0.35f;
            float length = (float) Math.hypot(deltaX, deltaY);
            arrowHead(canvas, endX, endY, deltaX / length, deltaY / length);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void arrowHead(Canvas canvas, float tipX, float tipY, float directionX, float directionY) {
        Path head = new Path();
        head.moveTo(tipX - directionX * 0.32f - directionY * 0.26f,
                tipY - directionY * 0.32f + directionX * 0.26f);
        head.lineTo(tipX, tipY);
        head.lineTo(tipX - directionX * 0.32f + directionY * 0.26f,
                tipY - directionY * 0.32f - directionX * 0.26f);
        canvas.drawPath(head, paint);
    }

    private void text(Canvas canvas, RectF tile, String value, float baseline, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(color);
        paint.setTextSize(size);
        float width = paint.measureText(value);
        if (width > tile.width() * 0.86f) paint.setTextSize(size * tile.width() * 0.86f / width);
        canvas.drawText(value, tile.centerX(), baseline, paint);
    }

    public static String label(NavigationRoute.Turn turn, int exit) {
        switch (turn) {
            case LIGHT_LEFT: return "LIGHT LEFT";
            case MEDIUM_LEFT: return "MEDIUM LEFT";
            case SHARP_LEFT: return "SHARP LEFT";
            case LIGHT_RIGHT: return "LIGHT RIGHT";
            case MEDIUM_RIGHT: return "MEDIUM RIGHT";
            case SHARP_RIGHT: return "SHARP RIGHT";
            case ROUNDABOUT: return exit > 0 ? "ROUNDABOUT / EXIT " + exit : "ROUNDABOUT";
            case U_TURN: return "U-TURN";
            case ARRIVE: return "DESTINATION";
            default: return "STRAIGHT";
        }
    }

    private static String statusLabel(NavigationState.Status status) {
        switch (status) {
            case WAITING_GPS: case GPS_LOST: return "WAITING FOR GPS";
            case ROUTING: return "CALCULATING";
            case OFF_ROUTE: return "OFF ROUTE";
            case ERROR: return "ROUTE UNAVAILABLE";
            default: return "NO ROUTE";
        }
    }
}