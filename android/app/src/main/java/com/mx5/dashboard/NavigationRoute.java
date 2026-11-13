package com.mx5.dashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NavigationRoute {
    public static final class Point {
        public final double latitude;
        public final double longitude;

        public Point(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public enum Turn {
        STRAIGHT, LIGHT_LEFT, MEDIUM_LEFT, SHARP_LEFT,
        LIGHT_RIGHT, MEDIUM_RIGHT, SHARP_RIGHT, ROUNDABOUT, U_TURN, ARRIVE
    }

    public static final class Cue {
        public final Turn turn;
        public final double along;
        public final int exit;

        public Cue(Turn turn, double along, int exit) {
            this.turn = turn;
            this.along = along;
            this.exit = exit;
        }
    }

    public final List<Point> points;
    public final List<Cue> cues;
    private final double[] distances;
    private double progress;
    private boolean located;
    private double deviation;
    private double distanceToDestination = Double.POSITIVE_INFINITY;

    public NavigationRoute(List<Point> points, List<Cue> cues) {
        if (points.size() < 2 || cues.isEmpty()) throw new IllegalArgumentException("Empty route");
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.cues = Collections.unmodifiableList(new ArrayList<>(cues));
        distances = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            distances[index] = distances[index - 1] + distance(points.get(index - 1), points.get(index));
        }
    }

    public double update(Point position) {
        distanceToDestination = distance(position, points.get(points.size() - 1));
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestProgress = progress;
        double longitudeScale = Math.cos(Math.toRadians(position.latitude));
        for (int index = 1; index < points.size(); index++) {
            if (located && (distances[index] < progress - 30 || distances[index - 1] > progress + 400)) continue;
            Point start = points.get(index - 1);
            Point end = points.get(index);
            double startX = (start.longitude - position.longitude) * longitudeScale * 111195;
            double startY = (start.latitude - position.latitude) * 111195;
            double deltaX = (end.longitude - start.longitude) * longitudeScale * 111195;
            double deltaY = (end.latitude - start.latitude) * 111195;
            double squared = deltaX * deltaX + deltaY * deltaY;
            double fraction = squared == 0 ? 0 : Math.max(0, Math.min(1,
                    -(startX * deltaX + startY * deltaY) / squared));
            double error = Math.hypot(startX + fraction * deltaX, startY + fraction * deltaY);
            if (error < bestDistance) {
                bestDistance = error;
                bestProgress = distances[index - 1] + fraction * (distances[index] - distances[index - 1]);
            }
        }
        deviation = bestDistance;
        if (bestDistance <= 60) {
            progress = Math.max(progress, bestProgress);
            located = true;
        }
        return deviation;
    }

    public Cue next() {
        for (Cue cue : cues) {
            if (cue.turn == Turn.ARRIVE || cue.along > progress + 2) return cue;
        }
        return cues.get(cues.size() - 1);
    }

    public double remaining() { return Math.max(0, next().along - progress); }
    public boolean arrived() { return distanceToDestination <= 25 && totalDistance() - progress <= 20; }
    public double totalDistance() { return distances[distances.length - 1]; }

    public static int roundedDistance(double metres) {
        return (int) (Math.ceil(Math.max(0, metres) / 50) * 50);
    }

    public static Turn classify(String type, String modifier) {
        if (type.contains("roundabout") || type.contains("rotary")) return Turn.ROUNDABOUT;
        if ("arrive".equals(type)) return Turn.ARRIVE;
        if ("uturn".equals(modifier)) return Turn.U_TURN;
        switch (modifier) {
            case "slight left": return Turn.LIGHT_LEFT;
            case "left": return Turn.MEDIUM_LEFT;
            case "sharp left": return Turn.SHARP_LEFT;
            case "slight right": return Turn.LIGHT_RIGHT;
            case "right": return Turn.MEDIUM_RIGHT;
            case "sharp right": return Turn.SHARP_RIGHT;
            default: return Turn.STRAIGHT;
        }
    }

    public static double distance(Point first, Point second) {
        double latitudeDelta = Math.toRadians(second.latitude - first.latitude);
        double longitudeDelta = Math.toRadians(second.longitude - first.longitude);
        double haversine = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(Math.toRadians(first.latitude)) * Math.cos(Math.toRadians(second.latitude))
                * Math.pow(Math.sin(longitudeDelta / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, haversine)));
    }
}