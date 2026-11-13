package com.mx5.dashboard;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SimulationBridge implements AutoCloseable {
    public static final String SOCKET_NAME = "mx5-can-sim";

    public interface Listener {
        void onConnectionChanged(boolean connected);
        void onFrame(CanFrame frame);
        void onReply(DiagnosticReply reply);
        void onTransmitConfirmation(int sequence, boolean accepted);
        void onError(String message);
    }

    private final Listener listener;
    private final LocalServerSocket server;
    private volatile boolean running = true;
    private volatile LocalSocket client;
    private volatile BufferedWriter writer;
    private final Object writeLock = new Object();
    private final AtomicInteger nextSequence = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Boolean> pendingTransmissions = new ConcurrentHashMap<>();
    private final Thread worker;

    public SimulationBridge(Listener listener) throws IOException {
        this.listener = listener;
        server = new LocalServerSocket(SOCKET_NAME);
        worker = new Thread(this::acceptClients, "pc-can-simulator");
        worker.start();
    }

    private void acceptClients() {
        while (running) {
            try (LocalSocket accepted = server.accept()) {
                client = accepted;
                writer = new BufferedWriter(new OutputStreamWriter(
                        accepted.getOutputStream(), StandardCharsets.UTF_8));
                listener.onConnectionChanged(true);
                readFrames(accepted);
            } catch (IOException error) {
                if (running) listener.onError("PC simulator socket: " + error.getMessage());
            } finally {
                writer = null;
                client = null;
                pendingTransmissions.clear();
                if (running) listener.onConnectionChanged(false);
            }
        }
    }

    public int sendFrame(CanFrame frame) throws IOException, JSONException {
        int sequence = nextSequence.getAndIncrement();
        JSONObject message = new JSONObject();
        message.put("type", "tx");
        message.put("seq", sequence);
        message.put("canId", frame.canId);
        message.put("data", toHex(frame.data));
        pendingTransmissions.put(sequence, true);
        writeMessage(message);
        return sequence;
    }

    private void readFrames(LocalSocket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while (running && (line = reader.readLine()) != null) {
            if (line.length() > 1024) {
                listener.onError("Ignored oversized simulator message");
                continue;
            }
            try {
                JSONObject message = new JSONObject(line);
                String type = message.optString("type", "frame");
                if ("ack".equals(type)) {
                    int sequence = message.getInt("seq");
                    if (pendingTransmissions.remove(sequence) != null) {
                        listener.onTransmitConfirmation(sequence, message.optBoolean("ok", false));
                    }
                    continue;
                }
                boolean reply = "reply".equals(type);
                if (!"frame".equals(type) && !reply) throw new IllegalArgumentException("Unknown simulator message type");
                int canId = message.getInt("canId");
                String hexData = message.getString("data");
                if (canId < 0 || canId > 0x7FF || hexData.length() > (reply ? 512 : 16) || (hexData.length() & 1) != 0) {
                    throw new IllegalArgumentException("Invalid standard CAN frame");
                }
                byte[] data = new byte[hexData.length() / 2];
                for (int index = 0; index < data.length; index++) {
                    data[index] = (byte) Integer.parseInt(hexData.substring(index * 2, index * 2 + 2), 16);
                }
                if (reply) listener.onReply(new DiagnosticReply(canId, data));
                else listener.onFrame(new CanFrame(CanFrame.RECEIVED, canId, 0, 0, data));
                writeAck(message.optInt("seq", -1), true);
            } catch (JSONException | IllegalArgumentException error) {
                listener.onError("Ignored malformed simulator frame: " + error.getMessage());
                writeAck(-1, false);
            }
        }
    }

    private void writeAck(int sequence, boolean accepted) throws IOException {
        if (sequence < 0) return;
        JSONObject ack = new JSONObject();
        try {
            ack.put("type", "ack");
            ack.put("seq", sequence);
            ack.put("ok", accepted);
        } catch (JSONException error) {
            throw new IOException("Unable to encode simulator acknowledgement", error);
        }
        writeMessage(ack);
    }

    private void writeMessage(JSONObject message) throws IOException {
        synchronized (writeLock) {
            BufferedWriter current = writer;
            if (current == null || client == null) throw new IOException("PC simulator is disconnected");
            current.write(message.toString());
            current.newLine();
            current.flush();
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder hex = new StringBuilder(data.length * 2);
        for (byte value : data) hex.append(String.format(java.util.Locale.US, "%02X", value & 0xFF));
        return hex.toString();
    }

    @Override public void close() {
        running = false;
        LocalSocket connectedClient = client;
        if (connectedClient != null) {
            try { connectedClient.close(); } catch (IOException ignored) { }
        }
        try { server.close(); } catch (IOException ignored) { }
        worker.interrupt();
    }
}