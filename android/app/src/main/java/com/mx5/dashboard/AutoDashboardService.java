package com.mx5.dashboard;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.Lifecycle;
import java.util.Locale;

public final class AutoDashboardService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        return new HostValidator.Builder(getApplicationContext()).build();
    }

    @NonNull @Override public Session onCreateSession() {
        return new Session() {
            @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
                return new DashboardScreen(getCarContext());
            }
        };
    }

    private static final class DashboardScreen extends Screen {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final NavigationTileRenderer navigationRenderer = new NavigationTileRenderer();
        private final Rect stableArea = new Rect();
        private final Rect visibleArea = new Rect();
        private final RpmEdgeView edgeView;
        private Surface surface;
        private int surfaceWidth;
        private int surfaceHeight;
        private final Runnable refresh = new Runnable() {
            @Override public void run() {
                drawDashboard();
                handler.postDelayed(this, 100);
            }
        };
        private final SurfaceCallback surfaceCallback = new SurfaceCallback() {
            @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
                Surface next = container.getSurface();
                if (surface != null && surface != next) surface.release();
                surface = next;
                surfaceWidth = container.getWidth();
                surfaceHeight = container.getHeight();
                stableArea.setEmpty();
                visibleArea.setEmpty();
                drawDashboard();
            }

            @Override public void onStableAreaChanged(@NonNull Rect area) {
                stableArea.set(area);
                drawDashboard();
            }

            @Override public void onVisibleAreaChanged(@NonNull Rect area) {
                visibleArea.set(area);
                drawDashboard();
            }

            @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
                Surface destroyed = container.getSurface();
                if (surface != null && (destroyed == null || surface == destroyed || surface.equals(destroyed))) {
                    surface.release();
                    if (surface == destroyed) destroyed = null;
                    surface = null;
                }
                if (destroyed != null) destroyed.release();
            }
        };

        DashboardScreen(CarContext context) {
            super(context);
            edgeView = new RpmEdgeView(context);
            getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver) (source, event) -> {
                if (event == Lifecycle.Event.ON_START) {
                    context.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
                    handler.removeCallbacks(refresh);
                    handler.post(refresh);
                } else if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    handler.removeCallbacks(refresh);
                    context.getCarService(AppManager.class).setSurfaceCallback(null);
                    if (surface != null) {
                        surface.release();
                        surface = null;
                    }
                }
            });
        }

        @NonNull @Override public Template onGetTemplate() {
            return new NavigationTemplate.Builder()
                    .setActionStrip(new ActionStrip.Builder().addAction(Action.BACK).build())
                    .build();
        }

        private void drawDashboard() {
            if (surface == null || !surface.isValid() || surfaceWidth <= 0 || surfaceHeight <= 0) return;
            DashboardTelemetry.Snapshot snapshot = DashboardTelemetry.snapshot(SystemClock.elapsedRealtime());
            NavigationState.Snapshot navigation = NavigationState.snapshot(SystemClock.elapsedRealtime());
            Canvas canvas = null;
            try {
                canvas = surface.lockCanvas(null);
                if (canvas == null) return;
                canvas.drawColor(Color.rgb(13, 19, 20));
                Rect area = new Rect(0, 0, surfaceWidth, surfaceHeight);
                if (!stableArea.isEmpty() && !area.intersect(stableArea)) return;
                if (!visibleArea.isEmpty() && !area.intersect(visibleArea)) return;
                float padding = Math.max(8, Math.min(area.width(), area.height()) * 0.045f);
                float gap = padding;
                float tileWidth = Math.max(0, (area.width() - 2 * padding - gap) / 2f);
                float top = area.top + padding;
                float bottom = area.bottom - padding;
                if (tileWidth <= 0 || bottom <= top) return;
                navigationRenderer.draw(canvas, new RectF(area.left + padding, top,
                        area.left + padding + tileWidth, bottom), navigation);
                RectF engineTile = new RectF(area.left + padding + tileWidth + gap, top,
                        area.left + padding + 2 * tileWidth + gap, bottom);
                drawTile(canvas, engineTile, "ENGINE", snapshot.rpm, "rpm", snapshot.rpmSimulated);
                drawSpeed(canvas, engineTile, snapshot);
                edgeView.layout(0, 0, surfaceWidth, surfaceHeight);
                edgeView.setProgress(RpmAlert.progress(snapshot.rpm == null ? 0 : snapshot.rpm,
                        snapshot.threshold, snapshot.rpm != null));
                edgeView.draw(canvas);
            } catch (IllegalArgumentException | IllegalStateException error) {
                android.util.Log.w("AutoDashboard", "Unable to draw car surface", error);
            } finally {
                if (canvas != null) surface.unlockCanvasAndPost(canvas);
            }
        }

        private void drawTile(Canvas canvas, RectF tile, String label, Double value, String unit, boolean simulated) {
            float height = tile.height();
            paint.setColor(Color.rgb(29, 39, 39));
            canvas.drawRoundRect(tile, 8, 8, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setColor(Color.rgb(163, 183, 179));
            paint.setTextSize(Math.max(10, Math.min(height * 0.09f, tile.width() * 0.1f)));
            canvas.drawText(label, tile.centerX(), tile.top + height * 0.20f, paint);
            String display = value == null ? "--" : String.format(Locale.US, "%.0f", value);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.min(height * 0.38f, tile.width() * 0.28f));
            if (paint.measureText(display) > tile.width() * 0.8f) {
                paint.setTextSize(paint.getTextSize() * tile.width() * 0.8f / paint.measureText(display));
            }
            canvas.drawText(display, tile.centerX(), tile.top + height * 0.62f, paint);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(Math.max(10, Math.min(height * 0.08f, tile.width() * 0.08f)));
            paint.setColor(Color.rgb(163, 183, 179));
            canvas.drawText(value == null ? unit : unit + (simulated ? "  /  SIM" : "  /  CAN"),
                    tile.centerX(), tile.top + height * 0.76f, paint);
        }

        private void drawSpeed(Canvas canvas, RectF tile, DashboardTelemetry.Snapshot telemetry) {
            Double speed = telemetry.speed;
            String source = telemetry.speedSimulated ? "SIM" : "CAN";
            String display = speed == null ? "-- km/h" : String.format(Locale.US, "%.0f km/h / %s", speed, source);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.min(tile.height() * 0.095f, tile.width() * 0.075f));
            float width = paint.measureText(display);
            if (width > tile.width() * 0.75f) paint.setTextSize(paint.getTextSize() * tile.width() * 0.75f / width);
            canvas.drawText(display, tile.right - tile.width() * 0.06f, tile.bottom - tile.height() * 0.06f, paint);
        }
    }
}