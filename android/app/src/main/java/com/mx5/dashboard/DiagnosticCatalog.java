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
        return load(input, 2024);
    }

    public static List<DiagnosticSignal> load(InputStream input, int modelYear) throws IOException, JSONException {
        return load(input, modelYear, true);
    }

    public static List<DiagnosticSignal> load(InputStream input, int modelYear, boolean manual) throws IOException, JSONException {
        List<DiagnosticSignal> signals = new ArrayList<>();
        signals.add(new DiagnosticSignal("OBD_RPM", "Engine RPM", "rpm", 0x7DF, 0x7E8,
                Obd.RPM, true, 0, 16, 1, 4, 0, false, null, null));
        signals.add(new DiagnosticSignal("OBD_SPEED", "Vehicle speed", "km/h", 0x7DF, 0x7E8,
                Obd.SPEED, true, 0, 8, 1, 1, 0, false, null, null));
        JSONArray commands = readDocument(input).getJSONArray("commands");
        for (int index = 0; index < commands.length(); index++) {
            JSONObject command = commands.getJSONObject(index);
            JSONObject filter = command.optJSONObject("filter");
            if (filter != null) {
                JSONArray listed = filter.optJSONArray("years");
                int[] years = new int[listed == null ? 0 : listed.length()];
                for (int yearIndex = 0; yearIndex < years.length; yearIndex++) {
                    years[yearIndex] = listed.getInt(yearIndex);
                }
                if (!ModelYearFilter.matches(modelYear,
                        filter.has("from") ? filter.getInt("from") : null,
                        filter.has("to") ? filter.getInt("to") : null, years)) continue;
            }
            String did = command.getJSONObject("cmd").optString("22", "");
            if (did.length() != 4) continue;
            int requestId = Integer.parseInt(command.getString("hdr"), 16);
            if (!TransmissionFilter.allows(manual, requestId, Integer.parseInt(did, 16))) continue;
            JSONArray definitions = command.getJSONArray("signals");
            for (int signalIndex = 0; signalIndex < definitions.length(); signalIndex++) {
                signals.add(parseSignal(command, definitions.getJSONObject(signalIndex), Integer.parseInt(did, 16), false));
            }
        }
        return signals;
    }

    public static List<DiagnosticSignal> load(InputStream input, int modelYear, boolean manual,
            InputStream discovered) throws IOException, JSONException {
        List<DiagnosticSignal> mazda = load(input, modelYear, manual);
        JSONObject document = readDocument(discovered);
        JSONObject profile = document.getJSONObject("profile");
        if (profile.getInt("model_year") != modelYear
                || !profile.getString("transmission").equals(manual ? "manual" : "automatic")) return mazda;
        List<DiagnosticSignal> standard = new ArrayList<>();
        JSONArray commands = document.getJSONArray("commands");
        for (int index = 0; index < commands.length(); index++) {
            JSONObject command = commands.getJSONObject(index);
            int pid = Integer.parseInt(command.getJSONObject("cmd").getString("01"), 16);
            JSONArray definitions = command.getJSONArray("signals");
            for (int signalIndex = 0; signalIndex < definitions.length(); signalIndex++) {
                standard.add(parseSignal(command, definitions.getJSONObject(signalIndex), pid, true));
            }
        }
        standard.sort(java.util.Comparator.comparingInt(signal ->
                signal.id.equals("OBD_RPM") ? 0 : signal.id.equals("OBD_SPEED") ? 1 : 2));
        mazda.removeIf(signal -> signal.obd);
        standard.addAll(mazda);
        return standard;
    }

    private static DiagnosticSignal parseSignal(JSONObject command, JSONObject signal, int identifier,
            boolean obd) throws JSONException {
        JSONObject format = signal.getJSONObject("fmt");
        java.util.Map<Long, String> map = new java.util.HashMap<>();
        JSONObject mappings = format.optJSONObject("map");
        if (mappings != null) {
            java.util.Iterator<String> keys = mappings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(Long.parseLong(key), mappings.getJSONObject(key).optString("value", key));
            }
        }
        String id = signal.getString("id");
        if (obd && "RPM".equals(id)) id = "OBD_RPM";
        if (obd && "VSS".equals(id)) id = "OBD_SPEED";
        return new DiagnosticSignal(id, signal.getString("name"), format.optString("unit", ""),
                Integer.parseInt(command.getString("hdr"), 16), Integer.parseInt(command.getString("rax"), 16),
                identifier, obd, format.optInt("bix", 0), format.getInt("len"), format.optDouble("mul", 1),
                format.optDouble("div", 1), format.optDouble("add", 0), format.optBoolean("sign", false),
                format.has("nullmin") ? format.getDouble("nullmin") : null,
                format.has("nullmax") ? format.getDouble("nullmax") : null)
                .withFormat(format.has("min") ? format.getDouble("min") : null,
                        format.has("max") ? format.getDouble("max") : null, map);
    }

    private static JSONObject readDocument(InputStream input) throws IOException, JSONException {
        try (InputStream source = input) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}