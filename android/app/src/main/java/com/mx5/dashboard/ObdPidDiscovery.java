package com.mx5.dashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ObdPidDiscovery {
    private final List<Integer> supported = new ArrayList<>();
    private int block;
    private boolean complete;
    private int blocksRead;

    public DiagnosticSignal request() {
        if (complete) throw new IllegalStateException("PID discovery is complete");
        return new DiagnosticSignal(String.format(Locale.US, "OBD_SUPPORT_%02X", block),
                "Supported engine PIDs", "mask", 0x7E0, 0x7E8, block, true,
                0, 32, 1, 1, 0, false, null, null);
    }

    public boolean accept(CanFrame frame) {
        if (complete || !request().matches(frame) || request().negative(frame)) return false;
        byte[] data = frame.data;
        if ((data[0] & 255) != 6 || data.length < 7) return false;
        long mask = 0;
        for (int index = 3; index < 7; index++) mask = (mask << 8) | (data[index] & 255);
        for (int offset = 1; offset <= 31; offset++) {
            if ((mask & (1L << (32 - offset))) != 0) supported.add(block + offset);
        }
        blocksRead++;
        if ((mask & 1) == 0 || block == 0xE0) complete = true;
        else block += 0x20;
        return true;
    }

    public boolean isComplete() { return complete; }
    public int getBlocksRead() { return blocksRead; }
    public List<Integer> getSupportedPids() { return Collections.unmodifiableList(supported); }
}