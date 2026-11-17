package com.mx5.dashboard;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
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
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.Lifecycle;
import androidx.core.graphics.drawable.IconCompat;

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
                return new DashboardScreen(getCarContext(), DashboardTelemetry.Page.MAIN);
            }
        };
    }

    private static final class DashboardScreen extends Screen {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AutoDashboardRenderer renderer;
        private final DashboardTelemetry.Page page;
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
                logSurfaceArea("surface");
                drawDashboard();
            }

            @Override public void onStableAreaChanged(@NonNull Rect area) {
                stableArea.set(area);
                logSurfaceArea("stable");
                drawDashboard();
            }

            @Override public void onVisibleAreaChanged(@NonNull Rect area) {
                visibleArea.set(area);
                logSurfaceArea("visible");
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

        DashboardScreen(CarContext context, DashboardTelemetry.Page page) {
            super(context);
            this.page = page;
            renderer = new AutoDashboardRenderer(context);
            edgeView = new RpmEdgeView(context);
            getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver) (source, event) -> {
                if (event == Lifecycle.Event.ON_RESUME) {
                    DashboardTelemetry.showAutoPage(page, true);
                    context.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
                    handler.removeCallbacks(refresh);
                    handler.post(refresh);
                } else if (event == Lifecycle.Event.ON_PAUSE) {
                    DashboardTelemetry.showAutoPage(page, false);
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
            ActionStrip.Builder actions = new ActionStrip.Builder();
            if (page == DashboardTelemetry.Page.MAIN) {
            actions.addAction(new Action.Builder()
                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_menu))
                    .build())
                .setOnClickListener(() -> getScreenManager().push(
                    new DashboardScreen(getCarContext(), DashboardTelemetry.Page.DETAILS)))
                .build());
            } else {
                actions.addAction(Action.BACK);
            }
            return new NavigationTemplate.Builder()
                .setActionStrip(actions.build())
                    .build();
        }

        private void logSurfaceArea(String event) {
            android.util.Log.i("AutoDashboard", event + " page=" + page + " surface=" + surfaceWidth + "x"
                + surfaceHeight + " stable=" + stableArea + " visible=" + visibleArea + " drawing=" + drawingArea());
        }

        private Rect drawingArea() {
            Rect area = new Rect(0, 0, surfaceWidth, surfaceHeight);
            Rect hostArea = !visibleArea.isEmpty() ? visibleArea : stableArea;
            if (!hostArea.isEmpty() && !area.intersect(hostArea)) {
                area.setEmpty();
                return area;
            }
            area.top = 0;
            return area;
        }

        private void drawDashboard() {
            if (surface == null || !surface.isValid() || surfaceWidth <= 0 || surfaceHeight <= 0) return;
            DashboardTelemetry.Snapshot snapshot = DashboardTelemetry.snapshot(SystemClock.elapsedRealtime());
            NavigationState.Snapshot navigation = NavigationState.snapshot(SystemClock.elapsedRealtime());
            Canvas canvas = null;
            Surface drawingSurface = surface;
            try {
                canvas = drawingSurface.lockHardwareCanvas();
                if (canvas == null) return;
                canvas.drawColor(Color.rgb(13, 19, 20));
                Rect area = drawingArea();
                if (area.isEmpty()) return;
                renderer.draw(canvas, new RectF(area), snapshot, navigation, page);
                if (page == DashboardTelemetry.Page.MAIN) {
                    edgeView.layout(0, 0, surfaceWidth, surfaceHeight);
                    edgeView.setProgress(RpmAlert.progress(snapshot.rpm == null ? 0 : snapshot.rpm,
                        snapshot.threshold, snapshot.rpm != null));
                    edgeView.draw(canvas);
                }
            } catch (IllegalArgumentException | IllegalStateException error) {
                android.util.Log.w("AutoDashboard", "Unable to draw car surface", error);
            } finally {
                if (canvas != null) {
                    try {
                        drawingSurface.unlockCanvasAndPost(canvas);
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        android.util.Log.w("AutoDashboard", "Unable to post car surface", error);
                    }
                }
            }
        }
    }
}