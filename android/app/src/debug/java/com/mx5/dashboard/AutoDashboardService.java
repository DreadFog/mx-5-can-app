package com.mx5.dashboard;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.Lifecycle;
import java.util.Locale;

public final class AutoDashboardService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
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
        private final Runnable refresh = new Runnable() {
            @Override public void run() {
                invalidate();
                handler.postDelayed(this, 1000);
            }
        };

        DashboardScreen(CarContext context) {
            super(context);
            getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver) (source, event) -> {
                if (event == Lifecycle.Event.ON_START) {
                    handler.removeCallbacks(refresh);
                    handler.post(refresh);
                } else if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    handler.removeCallbacks(refresh);
                }
            });
        }

        @NonNull @Override public Template onGetTemplate() {
            DashboardTelemetry.Snapshot snapshot = DashboardTelemetry.snapshot(SystemClock.elapsedRealtime());
            Pane.Builder pane = new Pane.Builder();
            pane.addRow(new Row.Builder().setTitle("Engine RPM")
                    .addText(snapshot.rpm == null ? "--" : String.format(Locale.US, "%.0f rpm%s",
                            snapshot.rpm, snapshot.rpmSimulated ? " / SIM" : " / CAN")).build());
            pane.addRow(new Row.Builder().setTitle("Vehicle speed")
                    .addText(snapshot.speed == null ? "--" : String.format(Locale.US, "%.0f km/h%s",
                            snapshot.speed, snapshot.speedSimulated ? " / SIM" : " / CAN")).build());
            pane.addRow(new Row.Builder().setTitle(snapshot.rpm != null && snapshot.rpm >= snapshot.threshold
                            ? "UP" : "CAN status")
                    .addText(snapshot.state)
                    .addText(String.format(Locale.US, "ND %d / threshold %d rpm", snapshot.modelYear, snapshot.threshold)).build());
            return new PaneTemplate.Builder(pane.build()).setTitle("MX-5 Experimental")
                    .setHeaderAction(Action.APP_ICON).build();
        }
    }
}