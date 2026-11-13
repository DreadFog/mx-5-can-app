package com.mx5.dashboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

public final class DestinationPanel extends ScrollView {
    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final EditText query;
    private final TextView status;
    private final TextView searchStatus;
    private final TextView selectedText;
    private final LinearLayout results;
    private final ImageButton search;
    private final Button start;
    private final ImageButton stop;
    private final MapView map;
    private final Marker marker;
    private final Polyline line;
    private NavigationClient.Destination selected;
    private List<NavigationRoute.Point> drawn = Collections.emptyList();
    private boolean closed;
    private boolean resumed;
    private long lastSearch;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (closed || !resumed) return;
            refreshNavigation();
            handler.postDelayed(this, 1000);
        }
    };

    public DestinationPanel(MainActivity activity) {
        super(activity);
        this.activity = activity;
        setFillViewport(true);
        setBackgroundColor(Color.rgb(25, 33, 30));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(20));
        addView(content);
        content.addView(label("DESTINATION", 22, Color.WHITE));
        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(activity);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.rgb(165, 181, 168));
        query.setHint("Address or place");
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));
        search = icon(android.R.drawable.ic_menu_search, "Search destination");
        searchRow.addView(search, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(searchRow);
        searchStatus = label("", 13, Color.rgb(165, 181, 168));
        content.addView(searchStatus);
        results = new LinearLayout(activity);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        selectedText = label("No destination selected", 14, Color.WHITE);
        selectedText.setMaxLines(3);
        selectedText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selectedText.setPadding(0, dp(12), 0, dp(12));
        content.addView(selectedText);
        LinearLayout commands = new LinearLayout(activity);
        start = new Button(activity);
        start.setText("START GUIDANCE");
        start.setTextColor(Color.rgb(25, 33, 30));
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(202, 226, 151)));
        start.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0);
        start.setEnabled(false);
        commands.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1));
        stop = icon(android.R.drawable.ic_media_pause, "Stop guidance");
        commands.addView(stop, new LinearLayout.LayoutParams(dp(52), dp(52)));
        content.addView(commands);
        status = label("Select a destination", 14, Color.rgb(165, 181, 168));
        status.setPadding(0, dp(8), 0, dp(12));
        content.addView(status);
        Configuration.getInstance().setUserAgentValue(activity.getPackageName() + "/1.0");
        map = new MapView(activity);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.getController().setZoom(3.0);
        map.getController().setCenter(new GeoPoint(46.5, 2.5));
        line = new Polyline(map);
        line.getOutlinePaint().setColor(Color.rgb(35, 119, 195));
        line.getOutlinePaint().setStrokeWidth(dp(5));
        marker = new Marker(map);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint point) { return false; }
            @Override public boolean longPressHelper(GeoPoint point) {
                select(new NavigationClient.Destination(String.format(java.util.Locale.US, "%.5f, %.5f",
                        point.getLatitude(), point.getLongitude()),
                        new NavigationRoute.Point(point.getLatitude(), point.getLongitude())));
                return true;
            }
        }));
        map.getOverlays().add(line);
        content.addView(map, new LinearLayout.LayoutParams(-1, dp(280)));
        TextView attribution = label("© OpenStreetMap contributors · OSRM", 11, Color.rgb(165, 181, 168));
        attribution.setPadding(0, dp(8), 0, 0);
        attribution.setOnClickListener(view -> activity.startActivity(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.openstreetmap.org/copyright"))));
        content.addView(attribution);
        search.setOnClickListener(view -> search());
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            search();
            return true;
        });
        start.setOnClickListener(view -> {
            if (selected != null) activity.startNavigation(selected);
        });
        stop.setOnClickListener(view -> {
            activity.stopService(new Intent(activity, NavigationService.class));
            NavigationState.publish(new NavigationState.Snapshot(NavigationState.Status.IDLE, "", "Navigation stopped",
                    NavigationRoute.Turn.STRAIGHT, 0, 0, 0, Collections.emptyList()));
            refreshNavigation();
        });
        SharedPreferences saved = activity.getSharedPreferences("navigation_destination", android.content.Context.MODE_PRIVATE);
        if (saved.contains("name")) select(new NavigationClient.Destination(saved.getString("name", "Destination"),
                new NavigationRoute.Point(Double.longBitsToDouble(saved.getLong("latitude", 0)),
                        Double.longBitsToDouble(saved.getLong("longitude", 0)))));
        refreshNavigation();
    }

    private void search() {
        String text = query.getText().toString().trim();
        if (text.length() < 3) { query.setError("Enter at least 3 characters"); return; }
        if (!search.isEnabled() || SystemClock.elapsedRealtime() - lastSearch < 1000) return;
        lastSearch = SystemClock.elapsedRealtime();
        search.setEnabled(false);
        searchStatus.setText("Searching...");
        results.removeAllViews();
        ((InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
        network.execute(() -> {
            try {
                List<NavigationClient.Destination> found = NavigationClient.search(text);
                handler.post(() -> {
                    if (closed) return;
                    search.setEnabled(true);
                    searchStatus.setText(found.isEmpty() ? "No matching destination" : "");
                    for (NavigationClient.Destination destination : found) {
                        TextView row = label(destination.name, 14, Color.WHITE);
                        row.setPadding(dp(8), dp(12), dp(8), dp(12));
                        row.setMinHeight(dp(48));
                        row.setBackgroundResource(android.R.drawable.list_selector_background);
                        row.setOnClickListener(view -> select(destination));
                        results.addView(row);
                    }
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (closed) return;
                    search.setEnabled(true);
                    searchStatus.setText("Search unavailable. Check the connection and try again.");
                });
            }
        });
    }

    private void select(NavigationClient.Destination destination) {
        selected = destination;
        searchStatus.setText("");
        results.removeAllViews();
        selectedText.setText(destination.name);
        start.setEnabled(true);
        marker.setPosition(new GeoPoint(destination.point.latitude, destination.point.longitude));
        marker.setTitle(destination.name);
        if (!map.getOverlays().contains(marker)) map.getOverlays().add(marker);
        map.getController().setZoom(15.0);
        map.getController().setCenter(marker.getPosition());
        map.invalidate();
        activity.getSharedPreferences("navigation_destination", android.content.Context.MODE_PRIVATE).edit()
                .putString("name", destination.name)
                .putLong("latitude", Double.doubleToLongBits(destination.point.latitude))
                .putLong("longitude", Double.doubleToLongBits(destination.point.longitude)).apply();
    }

    private void refreshNavigation() {
        NavigationState.Snapshot state = NavigationState.snapshot(SystemClock.elapsedRealtime());
        stop.setEnabled(state.status != NavigationState.Status.IDLE && state.status != NavigationState.Status.ARRIVED);
        status.setText(state.hasCue() ? state.distance + " m · " + NavigationTileRenderer.label(state.turn, state.exit)
            + "\n" + state.destination : state.message);
        if (drawn != state.points) {
            drawn = state.points;
            List<GeoPoint> points = new ArrayList<>();
            for (NavigationRoute.Point point : drawn) points.add(new GeoPoint(point.latitude, point.longitude));
            line.setPoints(points);
            if (!points.isEmpty()) map.post(() -> {
                if (!closed && drawn == state.points) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, dp(28));
            });
            map.invalidate();
        }
    }

    public void resume() {
        if (closed) return;
        resumed = true;
        map.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    public void pause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        map.onPause();
    }

    public void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        map.onDetach();
    }

    private ImageButton icon(int drawable, String description) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(drawable);
        button.setColorFilter(Color.WHITE);
        button.setBackgroundColor(Color.rgb(34, 45, 40));
        button.setContentDescription(description);
        button.setTooltipText(description);
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}