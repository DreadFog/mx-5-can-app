package com.mx5.dashboard;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class CanFrame {
    public static final int RECEIVED = -1;
    public final int echoId;
    public final int canId;
    public final int channel;
    public final int flags;
    public final byte[] data;

    public CanFrame(int echoId, int canId, int channel, int flags, byte[] data) {
        if (data.length > 8) throw new IllegalArgumentException("Classic CAN payload exceeds 8 bytes");
        this.echoId = echoId;
        this.canId = canId;
        this.channel = channel;
        this.flags = flags;
        this.data = data.clone();
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(echoId).putInt(canId).put((byte) data.length).put((byte) channel)
                .put((byte) flags).put((byte) 0).put(data);
        return buffer.array();
    }

    public static CanFrame decode(byte[] bytes, int length) {
        if (length < 12 || length > bytes.length) throw new IllegalArgumentException("Short gs_usb frame");
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int echoId = buffer.getInt();
        int canId = buffer.getInt();
        int dlc = Byte.toUnsignedInt(buffer.get());
        int channel = Byte.toUnsignedInt(buffer.get());
        int flags = Byte.toUnsignedInt(buffer.get());
        buffer.get();
        if (dlc > 8 || length < 12 + dlc || (flags & 2) != 0) {
            throw new IllegalArgumentException("Invalid or unsupported gs_usb frame");
        }
        return new CanFrame(echoId, canId, channel, flags, Arrays.copyOfRange(bytes, 12, 12 + dlc));
    }
}