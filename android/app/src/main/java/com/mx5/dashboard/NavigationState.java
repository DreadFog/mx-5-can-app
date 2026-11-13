package com.mx5.dashboard;

import java.util.Collections;
import java.util.List;

public final class NavigationState {
    public enum Status { IDLE, WAITING_GPS, ROUTING, GUIDING, OFF_ROUTE, GPS_LOST, ARRIVED, ERROR }

    public static final class Snapshot {
        public final Status status;
        public final String destination;
        public final String message;
        public final NavigationRoute.Turn turn;
        public final int distance;
        public final int exit;
        public final long updatedAt;
        public final List<NavigationRoute.Point> points;

        public Snapshot(Status status, String destination, String message, NavigationRoute.Turn turn,
                int distance, int exit, long updatedAt, List<NavigationRoute.Point> points) {
            this.status = status;
            this.destination = destination;
            this.message = message;
            this.turn = turn;
            this.distance = distance;
            this.exit = exit;
            this.updatedAt = updatedAt;
            this.points = points;
        }

        public boolean hasCue() { return status == Status.GUIDING || status == Status.ARRIVED; }
    }

    private static volatile Snapshot current = new Snapshot(Status.IDLE, "", "Select a destination",
            NavigationRoute.Turn.STRAIGHT, 0, 0, 0, Collections.emptyList());

    public static void publish(Snapshot snapshot) { current = snapshot; }

    public static Snapshot snapshot(long now) {
        Snapshot value = current;
        if (value.updatedAt != 0 && now - value.updatedAt > 10000) {
            boolean active = value.status != Status.ARRIVED && value.status != Status.IDLE && value.status != Status.ERROR;
            return new Snapshot(active ? Status.GPS_LOST : value.status, value.destination,
                active ? "Waiting for a reliable GPS fix" : value.message,
                    value.turn, value.distance, value.exit, value.updatedAt, value.points);
        }
        return value;
    }

    private NavigationState() { }
}