# MX-5 ND CAN Dashboard for Android

Native Android dashboard for a 2024 Mazda MX-5 ND. The Pixel 9 connects directly to a CANable `gs_usb` adapter over USB host/OTG; no web server, Docker, WSL, or SocketCAN backend is required.

## Features

- Displays vehicle speed and engine RPM; gear remains unavailable until a numerical signal is verified.
- Starts in CAN listen-only mode. Active mode alternates standard OBD-II RPM (`01 0C`) and speed (`01 0D`) requests at up to 10 requests per second total.
- Provides separate Dashboard, Logs, Configuration, and Debug pages.
- Debug shows the latest decoded and raw OBD/UDS responses with CAN/SIM source and freshness. Each value has a read-once button and a Live checkbox targeting 10 Hz per selected value.
- The configurable RPM shift threshold defaults to 6,000 RPM. Edge lighting fades in from yellow at 75% of the threshold, transitions through orange at 90%, and reaches bright red at or above the threshold. The dashboard remains visible.
- The CANable disconnects and returns to listen-only when active reads are stopped or the session closes.

## Safety and Hardware

Use a data-capable USB-C OTG adapter with the Pixel 9. The CANable draws power from the phone; use a powered OTG hub if Android reports excess USB power. Disconnect the adapter from any computer before connecting it to the phone.

Test while parked, in a well-ventilated area, and do not operate the interface while driving. Active mode sends only standard OBD read requests; measurements are not a substitute for the vehicle's instruments. Values expire after three seconds without a response.

## Build and Install

See [android/README.md](android/README.md) for Arch Linux/JDK/SDK setup, USB permission details, build commands, APK installation, and device-side test notes.

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is intended for local installation, not release distribution. See [can_handoff.md](can_handoff.md) for historical SocketCAN and OBD wiring notes. [default.json](default.json) supplies the Debug catalogue and PC simulator; inclusion does not establish support for every signal on the 2024 vehicle.

## Debug Reads

Open Debug after connecting a CANable session or the PC simulator. Stop Dashboard active reads first. Use the refresh icon to request a value once, or check Live to monitor it at a target of 10 Hz. The pause icon stops all Debug Live reads. Debug polling continues across pages, but selections are not saved across app restarts or reconnections.

The panel includes standard RPM/speed and the 91 UDS catalogue signals. Values remain visible when stale; negative UDS responses, invalid values, and timeouts are shown instead of inventing measurements. Several catalogue signals target other model years and are not verified on this vehicle. Physical requests require confirmation while parked; no actuator/write services are sent.

Debug requests are serialized, with one response awaited at a time. The target is 10 Hz per selected value, not a guarantee: slow ECUs or many selections reduce the achieved rate without queuing a backlog. A timeout or negative response stops that signal's Live polling; disconnects stop all Debug reads. Physical CAN returns to listen-only once the final pending request finishes and no Live selection remains. Debug Live and Dashboard polling are mutually exclusive; the Dashboard's existing 10 requests/s total cadence is unchanged.

## PC CAN Simulator

Connect the Pixel to the computer over USB with ADB authorized, open the app, then run:

```bash
python3 tools/can_simulator.py
```

Choose the USB or wireless ADB transport and connect. The PC panel provides RPM/speed sliders and controls for all 91 UDS signals in `default.json`; frames are forwarded to the app through a local ADB socket. Slider updates are sent asynchronously so they do not block the panel while active reads are running. The app's **Start active reads** button also works in this mode: the PC simulator answers its OBD requests using the current RPM/speed slider values. This mode is entirely simulated and never writes to the CANable or vehicle bus. The panel shows recent events and errors; a persistent diagnostic log is written to `~/.local/state/mx5-can-simulator.log`. Wireless debugging can remain enabled while USB is used for the simulator.

Run the simulator codec tests with:

```bash
python3 -m unittest discover -s tools -v
```

For wireless ADB pairing and live `scrcpy` screen mirroring, see [Wireless ADB and Screen Mirroring](android/README.md#wireless-adb-and-screen-mirroring).