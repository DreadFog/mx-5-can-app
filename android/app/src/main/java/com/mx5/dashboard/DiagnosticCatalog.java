package com.mx5.dashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DiagnosticCatalog {
    private DiagnosticCatalog() { }

    public static List<DiagnosticSignal> load(InputStream input) throws IOException, JSONException {
        List<DiagnosticSignal> signals = new ArrayList<>();
        signals.add(new DiagnosticSignal("OBD_RPM", "Engine RPM", "rpm", 0x7DF, 0x7E8,
                Obd.RPM, true, 0, 16, 1, 4, 0, false, null, null));
        signals.add(new DiagnosticSignal("OBD_SPEED", "Vehicle speed", "km/h", 0x7DF, 0x7E8,
                Obd.SPEED, true, 0, 8, 1, 1, 0, false, null, null));
        String json;
        try (InputStream source = input) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            json = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        JSONArray commands = new JSONObject(json).getJSONArray("commands");
        for (int index = 0; index < commands.length(); index++) {
            JSONObject command = commands.getJSONObject(index);
            String did = command.getJSONObject("cmd").optString("22", "");
            if (did.length() != 4) continue;
            JSONArray definitions = command.getJSONArray("signals");
            for (int signalIndex = 0; signalIndex < definitions.length(); signalIndex++) {
                JSONObject signal = definitions.getJSONObject(signalIndex);
                JSONObject format = signal.getJSONObject("fmt");
                signals.add(new DiagnosticSignal(signal.getString("id"), signal.getString("name"),
                        format.optString("unit", ""), Integer.parseInt(command.getString("hdr"), 16),
                        Integer.parseInt(command.getString("rax"), 16), Integer.parseInt(did, 16), false,
                        format.optInt("bix", 0), format.getInt("len"), format.optDouble("mul", 1),
                        format.optDouble("div", 1), format.optDouble("add", 0), format.optBoolean("sign", false),
                        format.has("nullmin") ? format.getDouble("nullmin") : null,
                        format.has("nullmax") ? format.getDouble("nullmax") : null));
            }
        }
        return signals;
    }
}