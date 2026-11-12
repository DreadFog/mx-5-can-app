package com.mx5.dashboard;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class DebugPanel extends ScrollView {
    public interface Transport {
        boolean ready();
        boolean physical();
        int send(DiagnosticSignal signal) throws Exception;
        void holdNormalMode(boolean enabled);
        void log(String message);
    }

    private final Transport transport;
    private final List<DiagnosticSignal> signals;
    private final HashMap<String, TextView> valueViews = new HashMap<>();
    private final HashMap<String, ImageButton> readButtons = new HashMap<>();
    private final HashMap<String, CheckBox> liveButtons = new HashMap<>();
    private final HashMap<String, String> values = new HashMap<>();
    private final HashMap<String, Long> receivedAt = new HashMap<>();
    private final LinkedHashMap<DiagnosticSignal, Long> live = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DiagnosticSignal pending;
    private int pendingSequence = -1;
    private long deadline;
    private long refreshedAt;
    private int cursor;
    private boolean closed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            if (pending != null && now >= deadline) failPending("No response / timeout");
            if (pending == null && transport.ready() && !live.isEmpty()) {
                DiagnosticSignal[] selected = live.keySet().toArray(new DiagnosticSignal[0]);
                for (int offset = 0; offset < selected.length; offset++) {
                    int index = (cursor + offset) % selected.length;
                    DiagnosticSignal signal = selected[index];
                    if (now >= live.get(signal)) {
                        cursor = (index + 1) % selected.length;
                        live.put(signal, now + 100);
                        read(signal);
                        break;
                    }
                }
            }
            if (now - refreshedAt >= 250) {
                refresh();
                refreshedAt = now;
            }
            long delay = 250;
            if (pending == null && transport.ready()) {
                long current = SystemClock.elapsedRealtime();
                for (long due : live.values()) delay = Math.min(delay, Math.max(1, due - current));
            }
            handler.postDelayed(this, delay);
        }
    };

    public DebugPanel(Context context, List<DiagnosticSignal> signals, Transport transport) {
        super(context);
        this.signals = signals;
        this.transport = transport;
        setFillViewport(true);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(14), dp(16), dp(14), dp(20));
        addView(rows);
        TextView title = text("DEBUG / RAW VALUES", 20, Color.WHITE);
        title.setPadding(0, 0, 0, dp(12));
        rows.addView(title);
        ImageButton stopLive = new ImageButton(context);
        stopLive.setImageResource(android.R.drawable.ic_media_pause);
        stopLive.setColorFilter(0xFFCAE297);
        stopLive.setBackgroundColor(0xFF222D28);
        stopLive.setContentDescription("Stop all Live reads");
        stopLive.setTooltipText("Stop all Live reads");
        rows.addView(stopLive, new LinearLayout.LayoutParams(dp(48), dp(48)));
        stopLive.setOnClickListener(view -> stop("Debug reads stopped by user"));
        for (DiagnosticSignal signal : signals) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            rows.addView(row);
            LinearLayout detail = new LinearLayout(context);
            detail.setOrientation(LinearLayout.VERTICAL);
            row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
            detail.addView(text(signal.name, 13, Color.WHITE));
            detail.addView(text(signal.id + String.format(Locale.US, "\n%03X → %03X / %s %s",
                    signal.requestId, signal.responseId, signal.obd ? "01" : "22",
                    String.format(Locale.US, signal.obd ? "%02X" : "%04X", signal.identifier)), 10, 0xFFA5B5A8));
            TextView value = text("Not read", 11, 0xFFCAE297);
            value.setTypeface(Typeface.MONOSPACE);
            value.setPadding(0, dp(4), dp(4), 0);
            detail.addView(value);
            valueViews.put(signal.id, value);
            LinearLayout controls = new LinearLayout(context);
            controls.setOrientation(LinearLayout.VERTICAL);
            row.addView(controls, new LinearLayout.LayoutParams(dp(68), -2));
            ImageButton once = new ImageButton(context);
            once.setImageResource(android.R.drawable.ic_menu_rotate);
            once.setColorFilter(0xFFCAE297);
            once.setBackgroundColor(0xFF222D28);
            once.setContentDescription("Read once: " + signal.name);
            once.setTooltipText("Read once: " + signal.name);
            controls.addView(once, new LinearLayout.LayoutParams(dp(48), dp(48)));
            readButtons.put(signal.id, once);
            once.setOnClickListener(view -> confirm(signal, false));
            CheckBox liveButton = new CheckBox(context);
            liveButton.setText("Live");
            liveButton.setTextSize(11);
            liveButton.setTextColor(Color.WHITE);
            liveButton.setContentDescription("Live polling: " + signal.name);
            controls.addView(liveButton, new LinearLayout.LayoutParams(-1, dp(48)));
            liveButtons.put(signal.id, liveButton);
            liveButton.setOnClickListener(view -> {
                if (!liveButton.isChecked()) {
                    live.remove(signal);
                    transport.holdNormalMode(!live.isEmpty());
                } else {
                    liveButton.setChecked(false);
                    confirm(signal, true);
                }
            });
            View divider = new View(context);
            divider.setBackgroundColor(0xFF222D28);
            rows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        handler.post(tick);
    }

    private void confirm(DiagnosticSignal signal, boolean continuous) {
        if (!transport.ready() || (!continuous && pending != null)) return;
        Runnable start = () -> {
            if (!transport.ready()) return;
            if (continuous) {
                live.put(signal, 0L);
                liveButtons.get(signal.id).setChecked(true);
                transport.holdNormalMode(true);
                handler.removeCallbacks(tick);
                handler.post(tick);
            } else if (pending == null) read(signal);
        };
        if (!transport.physical()) {
            start.run();
            return;
        }
        new AlertDialog.Builder(getContext()).setTitle(continuous ? "Poll vehicle at up to 10 Hz?" : "Read from vehicle?")
                .setMessage("Only while parked. " + (signal.obd ? "Standard OBD read." :
                        "Catalogue UDS signal: support for the 2024 vehicle is not confirmed.")
                        + (continuous ? " Live remains active across pages until unchecked or disconnected." :
                        " Sends one request then returns to listen-only unless other Live reads are selected."))
                .setNegativeButton("Cancel", null)
                .setPositiveButton(continuous ? "Start Live" : "Read once", (dialog, which) -> start.run()).show();
    }

    private void read(DiagnosticSignal signal) {
        pending = signal;
        deadline = SystemClock.elapsedRealtime() + 2500;
        try {
            pendingSequence = transport.send(signal);
            if (!values.containsKey(signal.id)) values.put(signal.id, "Waiting for response");
        } catch (Exception error) {
            failPending("Read failed: " + error.getMessage());
        }
    }

    public boolean acknowledge(int sequence, boolean accepted) {
        if (pending == null || sequence != pendingSequence) return false;
        if (!accepted) failPending("Simulator rejected request");
        return true;
    }

    public void accept(CanFrame frame, boolean simulated) {
        for (DiagnosticSignal signal : signals) {
            if (!signal.matches(frame)) continue;
            if (signal.negative(frame) && pending != signal) continue;
            Double value = signal.decode(frame);
            String decoded = signal.negative(frame)
                    ? String.format(Locale.US, "Negative response / NRC %02X", frame.data[3] & 255)
                    : value == null ? "Unavailable / invalid payload" : String.format(Locale.US, "%.3f %s", value, signal.unit);
            StringBuilder raw = new StringBuilder();
            for (byte item : frame.data) raw.append(String.format(Locale.US, "%02X ", item & 255));
            values.put(signal.id, (simulated ? "SIM / " : "CAN / ") + decoded + "\n" + raw);
            receivedAt.put(signal.id, SystemClock.elapsedRealtime());
        }
        if (pending != null && pending.matches(frame)) {
            if (pending.negative(frame)) {
                if ((frame.data[3] & 255) != 0x78) failPending(null);
            } else if (pending.decode(frame) == null) {
                failPending(null);
            } else {
                pending = null;
                pendingSequence = -1;
            }
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        refresh();
    }

    private void failPending(String reason) {
        if (pending == null) return;
        if (reason != null) {
            values.put(pending.id, reason + (values.containsKey(pending.id) ? "\nLast: " + values.get(pending.id) : ""));
            transport.log("Debug " + pending.id + ": " + reason);
        }
        live.remove(pending);
        liveButtons.get(pending.id).setChecked(false);
        pending = null;
        pendingSequence = -1;
        transport.holdNormalMode(!live.isEmpty());
    }

    public boolean busy() {
        return pending != null || !live.isEmpty();
    }

    public void stop(String reason) {
        failPending(reason);
        live.clear();
        for (CheckBox button : liveButtons.values()) button.setChecked(false);
        transport.holdNormalMode(false);
        refresh();
    }

    public void close() {
        closed = true;
        stop("Debug closed");
        handler.removeCallbacks(tick);
    }

    private void refresh() {
        boolean ready = transport.ready();
        for (DiagnosticSignal signal : signals) {
            boolean onceEnabled = ready && pending == null && live.isEmpty();
            ImageButton once = readButtons.get(signal.id);
            if (once.isEnabled() != onceEnabled) {
                once.setEnabled(onceEnabled);
                once.setAlpha(onceEnabled ? 1 : 0.35f);
            }
            CheckBox liveButton = liveButtons.get(signal.id);
            boolean liveEnabled = ready || live.containsKey(signal);
            if (liveButton.isEnabled() != liveEnabled) liveButton.setEnabled(liveEnabled);
            String value = values.get(signal.id);
            if (value != null) {
                Long updated = receivedAt.get(signal.id);
                long age = updated == null ? 0 : SystemClock.elapsedRealtime() - updated;
                String status = updated == null ? "" : age > 3000 ? "\nSTALE" : "\nFRESH";
                if (pending == signal && !live.containsKey(signal)) status += " / reading";
                TextView display = valueViews.get(signal.id);
                String content = value + status;
                if (!content.contentEquals(display.getText())) display.setText(content);
            }
        }
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}