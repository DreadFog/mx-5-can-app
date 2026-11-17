package com.mx5.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.car.app.CarContext;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.Screen;
import androidx.car.app.ScreenManager;
import androidx.car.app.model.Action;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.lifecycle.Lifecycle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutoDashboardRendererTest {
    @After public void clearTelemetry() {
        DashboardTelemetry.clear("test complete");
        DashboardTelemetry.showAutoPage(DashboardTelemetry.Page.MAIN, false);
    }

    @Test public void roundaboutArcIsCounterclockwise() {
        RecordingCanvas canvas = new RecordingCanvas(600, 400);
        new NavigationTileRenderer().draw(canvas, new RectF(0, 0, 600, 400), navigation());
        assertEquals(-285, canvas.arcSweep, 0.01);
    }

    @Test public void frontTyresStaySolidWhereMirrorPathsOverlap() {
        android.graphics.drawable.Drawable car = RuntimeEnvironment.getApplication().getDrawable(R.drawable.mx5_roadster_top);
        Bitmap bitmap = Bitmap.createBitmap(120, 260, Bitmap.Config.ARGB_8888);
        car.setBounds(0, 0, 120, 260);
        car.draw(new Canvas(bitmap));
        assertEquals(0xFF182423, bitmap.getPixel(19, 78));
        assertEquals(0xFF182423, bitmap.getPixel(101, 78));
    }

    @Test public void bodyPaintDefaultsToRedAndPreservesTyresGlassAndLights() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences preferences = context.getSharedPreferences(CarPaint.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().remove(CarPaint.KEY).commit();
        try {
            assertEquals(CarPaint.SOUL_RED_CRYSTAL, CarPaint.load(context));
            for (CarPaint paint : CarPaint.values()) {
                android.graphics.drawable.Drawable car = paint.drawable(context);
                Bitmap bitmap = Bitmap.createBitmap(120, 260, Bitmap.Config.ARGB_8888);
                car.setBounds(0, 0, 120, 260);
                car.draw(new Canvas(bitmap));
                assertEquals(paint.label, paint.color, bitmap.getPixel(60, 50));
                assertEquals(0xFF182423, bitmap.getPixel(19, 78));
                assertEquals(0xFF182423, bitmap.getPixel(101, 78));
                assertEquals(0xFF273A39, bitmap.getPixel(60, 110));
                assertEquals(0xFFDFF1DF, bitmap.getPixel(35, 34));
            }
            preferences.edit().putString(CarPaint.KEY, "unknown-paint").commit();
            assertEquals(CarPaint.SOUL_RED_CRYSTAL, CarPaint.load(context));
        } finally {
            preferences.edit().remove(CarPaint.KEY).commit();
        }
    }

    @Test public void existingRendererReloadsSavedPaintWithoutRestart() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences preferences = context.getSharedPreferences(CarPaint.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().remove(CarPaint.KEY).commit();
        try {
            AutoDashboardRenderer renderer = new AutoDashboardRenderer(context);
            for (CarPaint paint : new CarPaint[]{CarPaint.SOUL_RED_CRYSTAL, CarPaint.DEEP_CRYSTAL_BLUE}) {
                paint.save(context);
                renderer.draw(new Canvas(Bitmap.createBitmap(1103, 444, Bitmap.Config.ARGB_8888)),
                        new RectF(0, 0, 1103, 444), DashboardTelemetry.snapshot(100), navigation(), DashboardTelemetry.Page.DETAILS);
                android.graphics.drawable.Drawable car = ReflectionHelpers.getField(renderer, "car");
                Bitmap bitmap = Bitmap.createBitmap(120, 260, Bitmap.Config.ARGB_8888);
                car.setBounds(0, 0, 120, 260);
                car.draw(new Canvas(bitmap));
                assertEquals(paint.color, bitmap.getPixel(60, 50));
            }
        } finally {
            preferences.edit().remove(CarPaint.KEY).commit();
        }
    }

    @Test public void bothPagesRenderWithoutOverlappingTextAtHeadUnitSizes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        List<DiagnosticSignal> signals = catalogue(context, 2024);
        DashboardTelemetry.configureSignals(signals);
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8, new byte[]{0x62, 0x13, 0x10, 0x33, 0x2C}), 100, true);
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x728, new byte[]{0x62, 0x61, (byte) 0xB1, 45}), 100, true);
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8, new byte[]{0x41, (byte) 0xA4, 1, 0x30, 0, 0}), 100, true);
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8, new byte[]{0x41, 5, 120}), 100, true);
        DashboardTelemetry.accept(new CanFrame(CanFrame.RECEIVED, 0x7E8, 0, 0,
                new byte[]{4, 0x41, 12, 0x7D, 0, 0, 0, 0}), 100, true);
        int[][] sizes = {{640, 240}, {1103, 444}, {1190, 460}, {1280, 720}};
        for (int[] size : sizes) {
            for (DashboardTelemetry.Page page : DashboardTelemetry.Page.values()) {
                RecordingCanvas canvas = new RecordingCanvas(size[0], size[1]);
                new AutoDashboardRenderer(context).draw(canvas, new RectF(0, 0, size[0], size[1]),
                        DashboardTelemetry.snapshot(100), navigation(), page);
                assertFalse(canvas.textBounds.isEmpty());
                for (RectF bounds : canvas.textBounds) {
                    assertTrue("Text outside display: " + bounds, bounds.left >= 0 && bounds.top >= 0
                            && bounds.right <= size[0] && bounds.bottom <= size[1]);
                    for (RectF other : canvas.textBounds) {
                        if (other != bounds) assertFalse("Overlapping text: " + bounds + " / " + other,
                                RectF.intersects(bounds, other));
                    }
                }
                assertNotEquals(0, canvas.bitmap.getPixel(size[0] / 4, size[1] / 2));
            }
        }
    }

    @Test public void actualGearRequiresItsSupportBitAndOldTyreDidsStayExcluded() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        List<DiagnosticSignal> signals = catalogue(context, 2024);
        DashboardTelemetry.configureSignals(signals);
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8,
                new byte[]{0x41, (byte) 0xA4, 1, 0x30, 0, 0}), 100, false);
        assertEquals(3, DashboardTelemetry.snapshot(100).reading("GEAR_ACT").value, 0);
        DashboardTelemetry.acceptReply(new DiagnosticReply(0x7E8,
                new byte[]{0x41, (byte) 0xA4, 0, 0x30, 0, 0}), 200, false);
        assertNull(DashboardTelemetry.snapshot(200).reading("GEAR_ACT"));
        assertFalse(signals.stream().anyMatch(signal -> java.util.Arrays.asList(
            "MX5_TP_FL", "MX5_TP_FR", "MX5_TP_RL", "MX5_TP_RR").contains(signal.id)));
        assertTrue(catalogue(context, 2015).stream().anyMatch(signal -> signal.id.equals("MX5_TP_FL")));
    }

    @Test public void nativeMenuPushesDetailsAndLifecycleSelectsPollingPage() throws Exception {
        TestCarContext context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        Class<? extends Screen> screenClass = Class.forName(AutoDashboardService.class.getName() + "$DashboardScreen")
                .asSubclass(Screen.class);
        Screen main = ReflectionHelpers.callConstructor(screenClass,
                ReflectionHelpers.ClassParameter.from(CarContext.class, context),
                ReflectionHelpers.ClassParameter.from(DashboardTelemetry.Page.class, DashboardTelemetry.Page.MAIN));
        ScreenController mainController = new ScreenController(main).moveToState(Lifecycle.State.RESUMED);
        try {
            NavigationTemplate template = (NavigationTemplate) main.onGetTemplate();
            assertEquals(1, template.getActionStrip().getActions().size());
            Action menu = template.getActionStrip().getActions().get(0);
            assertNotNull(menu.getIcon());
            assertNull(menu.getTitle());
            menu.getOnClickDelegate().sendClick(new OnDoneCallback() {
                @Override public void onSuccess(Bundleable result) { }
                @Override public void onFailure(Bundleable failure) { fail("Menu action failed"); }
            });
            Screen details = context.getCarService(ScreenManager.class).getTop();
            assertNotSame(main, details);
            mainController.moveToState(Lifecycle.State.STARTED);
            ScreenController detailsController = new ScreenController(details).moveToState(Lifecycle.State.RESUMED);
            assertEquals(DashboardTelemetry.Page.DETAILS, DashboardTelemetry.page());
            assertTrue(DashboardTelemetry.autoVisible());
            assertEquals(1, ((NavigationTemplate) details.onGetTemplate()).getActionStrip().getActions().size());
            assertEquals(Action.BACK, ((NavigationTemplate) details.onGetTemplate()).getActionStrip().getActions().get(0));
            detailsController.moveToState(Lifecycle.State.DESTROYED);
            mainController.moveToState(Lifecycle.State.RESUMED);
            assertEquals(DashboardTelemetry.Page.MAIN, DashboardTelemetry.page());
        } finally {
            mainController.moveToState(Lifecycle.State.DESTROYED);
        }
        assertFalse(DashboardTelemetry.autoVisible());
    }

    @Test public void layoutReclaimsTopInsetEvenWhenHostVisibilityIsStale() throws Exception {
        TestCarContext context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        Class<? extends Screen> screenClass = Class.forName(AutoDashboardService.class.getName() + "$DashboardScreen")
                .asSubclass(Screen.class);
        Screen screen = ReflectionHelpers.callConstructor(screenClass,
                ReflectionHelpers.ClassParameter.from(CarContext.class, context),
                ReflectionHelpers.ClassParameter.from(DashboardTelemetry.Page.class, DashboardTelemetry.Page.MAIN));
        ReflectionHelpers.setField(screen, "surfaceWidth", 1103);
        ReflectionHelpers.setField(screen, "surfaceHeight", 444);
        androidx.car.app.SurfaceCallback callback = ReflectionHelpers.getField(screen, "surfaceCallback");
        assertEquals(new Rect(0, 0, 1103, 444), ReflectionHelpers.callInstanceMethod(screen, "drawingArea"));
        callback.onStableAreaChanged(new Rect(0, 96, 1103, 444));
        assertEquals(new Rect(0, 0, 1103, 444), ReflectionHelpers.callInstanceMethod(screen, "drawingArea"));
        Rect[] visibleAreas = {new Rect(0, 96, 1103, 444), new Rect(0, 0, 1103, 444),
                new Rect(40, 64, 1063, 420), new Rect(0, 96, 1103, 444)};
        for (Rect visible : visibleAreas) {
            callback.onVisibleAreaChanged(visible);
            Rect area = ReflectionHelpers.callInstanceMethod(screen, "drawingArea");
            assertEquals(new Rect(visible.left, 0, visible.right, visible.bottom), area);
            for (DashboardTelemetry.Page page : DashboardTelemetry.Page.values()) {
                RecordingCanvas canvas = new RecordingCanvas(1103, 444);
                new AutoDashboardRenderer(context).draw(canvas, new RectF(area),
                        DashboardTelemetry.snapshot(100), navigation(), page);
                assertFalse(canvas.textBounds.isEmpty());
                for (RectF bounds : canvas.textBounds) {
                    assertTrue("Text outside drawing area: " + bounds, new RectF(area).contains(bounds));
                    for (RectF other : canvas.textBounds) {
                        if (other != bounds) assertFalse("Overlapping text after resizing",
                                RectF.intersects(bounds, other));
                    }
                }
            }
        }
        callback.onStableAreaChanged(new Rect(0, 120, 1103, 444));
        assertEquals(new Rect(0, 0, 1103, 444), ReflectionHelpers.callInstanceMethod(screen, "drawingArea"));
        callback.onVisibleAreaChanged(new Rect());
        assertEquals(new Rect(0, 0, 1103, 444), ReflectionHelpers.callInstanceMethod(screen, "drawingArea"));
        callback.onVisibleAreaChanged(new Rect(-10, -20, 1200, 500));
        assertEquals(new Rect(0, 0, 1103, 444), ReflectionHelpers.callInstanceMethod(screen, "drawingArea"));
        callback.onVisibleAreaChanged(new Rect(1200, 0, 1300, 444));
        assertTrue(((Rect) ReflectionHelpers.callInstanceMethod(screen, "drawingArea")).isEmpty());
    }

    @Test public void hardwareCanvasKeepsMiniTilesAtTopAcrossVisibilityCallbacks() throws Exception {
        TestCarContext context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        Class<? extends Screen> screenClass = Class.forName(AutoDashboardService.class.getName() + "$DashboardScreen")
                .asSubclass(Screen.class);
        Screen screen = ReflectionHelpers.callConstructor(screenClass,
                ReflectionHelpers.ClassParameter.from(CarContext.class, context),
                ReflectionHelpers.ClassParameter.from(DashboardTelemetry.Page.class, DashboardTelemetry.Page.MAIN));
        RecordingCanvas canvas = new RecordingCanvas(1103, 444);
        int[] postedFrames = {0};
        android.graphics.SurfaceTexture texture = new android.graphics.SurfaceTexture(0);
        android.view.Surface surface = new android.view.Surface(texture) {
            @Override public boolean isValid() { return true; }
            @Override public Canvas lockHardwareCanvas() { return canvas; }
            @Override public Canvas lockCanvas(Rect dirty) {
                throw new AssertionError("Projected surface must use the hardware canvas");
            }
            @Override public void unlockCanvasAndPost(Canvas posted) {
                assertSame(canvas, posted);
                postedFrames[0]++;
            }
        };
        try {
            ReflectionHelpers.setField(screen, "surface", surface);
            ReflectionHelpers.setField(screen, "surfaceWidth", 1103);
            ReflectionHelpers.setField(screen, "surfaceHeight", 444);
            androidx.car.app.SurfaceCallback callback = ReflectionHelpers.getField(screen, "surfaceCallback");
            callback.onVisibleAreaChanged(new Rect(12, 88, 1091, 432));
            float firstTextTop = canvas.textBounds.get(0).top;
            canvas.textBounds.clear();
            callback.onVisibleAreaChanged(new Rect(12, 12, 1091, 432));
            assertEquals(2, postedFrames[0]);
            assertEquals(firstTextTop, canvas.textBounds.get(0).top, 0.01);
            assertTrue("Mini tiles must occupy the action area despite the stale 88 px inset", firstTextTop < 40);
        } finally {
            surface.release();
            texture.release();
        }
    }

    private static List<DiagnosticSignal> catalogue(Context context, int year) throws Exception {
        return DiagnosticCatalog.load(context.getAssets().open("default.json"), year, true,
                context.getAssets().open("discovered_obd.json"));
    }

    private static NavigationState.Snapshot navigation() {
        return new NavigationState.Snapshot(NavigationState.Status.GUIDING, "Test", "Guiding",
                NavigationRoute.Turn.ROUNDABOUT, 150, 3, 100, Collections.emptyList());
    }

    private static final class RecordingCanvas extends Canvas {
        final Bitmap bitmap;
        final List<RectF> textBounds = new ArrayList<>();
        float arcSweep;

        RecordingCanvas(int width, int height) {
            this(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
        }

        private RecordingCanvas(Bitmap bitmap) {
            super(bitmap);
            this.bitmap = bitmap;
        }

        @Override public void drawArc(RectF oval, float start, float sweep, boolean useCenter, Paint paint) {
            arcSweep = sweep;
            super.drawArc(oval, start, sweep, useCenter, paint);
        }

        @Override public void drawText(String text, float horizontal, float vertical, Paint paint) {
            float width = paint.measureText(text);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            RectF bounds = new RectF(horizontal - width / 2, vertical + metrics.ascent,
                    horizontal + width / 2, vertical + metrics.descent);
            getMatrix().mapRect(bounds);
            textBounds.add(bounds);
            super.drawText(text, horizontal, vertical, paint);
        }
    }
}