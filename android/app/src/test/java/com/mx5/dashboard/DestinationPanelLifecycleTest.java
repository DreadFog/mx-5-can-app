package com.mx5.dashboard;

import android.os.Looper;
import android.os.SystemClock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osmdroid.views.MapView;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DestinationPanelLifecycleTest {
    @Test public void bodyColourSelectionIsSavedAndRestoredByConfiguration() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences preferences = context.getSharedPreferences(CarPaint.PREFERENCES, android.content.Context.MODE_PRIVATE);
        preferences.edit().remove(CarPaint.KEY).commit();
        try {
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
                MainActivity activity = controller.create().start().resume().visible().get();
                showPage(activity, 2);
                android.widget.Spinner picker = ReflectionHelpers.getField(activity, "carPaintPicker");
                assertEquals(CarPaint.SOUL_RED_CRYSTAL, picker.getSelectedItem());
                assertEquals(CarPaint.values().length, picker.getCount());
                picker.setSelection(CarPaint.DEEP_CRYSTAL_BLUE.ordinal());
                shadowOf(Looper.getMainLooper()).idle();
                assertEquals(CarPaint.DEEP_CRYSTAL_BLUE, CarPaint.load(context));
                controller.pause().stop().destroy();
            }
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
                MainActivity activity = controller.create().start().resume().visible().get();
                android.widget.Spinner picker = ReflectionHelpers.getField(activity, "carPaintPicker");
                assertEquals(CarPaint.DEEP_CRYSTAL_BLUE, picker.getSelectedItem());
                controller.pause().stop().destroy();
            }
        } finally {
            preferences.edit().remove(CarPaint.KEY).commit();
        }
    }

    @Test public void debugSwitchOverridesActiveAutoReadsAndCanResumeThem() {
        DashboardTelemetry.showAutoPage(DashboardTelemetry.Page.MAIN, false);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            ReflectionHelpers.callInstanceMethod(activity, "onSimulationConnectionChanged",
                    ReflectionHelpers.ClassParameter.from(boolean.class, true));
            assertEquals(Boolean.TRUE, ReflectionHelpers.getField(activity, "active"));
            DashboardTelemetry.showAutoPage(DashboardTelemetry.Page.MAIN, true);
            showPage(activity, 3);
            DebugPanel panel = ReflectionHelpers.getField(activity, "debugPanel");
            android.widget.LinearLayout rows = (android.widget.LinearLayout) panel.getChildAt(0);
            android.widget.Switch toggle = (android.widget.Switch) rows.getChildAt(1);
            DebugPanel.Transport transport = ReflectionHelpers.getField(activity, "debugTransport");
            assertTrue(toggle.isChecked());
            assertFalse(transport.ready());
            java.util.List<DiagnosticSignal> signals = ReflectionHelpers.getField(activity, "diagnosticSignals");
            DashboardPoller poller = new DashboardPoller(signals, new DashboardPoller.Transport() {
                @Override public int send(DiagnosticSignal signal) { return -1; }
                @Override public void log(String message) { }
            });
            ReflectionHelpers.setField(activity, "dashboardPoller", poller);
            poller.tick(100, DashboardTelemetry.Page.MAIN, true);
            toggle.setChecked(false);
            assertEquals(Boolean.FALSE, ReflectionHelpers.getField(activity, "active"));
            assertFalse(transport.ready());
            poller.accept(new DiagnosticReply(0x7E8, new byte[]{0x41, 12, 0x10, 0}), 200);
            assertTrue(transport.ready());
            ReflectionHelpers.callInstanceMethod(activity, "updateDashboardAcquisition");
            assertTrue(transport.ready());
            showPage(activity, 0);
            assertEquals(Boolean.FALSE, ReflectionHelpers.getField(activity, "active"));
            showPage(activity, 3);
            assertFalse(toggle.isChecked());
            java.util.Map<DiagnosticSignal, Long> live = ReflectionHelpers.getField(panel, "live");
            live.put(signals.get(0), 0L);
            assertTrue(panel.busy());
            toggle.setChecked(true);
            assertFalse(panel.busy());
            assertEquals(Boolean.TRUE, ReflectionHelpers.getField(activity, "active"));
            assertFalse(transport.ready());
            ReflectionHelpers.callInstanceMethod(activity, "onSimulationConnectionChanged",
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
            assertEquals(Boolean.FALSE, ReflectionHelpers.getField(activity, "active"));
            controller.pause().stop().destroy();
        } finally {
            DashboardTelemetry.showAutoPage(DashboardTelemetry.Page.MAIN, false);
        }
    }

    @Test public void routeUpdatesSurviveSwitchingAwayFromDestination() {
        NavigationState.publish(new NavigationState.Snapshot(NavigationState.Status.IDLE, "", "Select a destination",
                NavigationRoute.Turn.STRAIGHT, 0, 0, 0, Collections.emptyList()));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            DestinationPanel panel = ReflectionHelpers.getField(activity, "destinationPanel");
            MapView map = ReflectionHelpers.getField(panel, "map");
            map.setUseDataConnection(false);
            showPage(activity, 4);
            showPage(activity, 0);
            NavigationState.publish(new NavigationState.Snapshot(NavigationState.Status.GUIDING, "Test destination", "Guiding",
                    NavigationRoute.Turn.MEDIUM_LEFT, 150, 0, SystemClock.elapsedRealtime(), Arrays.asList(
                            new NavigationRoute.Point(48, 2), new NavigationRoute.Point(48.001, 2.001))));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(map.getOverlayManager().overlays().isEmpty());
            showPage(activity, 4);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            controller.pause().stop().destroy();
        }
    }

    private void showPage(MainActivity activity, int page) {
        ReflectionHelpers.callInstanceMethod(activity, "showPage", ReflectionHelpers.ClassParameter.from(int.class, page));
    }
}