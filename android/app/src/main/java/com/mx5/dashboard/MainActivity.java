package com.mx5.dashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.CheckBox;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;

public final class MainActivity extends Activity implements GsUsb.Listener {
    private static final String USB_PERMISSION = "com.mx5.dashboard.USB_PERMISSION";
    private static final String PREFERENCES = "dashboard_preferences";
    private static final String KEY_RPM_THRESHOLD = "rpm_threshold";
    private static final String KEY_MODEL_YEAR = "model_year";
    private static final String KEY_MANUAL_TRANSMISSION = "manual_transmission";
    private static final String KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake";
    private static final int DEFAULT_MODEL_YEAR = 2024;
    private static final int DEFAULT_RPM_THRESHOLD = 6000;
    private static final int MIN_RPM_THRESHOLD = 1000;
    private static final int MAX_RPM_THRESHOLD = 8000;
    private static final long SIM_REQUEST_INTERVAL_MS = 100;
    private static final int PAGE_DASHBOARD = 0;
    private static final int PAGE_LOGS = 1;
    private static final int PAGE_CONFIGURATION = 2;
    private static final int PAGE_DEBUG = 3;
    private static final int PAGE_DESTINATION = 4;
    private static final int NAVIGATION_PERMISSION = 51;
    private static final int BACKGROUND = Color.rgb(25, 33, 30);
    private static final int PANEL = Color.rgb(34, 45, 40);
    private static final int MUTED = Color.rgb(165, 181, 168);
    private static final int LIME = Color.rgb(202, 226, 151);
    private static final int RED = Color.rgb(217, 96, 82);

    private UsbManager usbManager;
    private SimulationBridge simulationBridge;
    private UsbDevice device;
    private GsUsb session;
    private Thread worker;
    private TextView connectionText;
    private TextView adapterText;
    private TextView speedText;
    private TextView rpmText;
    private TextView gearText;
    private TextView rpmFill;
    private TextView receiveText;
    private TextView transmitText;
    private TextView lastFrameText;
    private TextView logText;
    private Button connectButton;
    private Button readButton;
    private Button dashboardTab;
    private Button logsTab;
    private Button configurationTab;
    private Button debugTab;
    private Button destinationTab;
    private DestinationPanel destinationPanel;
    private NavigationClient.Destination pendingDestination;
    private DebugPanel debugPanel;
    private DebugPanel.Transport debugTransport;
    private final List<DiagnosticSignal> diagnosticSignals = new ArrayList<>();
    private EditText thresholdInput;
    private EditText modelYearInput;
    private TextView modelYearSummary;
    private int modelYear = DEFAULT_MODEL_YEAR;
    private boolean manualTransmission = true;
    private RadioButton manualChoice;
    private boolean keepScreenAwake = true;
    private boolean destroyed;
    private PowerManager powerManager;
    private PowerManager.WakeLock sessionWakeLock;
    private long wakeLockRenewedAt;
    private TextView batteryStatus;
    private ScrollView logScroll;
    private FrameLayout appRoot;
    private FrameLayout pageContainer;
    private RpmEdgeView shiftAlertOverlay;
    private View dashboardPage;
    private View logsPage;
    private View configurationPage;
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private int receivedFrames;
    private int transmittedFrames;
    private boolean connected;
    private boolean active;
    private boolean simulatorClientConnected;
    private int simulatorPid = Obd.RPM;
    private int simulatorEchoId;
    private int pendingSimulatorSequence = -1;
    private int rpmThreshold = DEFAULT_RPM_THRESHOLD;
    private double latestRpm;
    private long rpmUpdatedAt;
    private long speedUpdatedAt;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable expireValues = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (rpmUpdatedAt != 0 && now - rpmUpdatedAt > 3000) {
                rpmText.setText("--");
                FrameLayout.LayoutParams fillParams = (FrameLayout.LayoutParams) rpmFill.getLayoutParams();
                fillParams.height = 0;
                rpmFill.setLayoutParams(fillParams);
                rpmUpdatedAt = 0;
                updateShiftAlert();
            }
            if (speedUpdatedAt != 0 && now - speedUpdatedAt > 3000) {
                speedText.setText("--");
                speedUpdatedAt = 0;
            }
            updateSessionWakeLock();
            uiHandler.postDelayed(this, 250);
        }
    };
    private final Runnable simulatorPoll = new Runnable() {
        @Override public void run() {
            if (!active || connected || !simulatorClientConnected || simulationBridge == null
                    || pendingSimulatorSequence != -1) return;
            try {
                CanFrame request = Obd.request(simulatorPid, simulatorEchoId);
                pendingSimulatorSequence = simulationBridge.sendFrame(request);
                addLog(String.format(Locale.US, "SIM TX 7DF  02 01 %02X", simulatorPid));
                simulatorEchoId = (simulatorEchoId + 1) % 10;
                simulatorPid = simulatorPid == Obd.RPM ? Obd.SPEED : Obd.RPM;
            } catch (IOException | JSONException error) {
                stopSimulatorReads("PC simulator send failed: " + error.getMessage());
            }
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action)) {
                UsbDevice granted = getUsbDevice(intent);
                if (granted != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device = granted;
                    startSession();
                } else {
                    addLog("USB permission denied");
                    setConnection("USB permission denied", false);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice detached = getUsbDevice(intent);
                if (device != null && detached != null && device.getDeviceId() == detached.getDeviceId()) {
                    addLog("USB device detached");
                    stopSession();
                    device = null;
                    showAdapter();
                }
            }
        }
    };

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private void hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        hideStatusBar();
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        sessionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mx5:usb-can-session");
        sessionWakeLock.setReferenceCounted(false);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        rpmThreshold = preferences.getInt(KEY_RPM_THRESHOLD, DEFAULT_RPM_THRESHOLD);
        modelYear = preferences.getInt(KEY_MODEL_YEAR, DEFAULT_MODEL_YEAR);
        manualTransmission = preferences.getBoolean(KEY_MANUAL_TRANSMISSION, true);
        keepScreenAwake = preferences.getBoolean(KEY_KEEP_SCREEN_AWAKE, true);
        applyScreenAwake();
        DashboardTelemetry.configure(rpmThreshold, modelYear);
        try {
            diagnosticSignals.addAll(loadDiagnosticCatalogue(modelYear, manualTransmission));
        } catch (IOException | JSONException error) {
            android.util.Log.e("Mx5Debug", "Unable to load diagnostic catalogue", error);
        }
        buildScreen();
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
        addLog("App started. CAN transmission is disabled by default.");
        try {
            simulationBridge = new SimulationBridge(new SimulationBridge.Listener() {
                @Override public void onConnectionChanged(boolean linkConnected) {
                    runOnUiThread(() -> onSimulationConnectionChanged(linkConnected));
                }

                @Override public void onFrame(CanFrame frame) {
                    onSimulatedFrame(frame);
                }

                @Override public void onReply(DiagnosticReply reply) {
                    runOnUiThread(() -> {
                        if (connected) {
                            addLog("Rejected SIM reply while physical CAN session is open");
                            return;
                        }
                        debugPanel.acceptReply(reply, true);
                        receivedFrames++;
                        receiveText.setText(String.format(Locale.US, "SIM frames   %d", receivedFrames));
                        lastFrameText.setText(String.format(Locale.US, "SIM reply 0x%03X %s", reply.canId, hex(reply.payload)));
                        addLog(String.format(Locale.US, "SIM reply 0x%03X %s", reply.canId, hex(reply.payload)));
                    });
                }

                @Override public void onTransmitConfirmation(int sequence, boolean accepted) {
                    runOnUiThread(() -> {
                        if (debugPanel.acknowledge(sequence, accepted)) {
                            if (accepted) {
                                transmittedFrames++;
                                transmitText.setText(String.format(Locale.US, "SIM TX requests  %d", transmittedFrames));
                            }
                            return;
                        }
                        if (sequence != pendingSimulatorSequence) return;
                        pendingSimulatorSequence = -1;
                        if (accepted) {
                            transmittedFrames++;
                            transmitText.setText(String.format(Locale.US, "SIM TX requests  %d", transmittedFrames));
                            addLog("PC simulator accepted active read " + sequence);
                            if (active && simulatorClientConnected) {
                                uiHandler.postDelayed(simulatorPoll, SIM_REQUEST_INTERVAL_MS);
                            }
                        } else {
                            stopSimulatorReads("PC simulator rejected active read " + sequence);
                        }
                    });
                }

                @Override public void onError(String message) {
                    runOnUiThread(() -> addLog(message));
                }
            });
            addLog("PC simulator ready through adb forward; no CAN USB output");
        } catch (IOException error) {
            addLog("PC simulator unavailable: " + error.getMessage());
        }
        uiHandler.post(expireValues);
        findAdapter();
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            UsbDevice attached = getUsbDevice(getIntent());
            if (isCanable(attached)) {
                device = attached;
                addLog("CANable attachment detected");
                requestUsbPermission();
            }
        }
    }

    private void buildScreen() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(26));
        page.addView(content);

        TextView brand = text("MX-5  /  ND 2024", 13, LIME, true);
        brand.setLetterSpacing(0.12f);
        content.addView(brand);
        TextView title = text("DRIVER DISPLAY", 30, Color.WHITE, true);
        title.setPadding(0, dp(8), 0, dp(20));
        content.addView(title);

        LinearLayout statusPanel = panel(content);
        connectionText = text("CANABLE NOT CONNECTED", 13, MUTED, true);
        statusPanel.addView(connectionText);
        adapterText = text("USB 1D50:606F  /  500 kbit/s", 11, MUTED, false);
        adapterText.setPadding(0, dp(7), 0, 0);
        statusPanel.addView(adapterText);
        connectButton = button("CONNECT CANABLE", false);
        statusPanel.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(48)));
        connectButton.setOnClickListener(view -> onConnectClicked());

        LinearLayout gauges = new LinearLayout(this);
        gauges.setGravity(Gravity.CENTER_VERTICAL);
        gauges.setPadding(0, dp(20), 0, dp(20));
        content.addView(gauges);
        LinearLayout speedPanel = panel(gauges);
        speedText = text("--", 72, Color.WHITE, true);
        speedText.setGravity(Gravity.CENTER);
        speedPanel.addView(speedText, new LinearLayout.LayoutParams(-1, dp(112)));
        TextView speedLabel = text("VEHICLE SPEED  /  KM/H", 10, MUTED, true);
        speedLabel.setGravity(Gravity.CENTER);
        speedPanel.addView(speedLabel);

        LinearLayout rpmPanel = panel(gauges);
        rpmPanel.setGravity(Gravity.CENTER);
        rpmPanel.setMinimumHeight(dp(210));
        TextView rpmLabel = text("ENGINE RPM", 10, MUTED, true);
        rpmPanel.addView(rpmLabel);
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Color.rgb(59, 72, 62));
        track.setCornerRadius(dp(3));
        bar.setBackground(track);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(42), dp(125));
        barParams.topMargin = dp(12);
        rpmPanel.addView(bar, barParams);
        rpmFill = text("", 1, LIME, false);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(LIME);
        fill.setCornerRadius(dp(3));
        rpmFill.setBackground(fill);
        FrameLayout.LayoutParams fillParams = new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM);
        bar.addView(rpmFill, fillParams);
        rpmText = text("--", 30, Color.WHITE, true);
        rpmText.setPadding(0, dp(10), 0, 0);
        rpmPanel.addView(rpmText);
        TextView rpmScale = text("0  —  8,000", 10, MUTED, false);
        rpmPanel.addView(rpmScale);

        LinearLayout gearPanel = panel(content);
        gearPanel.setOrientation(LinearLayout.HORIZONTAL);
        gearPanel.setGravity(Gravity.CENTER_VERTICAL);
        TextView gearLabel = text("CURRENT GEAR", 11, MUTED, true);
        gearPanel.addView(gearLabel, new LinearLayout.LayoutParams(0, -2, 1));
        gearText = text("--", 32, Color.WHITE, true);
        gearPanel.addView(gearText);
        TextView gearNote = text("Not confirmed for model year 2024", 10, MUTED, false);
        gearNote.setPadding(0, dp(8), 0, 0);
        content.addView(gearNote);

        readButton = button("START ACTIVE READS", true);
        readButton.setEnabled(false);
        LinearLayout.LayoutParams readParams = new LinearLayout.LayoutParams(-1, dp(52));
        readParams.topMargin = dp(18);
        content.addView(readButton, readParams);
        TextView safety = text("Passive listening by default. Active reads send only standard RPM and speed requests. Test while parked.", 11, MUTED, false);
        safety.setPadding(0, dp(9), 0, dp(18));
        content.addView(safety);
        readButton.setOnClickListener(view -> toggleReads());

        appRoot = new FrameLayout(this);
        appRoot.setBackgroundColor(BACKGROUND);
        LinearLayout appLayout = new LinearLayout(this);
        appLayout.setOrientation(LinearLayout.VERTICAL);
        appRoot.addView(appLayout, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appRoot.setOnApplyWindowInsetsListener((view, insets) -> {
                DisplayCutout cutout = insets.getDisplayCutout();
                appLayout.setPadding(
                        cutout == null ? 0 : cutout.getSafeInsetLeft(),
                        cutout == null ? 0 : cutout.getSafeInsetTop(),
                        cutout == null ? 0 : cutout.getSafeInsetRight(),
                        cutout == null ? 0 : cutout.getSafeInsetBottom());
                return insets;
            });
        }

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(10), dp(8), dp(10), dp(8));
        navigation.setBackgroundColor(PANEL);
        appLayout.addView(navigation, new LinearLayout.LayoutParams(-1, dp(60)));
        dashboardTab = button("DRIVE", true);
        logsTab = button("LOGS", false);
        configurationTab = button("CONFIG", false);
        debugTab = button("DEBUG", false);
        destinationTab = button("DESTINATION", false);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        tabParams.setMargins(dp(3), 0, dp(3), 0);
        navigation.addView(dashboardTab, new LinearLayout.LayoutParams(tabParams));
        navigation.addView(logsTab, new LinearLayout.LayoutParams(tabParams));
        navigation.addView(configurationTab, new LinearLayout.LayoutParams(tabParams));
        navigation.addView(debugTab, new LinearLayout.LayoutParams(tabParams));
        navigation.addView(destinationTab, new LinearLayout.LayoutParams(tabParams));
        for (Button tab : new Button[]{dashboardTab, logsTab, configurationTab, debugTab, destinationTab}) {
            tab.setTextSize(10);
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setPadding(dp(2), 0, dp(2), 0);
            tab.setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        dashboardTab.setOnClickListener(view -> showPage(PAGE_DASHBOARD));
        logsTab.setOnClickListener(view -> showPage(PAGE_LOGS));
        configurationTab.setOnClickListener(view -> showPage(PAGE_CONFIGURATION));
        debugTab.setOnClickListener(view -> showPage(PAGE_DEBUG));
        destinationTab.setOnClickListener(view -> showPage(PAGE_DESTINATION));

        pageContainer = new FrameLayout(this);
        appLayout.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        dashboardPage = page;
        logsPage = buildLogsPage();
        configurationPage = buildConfigurationPage();
        destinationPanel = new DestinationPanel(this);
        debugTransport = new DebugPanel.Transport() {
            @Override public boolean ready() {
                return !active && (connected || (simulatorClientConnected && (worker == null || !worker.isAlive())));
            }

            @Override public boolean physical() { return connected; }

            @Override public int send(DiagnosticSignal signal) throws Exception {
                if (connected && session != null) {
                    if (!session.readOnce(signal)) throw new IOException("CAN reader is busy");
                    return -1;
                }
                return simulationBridge.sendFrame(signal.request(0));
            }

            @Override public void holdNormalMode(boolean enabled) {
                if (session != null) session.requestDiagnosticMode(enabled);
            }

            @Override public void log(String message) { addLog(message); }
        };
        debugPanel = new DebugPanel(this, diagnosticSignals, debugTransport);
        shiftAlertOverlay = buildShiftAlertOverlay();
        appRoot.addView(shiftAlertOverlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(appRoot);
        appRoot.requestApplyInsets();
        showPage(getIntent().getBooleanExtra("destination_panel", false) ? PAGE_DESTINATION : PAGE_DASHBOARD);
        updateShiftAlert();
    }

    private View buildLogsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(12));
        page.setBackgroundColor(BACKGROUND);

        TextView title = text("CAN DIAGNOSTICS", 22, Color.WHITE, true);
        title.setPadding(dp(4), dp(4), 0, dp(10));
        page.addView(title);
        LinearLayout diagnostics = panel(page);
        receiveText = text("RX frames    0", 11, Color.WHITE, false);
        diagnostics.addView(receiveText);
        transmitText = text("TX requests  0", 11, Color.WHITE, false);
        transmitText.setPadding(0, dp(6), 0, 0);
        diagnostics.addView(transmitText);
        lastFrameText = text("Last frame    —", 11, Color.WHITE, false);
        lastFrameText.setPadding(0, dp(6), 0, 0);
        diagnostics.addView(lastFrameText);

        TextView logHeading = text("USB / CAN EVENT LOG", 11, MUTED, true);
        logHeading.setPadding(dp(4), dp(16), 0, dp(7));
        page.addView(logHeading);
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.rgb(19, 25, 22));
        logScroll.setFillViewport(true);
        logText = text("Waiting for adapter...", 10, Color.rgb(209, 218, 209), false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.addView(logText);
        page.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private View buildConfigurationPage() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(22));
        page.addView(content);

        TextView title = text("CONFIGURATION", 22, Color.WHITE, true);
        title.setPadding(dp(4), dp(4), 0, dp(10));
        content.addView(title);
        LinearLayout settings = panel(content);
        settings.addView(text("RPM SHIFT THRESHOLD", 12, MUTED, true));

        LinearLayout thresholdRow = new LinearLayout(this);
        thresholdRow.setGravity(Gravity.CENTER_VERTICAL);
        thresholdRow.setPadding(0, dp(10), 0, dp(10));
        settings.addView(thresholdRow);
        thresholdInput = new EditText(this);
        thresholdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        thresholdInput.setSingleLine(true);
        thresholdInput.setTextColor(Color.WHITE);
        thresholdInput.setTextSize(24);
        thresholdInput.setSelectAllOnFocus(true);
        thresholdInput.setText(String.valueOf(rpmThreshold));
        thresholdInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LIME));
        thresholdRow.addView(thresholdInput, new LinearLayout.LayoutParams(0, dp(58), 1));
        TextView units = text("RPM", 14, LIME, true);
        units.setPadding(dp(14), 0, dp(8), 0);
        thresholdRow.addView(units);
        Button saveThreshold = button("SAVE THRESHOLD", true);
        settings.addView(saveThreshold, new LinearLayout.LayoutParams(-1, dp(48)));
        saveThreshold.setOnClickListener(view -> saveRpmThreshold());
        LinearLayout vehicleSettings = panel(content);
        vehicleSettings.addView(text("MODEL YEAR", 12, MUTED, true));
        modelYearInput = new EditText(this);
        modelYearInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        modelYearInput.setSingleLine(true);
        modelYearInput.setSelectAllOnFocus(true);
        modelYearInput.setTextSize(24);
        modelYearInput.setTextColor(Color.WHITE);
        modelYearInput.setText(String.valueOf(modelYear));
        modelYearInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LIME));
        vehicleSettings.addView(modelYearInput, new LinearLayout.LayoutParams(-1, dp(58)));
        vehicleSettings.addView(text("TRANSMISSION", 12, MUTED, true));
        RadioGroup transmission = new RadioGroup(this);
        transmission.setOrientation(LinearLayout.VERTICAL);
        manualChoice = new RadioButton(this);
        manualChoice.setId(View.generateViewId());
        manualChoice.setText("Manual");
        manualChoice.setTextColor(Color.WHITE);
        RadioButton automaticChoice = new RadioButton(this);
        automaticChoice.setId(View.generateViewId());
        automaticChoice.setText("Automatic");
        automaticChoice.setTextColor(Color.WHITE);
        transmission.addView(manualChoice);
        transmission.addView(automaticChoice);
        transmission.check(manualTransmission ? manualChoice.getId() : automaticChoice.getId());
        vehicleSettings.addView(transmission);
        modelYearSummary = text(vehicleSummary(), 12, MUTED, false);
        vehicleSettings.addView(modelYearSummary);
        Button saveYear = button("SAVE VEHICLE CONFIGURATION", true);
        vehicleSettings.addView(saveYear, new LinearLayout.LayoutParams(-1, dp(48)));
        saveYear.setOnClickListener(view -> saveModelYear());
        LinearLayout energy = panel(content);
        energy.addView(text("ENERGY", 12, MUTED, true));
        CheckBox screenAwake = new CheckBox(this);
        screenAwake.setText("Keep screen awake");
        screenAwake.setTextColor(Color.WHITE);
        screenAwake.setChecked(keepScreenAwake);
        energy.addView(screenAwake);
        screenAwake.setOnCheckedChangeListener((button, enabled) -> {
            keepScreenAwake = enabled;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled).apply();
            applyScreenAwake();
        });
        batteryStatus = text("", 12, MUTED, false);
        energy.addView(batteryStatus);
        Button batteryExemption = button("BATTERY EXEMPTION", false);
        energy.addView(batteryExemption, new LinearLayout.LayoutParams(-1, dp(48)));
        batteryExemption.setOnClickListener(view -> requestBatteryExemption());
        refreshEnergyStatus();
        return page;
    }

    private void applyScreenAwake() {
        if (keepScreenAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void refreshEnergyStatus() {
        if (batteryStatus != null && powerManager != null) {
            batteryStatus.setText(powerManager.isIgnoringBatteryOptimizations(getPackageName())
                    ? "Battery optimization: exempt" : "Battery optimization: enabled");
        }
    }

    private void requestBatteryExemption() {
        Intent intent = powerManager.isIgnoringBatteryOptimizations(getPackageName())
                ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException unavailable) {
                addLog("Battery optimization settings unavailable on this device");
            }
        }
    }

    private void updateSessionWakeLock() {
        if (sessionWakeLock == null) return;
        boolean required = !destroyed && (connected || simulatorClientConnected);
        if (!required) {
            if (sessionWakeLock.isHeld()) sessionWakeLock.release();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!sessionWakeLock.isHeld() || now - wakeLockRenewedAt >= 60000) {
            sessionWakeLock.acquire(120000);
            wakeLockRenewedAt = now;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        applyScreenAwake();
        refreshEnergyStatus();
        if (destinationPanel != null) destinationPanel.resume();
    }

    @Override protected void onPause() {
        if (destinationPanel != null) destinationPanel.pause();
        super.onPause();
    }

    public void startNavigation(NavigationClient.Destination destination) {
        pendingDestination = destination;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            List<String> permissions = new ArrayList<>();
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) permissions.add(android.Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(permissions.toArray(new String[0]), NAVIGATION_PERMISSION);
            return;
        }
        beginNavigation();
    }

    private void beginNavigation() {
        NavigationClient.Destination destination = pendingDestination;
        pendingDestination = null;
        if (destination == null) return;
        Intent intent = new Intent(this, NavigationService.class).putExtra("name", destination.name)
                .putExtra("latitude", destination.point.latitude).putExtra("longitude", destination.point.longitude);
        startForegroundService(intent);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != NAVIGATION_PERMISSION) return;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) beginNavigation();
        else {
            pendingDestination = null;
            android.widget.Toast.makeText(this, "Precise location is required for guidance", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void saveModelYear() {
        int selectedYear;
        try {
            selectedYear = Integer.parseInt(modelYearInput.getText().toString().trim());
            if (selectedYear < 1989 || selectedYear > 2100) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            modelYearInput.setError("Enter a model year from 1989 to 2100");
            return;
        }
        try {
            boolean selectedManual = manualChoice.isChecked();
            List<DiagnosticSignal> filtered = loadDiagnosticCatalogue(selectedYear, selectedManual);
            debugPanel.close();
            modelYear = selectedYear;
            manualTransmission = selectedManual;
            DashboardTelemetry.configure(rpmThreshold, modelYear);
            diagnosticSignals.clear();
            diagnosticSignals.addAll(filtered);
            debugPanel = new DebugPanel(this, diagnosticSignals, debugTransport);
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putInt(KEY_MODEL_YEAR, modelYear)
                    .putBoolean(KEY_MANUAL_TRANSMISSION, manualTransmission).apply();
            modelYearInput.setError(null);
            modelYearInput.clearFocus();
            modelYearSummary.setText(vehicleSummary());
            addLog("Model year " + modelYear + ": " + diagnosticSignals.size() + " filtered diagnostic signals");
        } catch (IOException | JSONException error) {
            modelYearInput.setError("Unable to load the diagnostic catalogue");
            addLog("Model year catalogue error: " + error.getMessage());
        }
    }

    private RpmEdgeView buildShiftAlertOverlay() {
        return new RpmEdgeView(this);
    }

    private String vehicleSummary() {
        return String.format(Locale.US, "%d / %s / %d signals", modelYear,
                manualTransmission ? "Manual" : "Automatic", diagnosticSignals.size());
    }

    private List<DiagnosticSignal> loadDiagnosticCatalogue(int year, boolean manual) throws IOException, JSONException {
        try (java.io.InputStream mazda = getAssets().open("default.json");
                java.io.InputStream standard = getAssets().open("discovered_obd.json")) {
            return DiagnosticCatalog.load(mazda, year, manual, standard);
        }
    }

    private void showPage(int page) {
        View selected;
        if (page == PAGE_LOGS) selected = logsPage;
        else if (page == PAGE_CONFIGURATION) selected = configurationPage;
        else if (page == PAGE_DEBUG) selected = debugPanel;
        else if (page == PAGE_DESTINATION) selected = destinationPanel;
        else selected = dashboardPage;
        pageContainer.removeAllViews();
        pageContainer.addView(selected, new FrameLayout.LayoutParams(-1, -1));
        setTabSelected(dashboardTab, page == PAGE_DASHBOARD);
        setTabSelected(logsTab, page == PAGE_LOGS);
        setTabSelected(configurationTab, page == PAGE_CONFIGURATION);
        setTabSelected(debugTab, page == PAGE_DEBUG);
        setTabSelected(destinationTab, page == PAGE_DESTINATION);
        if (page == PAGE_LOGS && logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void setTabSelected(Button tab, boolean selected) {
        tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                selected ? LIME : Color.rgb(71, 86, 75)));
        tab.setTextColor(selected ? BACKGROUND : Color.WHITE);
    }

    private void saveRpmThreshold() {
        try {
            int threshold = Integer.parseInt(thresholdInput.getText().toString().trim());
            if (threshold < MIN_RPM_THRESHOLD || threshold > MAX_RPM_THRESHOLD) {
                thresholdInput.setError("Enter 1000 to 8000 RPM");
                return;
            }
            rpmThreshold = threshold;
            DashboardTelemetry.configure(rpmThreshold, modelYear);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putInt(KEY_RPM_THRESHOLD, rpmThreshold).apply();
            thresholdInput.setError(null);
            thresholdInput.clearFocus();
            updateShiftAlert();
        } catch (NumberFormatException error) {
            thresholdInput.setError("Enter 1000 to 8000 RPM");
        }
    }

    private void updateShiftAlert() {
        if (shiftAlertOverlay == null) return;
        boolean fresh = rpmUpdatedAt != 0 && SystemClock.elapsedRealtime() - rpmUpdatedAt <= 3000;
        shiftAlertOverlay.setProgress(RpmAlert.progress(latestRpm, rpmThreshold, fresh));
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackgroundColor(PANEL);
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(horizontal ? 0 : -1, -2, horizontal ? 1 : 0);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        parent.addView(panel, params);
        return panel;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(primary ? BACKGROUND : Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? LIME : Color.rgb(71, 86, 75)));
        return button;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private boolean isCanable(UsbDevice candidate) {
        return candidate != null && candidate.getVendorId() == 0x1D50 && candidate.getProductId() == 0x606F;
    }

    private void findAdapter() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice candidate : devices.values()) {
            if (!isCanable(candidate)) continue;
            device = candidate;
            addLog("Found CANable: " + candidate.getDeviceName());
            showAdapter();
            return;
        }
        device = null;
        showAdapter();
    }

    private void showAdapter() {
        if (device == null) {
            adapterText.setText("PC simulator via ADB · no CANable connected");
            connectButton.setText(R.string.connect_canable);
            connectButton.setEnabled(false);
            readButton.setEnabled(simulatorClientConnected);
            readButton.setText(active ? "STOP ACTIVE READS" : "START ACTIVE READS");
            setConnection(simulatorClientConnected ? "PC SIMULATOR CONNECTED" : "SIMULATOR READY",
                    simulatorClientConnected);
            return;
        }
        adapterText.setText(String.format(Locale.US, "USB %04X:%04X  /  %d interface(s)", device.getVendorId(), device.getProductId(), device.getInterfaceCount()));
        connectButton.setText(connected ? "DISCONNECT CANABLE" : "OPEN USB SESSION");
        connectButton.setEnabled(true);
        setConnection(connected ? (active ? "CAN ACTIVE" : "PASSIVE LISTENING") : "CANABLE DETECTED", connected);
    }

    private void onConnectClicked() {
        if (connected) {
            stopSession();
            return;
        }
        findAdapter();
        if (device == null) {
            addLog("CANable not found. Check USB-C OTG and adapter power.");
            return;
        }
        if (usbManager.hasPermission(device)) startSession();
        else requestUsbPermission();
    }

    private void requestUsbPermission() {
        if (device == null) return;
        addLog("Requesting Android USB permission");
        Intent permissionIntent = new Intent(USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, permissionIntent, flags);
        usbManager.requestPermission(device, pending);
    }

    private void startSession() {
        if (debugPanel.busy()) return;
        if (worker != null && worker.isAlive()) return;
        if (device == null || !usbManager.hasPermission(device)) {
            addLog("USB permission is required before connecting");
            return;
        }
        session = new GsUsb(usbManager, device, this);
        worker = new Thread(session, "gs-usb-can");
        worker.start();
    }

    @SuppressLint("SetTextI18n")
    private void stopSession() {
        debugPanel.stop("CAN session stopped");
        uiHandler.removeCallbacks(simulatorPoll);
        if (session != null) session.stop();
        session = null;
        active = false;
        pendingSimulatorSequence = -1;
        connected = false;
        readButton.setEnabled(simulatorClientConnected);
        readButton.setText("START ACTIVE READS");
        if (device != null) {
            connectButton.setText("OPEN USB SESSION");
            connectButton.setEnabled(true);
        }
        if (simulatorClientConnected && device == null) {
            setConnection("PC SIMULATOR CONNECTED", true);
        } else {
            setConnection(device == null ? "CANABLE NOT CONNECTED" : "CANABLE DETECTED", false);
        }
    }

    private void toggleReads() {
        if (debugPanel.busy()) return;
        if (!connected && simulatorClientConnected) {
            if (active) {
                stopSimulatorReads("Simulator active reads stopped by user");
            } else {
                active = true;
                readButton.setText("STOP ACTIVE READS");
                setConnection("SIMULATOR ACTIVE", true);
                addLog("Simulator active OBD reads enabled; no physical CAN output");
                uiHandler.removeCallbacks(simulatorPoll);
                uiHandler.post(simulatorPoll);
            }
            return;
        }
        if (session == null || !connected) return;
        active = !active;
        session.requestActive(active);
        readButton.setText(active ? "STOP ACTIVE READS" : "START ACTIVE READS");
        setConnection(active ? "CAN ACTIVE" : "PASSIVE LISTENING", true);
        addLog(active ? "Active reads enabled by user" : "Active reads stopped by user");
    }

    private void setConnection(String value, boolean ready) {
        updateSessionWakeLock();
        DashboardTelemetry.setState(value);
        connectionText.setText(value);
        connectionText.setTextColor(ready ? LIME : MUTED);
    }

    private void onSimulationConnectionChanged(boolean linkConnected) {
        simulatorClientConnected = linkConnected;
        addLog(linkConnected ? "PC simulator connected; simulated frames only" : "PC simulator disconnected");
        if (!connected) {
            if (linkConnected) {
                adapterText.setText("ADB simulator link · physical CAN is not used");
                readButton.setEnabled(true);
                readButton.setText(active ? "STOP ACTIVE READS" : "START ACTIVE READS");
                setConnection(active ? "SIMULATOR ACTIVE" : "PC SIMULATOR CONNECTED", true);
            } else {
                debugPanel.stop("Simulator disconnected");
                if (active) stopSimulatorReads("Simulator link closed; active reads stopped");
                readButton.setEnabled(false);
                showAdapter();
            }
        }
    }

    private void stopSimulatorReads(String reason) {
        active = false;
        pendingSimulatorSequence = -1;
        uiHandler.removeCallbacks(simulatorPoll);
        readButton.setText("START ACTIVE READS");
        readButton.setEnabled(simulatorClientConnected);
        setConnection(simulatorClientConnected ? "PC SIMULATOR CONNECTED" : "SIMULATOR READY",
                simulatorClientConnected);
        addLog(reason);
    }

    private void addLog(String message) {
        String line = timeFormat.format(new Date()) + "  " + message;
        logs.addLast(line);
        while (logs.size() > 80) logs.removeFirst();
        if (logText == null) return;
        StringBuilder text = new StringBuilder();
        for (String item : logs) text.append(item).append('\n');
        logText.setText(text.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String hex(byte[] data) {
        StringBuilder value = new StringBuilder();
        for (byte item : data) value.append(String.format(Locale.US, "%02X ", item & 0xFF));
        return value.toString().trim();
    }

    @Override public void onState(String state, boolean isConnected, boolean isActive) {
        runOnUiThread(() -> {
            connected = isConnected;
            active = isActive;
            if (!isConnected) debugPanel.stop("CAN disconnected");
            setConnection(state.toUpperCase(Locale.US), connected);
            readButton.setEnabled(connected);
            readButton.setText(active ? "STOP ACTIVE READS" : "START ACTIVE READS");
            connectButton.setText(connected ? "DISCONNECT CANABLE" : device == null ? "CONNECT CANABLE" : "OPEN USB SESSION");
            connectButton.setEnabled(device != null);
        });
    }

    @Override public void onFrame(CanFrame frame) {
        runOnUiThread(() -> displayFrame(frame, false));
    }

    @Override public void onReply(DiagnosticReply reply) {
        runOnUiThread(() -> {
            debugPanel.acceptReply(reply, false);
            addLog(String.format(Locale.US, "RX ISO-TP 0x%03X  %s", reply.canId, hex(reply.payload)));
        });
    }

    private void onSimulatedFrame(CanFrame frame) {
        runOnUiThread(() -> {
            if (connected) {
                addLog("Rejected PC simulation frame while a physical CAN session is open");
                return;
            }
            displayFrame(frame, true);
        });
    }

    private void displayFrame(CanFrame frame, boolean simulated) {
        DashboardTelemetry.accept(frame, SystemClock.elapsedRealtime(), simulated);
        debugPanel.accept(frame, simulated);
        receivedFrames++;
        receiveText.setText(String.format(Locale.US, simulated ? "SIM frames   %d" : "RX frames    %d", receivedFrames));
        lastFrameText.setText(String.format(Locale.US, "%s 0x%03X  [%d]  %s",
                simulated ? "SIM" : "Last frame", frame.canId, frame.data.length, hex(frame.data)));
        Double rpm = Obd.decode(frame, Obd.RPM);
        Double speed = Obd.decode(frame, Obd.SPEED);
        if (rpm != null) {
            rpmUpdatedAt = SystemClock.elapsedRealtime();
            latestRpm = rpm;
            rpmText.setText(String.format(Locale.US, "%.0f", rpm));
            updateShiftAlert();
            FrameLayout.LayoutParams fillParams = (FrameLayout.LayoutParams) rpmFill.getLayoutParams();
            fillParams.height = Math.max(dp(2), (int) (dp(125) * Math.min(rpm, 8000) / 8000));
            rpmFill.setLayoutParams(fillParams);
            addLog(String.format(Locale.US, "%s OBD response: engine speed %.0f rpm",
                    simulated ? "SIM" : "", rpm));
        } else if (speed != null) {
            speedUpdatedAt = SystemClock.elapsedRealtime();
            speedText.setText(String.format(Locale.US, "%.0f", speed));
            addLog(String.format(Locale.US, "%s OBD response: vehicle speed %.0f km/h",
                    simulated ? "SIM" : "", speed));
        } else {
            addLog(String.format(Locale.US, "%s 0x%03X  [%d]  %s",
                    simulated ? "SIM RX" : "RX", frame.canId, frame.data.length, hex(frame.data)));
        }
    }

    @Override public void onLog(String message) {
        runOnUiThread(() -> {
            addLog(message);
            if (message.startsWith("TX ")) {
                transmittedFrames++;
                transmitText.setText(String.format(Locale.US, "TX requests  %d", transmittedFrames));
            }
            if (message.startsWith("ERROR: ")) setConnection(message.substring(7).toUpperCase(Locale.US), false);
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("destination_panel", false)) showPage(PAGE_DESTINATION);
        UsbDevice attached = getUsbDevice(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && isCanable(attached)) {
            device = attached;
            addLog("CANable attachment detected");
            showAdapter();
            requestUsbPermission();
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        updateSessionWakeLock();
        DashboardTelemetry.clear("Phone dashboard closed");
        debugPanel.close();
        destinationPanel.close();
        uiHandler.removeCallbacks(expireValues);
        uiHandler.removeCallbacks(simulatorPoll);
        if (session != null) session.stop();
        if (simulationBridge != null) simulationBridge.close();
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }
}