# MX-5 ND CAN Dashboard for Android

Native Android dashboard for a 2024 Mazda MX-5 ND. The Pixel 9 connects directly to a CANable `gs_usb` adapter over USB host/OTG; no web server, Docker, WSL, or SocketCAN backend is required.

## Features

- Displays vehicle speed, engine RPM and ECU-reported actual gear when supported; gear is never inferred from speed/RPM.
- Starts read-only OBD/UDS polling automatically when an authorized CANable session or simulator connects. Requests are serialized and capped at 10 per second total; the visible Android Auto page selects its own measurement group.
- Provides separate Dashboard, Logs, Configuration, and Debug pages.
- Debug shows the latest decoded and raw OBD/UDS responses with CAN/SIM source and freshness. Each value has a read-once button and a Live checkbox targeting 10 Hz per selected value.
- Configuration stores the model year (default 2024) and transmission (default Manual). Debug applies both filters; 2024 exposes 34 signals in Manual or 38 in Automatic, including standard RPM/speed.
- Configuration also offers **Body Colour**, with seven official 2024 MX-5 ND paint names and a swatch for each. Soul Red Crystal Metallic is the default. The selection is saved immediately and updates the Auto car illustration without restarting; screen colours are approximations, not factory paint specifications.
- Debug can discover the standard OBD PIDs advertised by the engine ECU using a bounded sequence of support-mask queries.
- The configurable RPM shift threshold defaults to 6,000 RPM. Edge lighting fades in from yellow at 75% of the threshold, transitions through orange at 90%, and reaches bright red at or above the threshold. The dashboard remains visible.
- Disconnecting stops automatic reads. The **Automatic dashboard reads** switch at the top of Debug pauses/resumes them, including while Android Auto is visible. The pause remains in effect across phone pages until explicitly re-enabled or the activity restarts.

## Safety and Hardware

Use a data-capable USB-C OTG adapter with the Pixel 9. The CANable draws power from the phone; use a powered OTG hub if Android reports excess USB power. Disconnect the adapter from any computer before connecting it to the phone.

Test while parked, in a well-ventilated area, and do not operate the interface while driving. Connecting a source starts OBD/UDS read requests automatically; there is no Start active reads button. No ECU write or actuator services are sent. Measurements are not a substitute for the vehicle's instruments. RPM, speed and gear expire after three seconds; slower dashboard measurements after ten seconds.

## Build and Install

See [android/README.md](android/README.md) for Arch Linux/JDK/SDK setup, USB permission details, build commands, APK installation, and device-side test notes.

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is intended for local installation, not release distribution. See [can_handoff.md](can_handoff.md) for historical SocketCAN and OBD wiring notes. [default.json](default.json) supplies the Debug catalogue and PC simulator; inclusion does not establish support for every signal on the 2024 vehicle.

## Debug Reads

Open Debug after connecting a CANable session or the PC simulator and turn off **Automatic dashboard reads**. Debug controls become available once any outstanding Dashboard request finishes or times out, even with Android Auto visible. Use the refresh icon to request a value once, or check Live to monitor it at a target of 10 Hz. The pause icon stops all Debug Live reads. Leaving Debug stops its reads but does not re-enable Dashboard polling. Turn **Automatic dashboard reads** back on to stop Debug Live/discovery and resume dashboard telemetry. The switch defaults to on when the phone activity starts; Live selections are not saved across app restarts or reconnections.

The panel includes standard RPM/speed and the UDS catalogue signals matching the configured model year and transmission. Set Manual or Automatic in Configuration and save the vehicle configuration to rebuild Debug; current Debug reads stop and its displayed values are reset. The default is 2024 Manual (34 signals). Manual hides only the known automatic-transmission queries, not every transmission or clutch signal. Values remain visible when stale; negative UDS responses, invalid values, and timeouts are shown instead of inventing measurements. Profile compatibility does not guarantee ECU support. Physical requests require confirmation while parked; no actuator/write services are sent.

### Supported PID Discovery

The search icon at the top of Debug starts engine discovery after active/Live reads are stopped. On the car, confirm only while parked. It sends Service 01 support-mask requests directly to `7E0`, accepts responses from `7E8`, and follows the continuation bit through blocks `00`, `20`, ..., `E0` (at most eight requests). It stops on completion, cancellation, refusal or timeout, then returns to passive mode. Incomplete results are marked Partial, not treated as proof of unsupported PIDs. The pause icon also cancels discovery.

This is discovery of advertised standard PID support, not a Mazda DID scan or automatic reading of all discovered parameters. Results are displayed for the current Debug session and reset on app restart or vehicle-profile change. The PC simulator advertises only its standard RPM/speed PIDs. A stationary test on the 2024 manual vehicle returned 52 PIDs from six support blocks, including `42`, `49`, `4A`, `A4` and `A6`; their actual values still require suitable decoders and verification.

Debug requests are serialized, with one response awaited at a time. The target is 10 Hz per selected value, not a guarantee: slow ECUs or many selections reduce the achieved rate without queuing a backlog. A timeout or negative response stops that signal's Live polling; disconnects stop all Debug reads. Physical CAN returns to listen-only once Dashboard polling is paused, the final pending request finishes and no Live selection remains. Debug Live and Dashboard polling are mutually exclusive; Dashboard uses a 10 requests/s aggregate cap.

## Experimental Android Auto

Debug and release APKs include **MX-5 Experimental**. The main Auto page shows navigation/RPM tiles with oil temperature, fuel volume and actual gear above them. A hamburger action opens a wheel-pressure/temperature view plus coolant, voltage, intake temperature and oil pressure. Changing pages switches the read-only polling group rather than polling both pages. The 2024 profile has no verified compatible wheel signals, so those fields remain unavailable. The phone activity still owns CAN/simulator acquisition; background process destruction stops it. Host acceptance remains experimental. See the [tested personal-use installer workaround](android/README.md#personal-use-install-source-workaround).

See [Android Auto and DHU Setup](android/README.md#experimental-android-auto) for the Arch dependencies, Android Auto developer settings, simulator testing and limitations.

## PC CAN Simulator

Connect the Pixel to the computer over USB with ADB authorized, open the app, then run:

```bash
python3 tools/can_simulator.py
```

Choose the USB or wireless ADB transport and connect. The PC panel provides RPM/speed sliders and Mazda/discovered-OBD controls; frames are forwarded to the app through a local ADB socket. Dashboard requests start automatically on connection and use the current simulator values. Set the actual-gear support flag as well as its value for the gear tile. Slider updates are asynchronous. This mode is entirely simulated and never writes to the CANable or vehicle bus. The panel shows recent events and errors; a persistent diagnostic log is written to `~/.local/state/mx5-can-simulator.log`. Wireless debugging can remain enabled while USB is used for the simulator.

Run the simulator codec tests with:

```bash
python3 -m unittest discover -s tools -v
```

For wireless ADB pairing and live `scrcpy` screen mirroring, see [Wireless ADB and Screen Mirroring](android/README.md#wireless-adb-and-screen-mirroring).