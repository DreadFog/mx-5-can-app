package com.mx5.dashboard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NavigationService extends Service implements LocationListener {
    public static final String STOP = "com.mx5.dashboard.STOP_NAVIGATION";
    private static final String CHANNEL = "navigation";
    private static final int NOTIFICATION = 51;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private LocationManager locations;
    private Location latest;
    private NavigationRoute route;
    private NavigationRoute.Point destination;
    private String name = "";
    private boolean routing;
    private boolean closed;
    private int generation;
    private long lastRequest;
    private String routeError;

    @Override public void onCreate() {
        super.onCreate();
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "GPS navigation", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            publish(NavigationState.Status.IDLE, "Select a destination");
            stopSelf();
            return START_NOT_STICKY;
        }
        name = intent.getStringExtra("name");
        if (name == null) name = "Destination";
        destination = new NavigationRoute.Point(intent.getDoubleExtra("latitude", Double.NaN),
                intent.getDoubleExtra("longitude", Double.NaN));
        if (!Double.isFinite(destination.latitude) || !Double.isFinite(destination.longitude)
                || Math.abs(destination.latitude) > 90 || Math.abs(destination.longitude) > 180
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            publish(NavigationState.Status.ERROR, "Precise location permission is required");
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIFICATION, notification);
        generation++;
        routing = false;
        route = null;
        routeError = null;
        lastRequest = 0;
        latest = null;
        publish(NavigationState.Status.WAITING_GPS, "Waiting for GPS");
        try {
            locations.removeUpdates(this);
            boolean enabled = false;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!locations.getAllProviders().contains(provider)) continue;
                boolean providerEnabled = locations.isProviderEnabled(provider);
                enabled |= providerEnabled;
                locations.requestLocationUpdates(provider, 1000, 2, this, Looper.getMainLooper());
                Location known = providerEnabled ? locations.getLastKnownLocation(provider) : null;
                if (reliable(known) && (latest == null || known.getElapsedRealtimeNanos() > latest.getElapsedRealtimeNanos())) latest = known;
            }
            if (!enabled) publish(NavigationState.Status.GPS_LOST, "Enable location on the phone");
            if (latest != null) onLocationChanged(latest);
        } catch (SecurityException | IllegalArgumentException error) {
            publish(NavigationState.Status.ERROR, "GPS is unavailable; check location permission");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .putExtra("destination_panel", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NavigationService.class)
                .setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("MX-5 navigation").setContentText(name).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build()).build();
    }

    private boolean reliable(Location location) {
        return location != null && location.hasAccuracy() && location.getAccuracy() <= 50
                && SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos() <= 10000000000L;
    }

    @Override public void onLocationChanged(Location location) {
        if (closed || destination == null) return;
        if (!reliable(location)) {
            if (!reliable(latest)) publish(NavigationState.Status.GPS_LOST, "Waiting for a reliable GPS fix");
            return;
        }
        if (latest != null && location.getElapsedRealtimeNanos() < latest.getElapsedRealtimeNanos()) return;
        latest = location;
        if (route == null) {
            if (routing) publish(NavigationState.Status.ROUTING, "Calculating route");
            requestRoute();
            return;
        }
        double deviation = route.update(new NavigationRoute.Point(location.getLatitude(), location.getLongitude()));
        if (deviation > 60) {
            publish(NavigationState.Status.OFF_ROUTE, routeError == null ? "Off route; recalculating" : routeError);
            requestRoute();
        } else if (route.arrived()) {
            publish(NavigationState.Status.ARRIVED, "Destination reached");
            stopSelf();
        } else {
            publish(NavigationState.Status.GUIDING, "Guiding");
        }
    }

    private void requestRoute() {
        if (routing || latest == null || closed) return;
        long now = SystemClock.elapsedRealtime();
        if (lastRequest != 0 && now - lastRequest < 15000) return;
        lastRequest = now;
        routing = true;
        int requestGeneration = generation;
        NavigationRoute.Point start = new NavigationRoute.Point(latest.getLatitude(), latest.getLongitude());
        NavigationRoute.Point target = destination;
        publish(NavigationState.Status.ROUTING, "Calculating route");
        network.execute(() -> {
            try {
                NavigationRoute result = NavigationClient.route(start, target);
                handler.post(() -> {
                    if (closed || requestGeneration != generation) return;
                    routing = false;
                    routeError = null;
                    route = result;
                    if (reliable(latest)) onLocationChanged(latest);
                    else publish(NavigationState.Status.GPS_LOST, "Waiting for a reliable GPS fix");
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (closed || requestGeneration != generation) return;
                    routing = false;
                    routeError = "Route unavailable; retrying when online";
                    publish(NavigationState.Status.ERROR, routeError);
                    android.util.Log.w("Mx5Navigation", "Route request failed", error);
                });
            }
        });
    }

    private void publish(NavigationState.Status status, String message) {
        NavigationRoute.Cue cue = route == null ? null : route.next();
        NavigationState.publish(new NavigationState.Snapshot(status, name, message,
                cue == null ? NavigationRoute.Turn.STRAIGHT : cue.turn,
                route == null ? 0 : NavigationRoute.roundedDistance(route.remaining()),
                cue == null ? 0 : cue.exit,
                latest == null ? 0 : latest.getElapsedRealtimeNanos() / 1000000,
                route == null || status == NavigationState.Status.IDLE ? Collections.emptyList() : route.points));
    }

    @Override public void onProviderDisabled(String provider) {
        latest = null;
        publish(NavigationState.Status.GPS_LOST, "Enable location on the phone");
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        closed = true;
        generation++;
        locations.removeUpdates(this);
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        NavigationState.Snapshot state = NavigationState.snapshot(SystemClock.elapsedRealtime());
        if (state.status != NavigationState.Status.ARRIVED && state.status != NavigationState.Status.ERROR
                && state.status != NavigationState.Status.IDLE) publish(NavigationState.Status.IDLE, "Navigation stopped");
        super.onDestroy();
    }
}