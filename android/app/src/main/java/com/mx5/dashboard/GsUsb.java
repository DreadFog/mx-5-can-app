package com.mx5.dashboard;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class GsUsb implements Runnable {
    private static final long REQUEST_INTERVAL_MS = 100;

    public interface Listener {
        void onState(String state, boolean connected, boolean active);
        void onFrame(CanFrame frame);
        void onReply(DiagnosticReply reply);
        void onLog(String message);
    }

    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile boolean requestedActive;
    private volatile boolean requestedDiagnosticMode;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint input;
    private UsbEndpoint output;
    private boolean active;
    private final AtomicReference<DiagnosticSignal> singleRead = new AtomicReference<>();

    public GsUsb(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
    }

    public void requestActive(boolean enabled) { requestedActive = enabled; }
    public void requestDiagnosticMode(boolean enabled) { requestedDiagnosticMode = enabled; }
    public void stop() { requestedActive = false; running = false; }

    public boolean readOnce(DiagnosticSignal signal) {
        return running && !requestedActive && singleRead.compareAndSet(null, signal);
    }

    private static byte[] words(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }

    private void controlOut(int request, int value, int index, byte[] payload) throws IOException {
        int transferred = connection.controlTransfer(0x41, request, value, index, payload, payload.length, 1000);
        if (transferred != payload.length) throw new IOException("USB control " + request + " failed (" + transferred + ")");
    }

    private byte[] controlIn(int request, int value, int index, int size) throws IOException {
        byte[] payload = new byte[size];
        int transferred = connection.controlTransfer(0xC1, request, value, index, payload, size, 1000);
        if (transferred != size) throw new IOException("USB control " + request + " failed (" + transferred + ")");
        return payload;
    }

    private void setMode(boolean sendEnabled) throws IOException {
        controlOut(2, 0, 0, words(0, 0));
        controlOut(1, 0, 0, words(6, 7, 2, 1, 6));
        controlOut(2, 0, 0, words(1, sendEnabled ? 0 : 1));
        active = sendEnabled;
        listener.onLog(sendEnabled ? "CAN started in normal mode, 500 kbit/s" : "CAN started in listen-only mode, 500 kbit/s");
        listener.onState("Connected", true, requestedActive);
    }

    private void open() throws IOException {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface candidate = device.getInterface(index);
            UsbEndpoint candidateIn = null;
            UsbEndpoint candidateOut = null;
            for (int endpointIndex = 0; endpointIndex < candidate.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) candidateIn = endpoint;
                else candidateOut = endpoint;
            }
            if (candidate.getId() == 0 && candidateIn != null && candidateOut != null) {
                usbInterface = candidate;
                input = candidateIn;
                output = candidateOut;
                break;
            }
        }
        if (usbInterface == null) throw new IOException("No gs_usb bulk interface found");
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Unable to open USB device; check permission");
        if (!connection.claimInterface(usbInterface, true)) throw new IOException("Unable to claim USB interface");
        listener.onLog(String.format(Locale.US, "USB interface %d, IN 0x%02X / OUT 0x%02X", usbInterface.getId(), input.getAddress(), output.getAddress()));
        controlOut(0, 1, usbInterface.getId(), words(0xBEEF));
        byte[] config = controlIn(5, 1, usbInterface.getId(), 12);
        ByteBuffer info = ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN);
        listener.onLog(String.format(Locale.US, "Device channels %d, firmware %d, hardware %d", (config[3] & 255) + 1, info.getInt(4), info.getInt(8)));
        ByteBuffer caps = ByteBuffer.wrap(controlIn(4, 0, 0, 40)).order(ByteOrder.LITTLE_ENDIAN);
        int features = caps.getInt();
        int clock = caps.getInt();
        int minTseg1 = caps.getInt();
        int maxTseg1 = caps.getInt();
        int minTseg2 = caps.getInt();
        int maxTseg2 = caps.getInt();
        int maxSjw = caps.getInt();
        int minBrp = caps.getInt();
        int maxBrp = caps.getInt();
        int brpIncrement = caps.getInt();
        listener.onLog(String.format(Locale.US, "Features 0x%08X, CAN clock %d Hz", features, clock));
        if ((features & 1) == 0) throw new IOException("Adapter does not report listen-only support");
        if (clock != 48000000 || minTseg1 > 13 || maxTseg1 < 13 || minTseg2 > 2 || maxTseg2 < 2
                || maxSjw < 1 || minBrp > 6 || maxBrp < 6 || brpIncrement <= 0 || (6 - minBrp) % brpIncrement != 0) {
            throw new IOException("Adapter does not support the tested 500 kbit/s timing");
        }
        setMode(false);
    }

    @Override public void run() {
        try {
            listener.onState("Connecting", false, false);
            open();
            byte[] buffer = new byte[64];
            long nextSend = 0;
            long echoDeadline = 0;
            int echoId = 0;
            int pendingEcho = -1;
            int pid = Obd.RPM;
            DiagnosticSignal reading = null;
            IsoTpReceiver isoTp = null;
            long readDeadline = 0;
            while (running) {
                boolean normalMode = requestedActive || requestedDiagnosticMode || singleRead.get() != null || reading != null || pendingEcho != -1;
                if (normalMode != active) {
                    setMode(normalMode);
                    nextSend = 0;
                }
                long now = SystemClock.elapsedRealtime();
                if (reading != null && now >= readDeadline) {
                    listener.onLog("Debug read timed out: " + reading.id);
                    singleRead.compareAndSet(reading, null);
                    reading = null;
                    isoTp = null;
                }
                if (active && pendingEcho != -1 && now >= echoDeadline) {
                    throw new IOException("No USB transmit confirmation; active reading stopped");
                }
                DiagnosticSignal queued = singleRead.get();
                boolean debugRead = !requestedActive && queued != null && reading == null;
                if (active && pendingEcho == -1 && now >= nextSend && (requestedActive || debugRead)) {
                    CanFrame frame = debugRead ? queued.request(echoId) : Obd.request(pid, echoId);
                    byte[] encoded = frame.encode();
                    int count = connection.bulkTransfer(output, encoded, encoded.length, 1000);
                    if (count != encoded.length) throw new IOException("USB write failed (" + count + ")");
                    listener.onLog(debugRead ? "TX Debug read " + queued.id
                            : String.format(Locale.US, "TX 7DF  02 01 %02X 00 00 00 00 00", pid));
                    if (debugRead) {
                        reading = queued;
                        isoTp = new IsoTpReceiver(queued);
                        readDeadline = now + 2000;
                    }
                    pendingEcho = echoId;
                    echoDeadline = now + 2000;
                    echoId = (echoId + 1) % 10;
                    pid = pid == Obd.RPM ? Obd.SPEED : Obd.RPM;
                    nextSend = now + REQUEST_INTERVAL_MS;
                }
                if (active && pendingEcho == -1 && isoTp != null && isoTp.needsFlowControl()) {
                    byte[] encoded = isoTp.flowControl(echoId).encode();
                    int written = connection.bulkTransfer(output, encoded, encoded.length, 1000);
                    if (written != encoded.length) throw new IOException("ISO-TP flow-control write failed");
                    pendingEcho = echoId;
                    echoDeadline = now + 2000;
                    echoId = (echoId + 1) % 10;
                    listener.onLog("TX ISO-TP flow control for " + reading.id);
                }
                int count = connection.bulkTransfer(input, buffer, buffer.length, 100);
                if (count < 0) {
                    if (!manager.getDeviceList().containsKey(device.getDeviceName())) throw new IOException("USB device disconnected");
                    continue;
                }
                try {
                    CanFrame frame = CanFrame.decode(buffer, count);
                    if (frame.channel != 0) continue;
                    if (frame.echoId != CanFrame.RECEIVED) {
                        if (frame.echoId == pendingEcho) {
                            pendingEcho = -1;
                            listener.onLog("USB transmit confirmation received");
                        }
                        continue;
                    }
                    listener.onFrame(frame);
                    if (reading != null && isoTp != null && frame.data.length > 0
                            && ((frame.data[0] & 255) >>> 4) != 0) {
                        DiagnosticReply reply = isoTp.accept(frame);
                        if (reply != null) {
                            listener.onReply(reply);
                            singleRead.compareAndSet(reading, null);
                            reading = null;
                            isoTp = null;
                        }
                    }
                    if (reading != null && reading.matches(frame)
                            && !(reading.negative(frame) && (frame.data[3] & 255) == 0x78)) {
                        singleRead.compareAndSet(reading, null);
                        reading = null;
                        isoTp = null;
                    }
                    if ((frame.canId & 0x20000040) == 0x20000040) throw new IOException("CAN bus-off reported; active reading stopped");
                } catch (IllegalArgumentException error) {
                    listener.onLog("Ignored malformed USB frame: " + error.getMessage());
                }
            }
        } catch (IOException | SecurityException error) {
            listener.onLog("ERROR: " + error.getMessage());
            listener.onState(error.getMessage(), false, false);
        } finally {
            requestedActive = false;
            if (connection != null) {
                try { controlOut(2, 0, 0, words(0, 0)); } catch (Exception ignored) { }
                if (usbInterface != null) connection.releaseInterface(usbInterface);
                connection.close();
            }
            listener.onLog("USB session closed");
            listener.onState("Disconnected", false, false);
        }
    }
}