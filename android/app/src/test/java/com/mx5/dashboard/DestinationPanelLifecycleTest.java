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