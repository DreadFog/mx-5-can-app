package com.mx5.dashboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NavigationClient {
    public static final class Destination {
        public final String name;
        public final NavigationRoute.Point point;

        public Destination(String name, NavigationRoute.Point point) {
            this.name = name;
            this.point = point;
        }
    }

    public static List<Destination> search(String query) throws IOException, JSONException {
        JSONArray results = new JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name())));
        List<Destination> destinations = new ArrayList<>();
        for (int index = 0; index < results.length(); index++) {
            JSONObject result = results.getJSONObject(index);
            destinations.add(new Destination(result.getString("display_name"), new NavigationRoute.Point(
                    result.getDouble("lat"), result.getDouble("lon"))));
        }
        return destinations;
    }

    public static NavigationRoute route(NavigationRoute.Point start, NavigationRoute.Point end)
            throws IOException, JSONException {
        String coordinates = String.format(Locale.US, "%.7f,%.7f;%.7f,%.7f",
                start.longitude, start.latitude, end.longitude, end.latitude);
        return parseRoute(get("https://router.project-osrm.org/route/v1/driving/" + coordinates
                + "?steps=true&overview=full&geometries=geojson"));
    }

    public static NavigationRoute parseRoute(String response) throws IOException, JSONException {
        JSONObject root = new JSONObject(response);
        if (!"Ok".equals(root.optString("code"))) throw new IOException("No driving route found");
        JSONArray routes = root.getJSONArray("routes");
        if (routes.length() == 0) throw new IOException("No driving route found");
        JSONObject route = routes.getJSONObject(0);
        JSONArray coordinates = route.getJSONObject("geometry").getJSONArray("coordinates");
        if (coordinates.length() < 2) throw new IOException("Route is too short");
        List<NavigationRoute.Point> points = new ArrayList<>();
        double[] along = new double[coordinates.length()];
        for (int index = 0; index < coordinates.length(); index++) {
            JSONArray coordinate = coordinates.getJSONArray(index);
            points.add(new NavigationRoute.Point(coordinate.getDouble(1), coordinate.getDouble(0)));
            if (index > 0) along[index] = along[index - 1]
                    + NavigationRoute.distance(points.get(index - 1), points.get(index));
        }
        List<NavigationRoute.Cue> cues = new ArrayList<>();
        JSONArray legs = route.getJSONArray("legs");
        int cursor = 0;
        for (int legIndex = 0; legIndex < legs.length(); legIndex++) {
            JSONArray steps = legs.getJSONObject(legIndex).getJSONArray("steps");
            for (int stepIndex = 0; stepIndex < steps.length(); stepIndex++) {
                JSONObject maneuver = steps.getJSONObject(stepIndex).getJSONObject("maneuver");
                String type = maneuver.getString("type");
                JSONArray location = maneuver.getJSONArray("location");
                NavigationRoute.Point point = new NavigationRoute.Point(location.getDouble(1), location.getDouble(0));
                int nearest = cursor;
                double closest = Double.POSITIVE_INFINITY;
                for (int index = cursor; index < points.size(); index++) {
                    double distance = NavigationRoute.distance(point, points.get(index));
                    if (distance < closest) { closest = distance; nearest = index; }
                    if (distance < 0.5) break;
                }
                cursor = nearest;
                if ("depart".equals(type) || "exit roundabout".equals(type) || "exit rotary".equals(type)) continue;
                cues.add(new NavigationRoute.Cue(NavigationRoute.classify(type, maneuver.optString("modifier")),
                        along[cursor], maneuver.optInt("exit", 0)));
            }
        }
        if (cues.isEmpty() || cues.get(cues.size() - 1).turn != NavigationRoute.Turn.ARRIVE) {
            cues.add(new NavigationRoute.Cue(NavigationRoute.Turn.ARRIVE, along[along.length - 1], 0));
        }
        return new NavigationRoute(points, cues);
    }

    private static String get(String address) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "MX5Dashboard/1.0 (Android; destination navigation prototype)");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException(code == 429 ? "Service busy; try again later"
                    : "Routing/search service unavailable (" + code + ")");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Route response is too large");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private NavigationClient() { }
}