# MX-5 Dashboard for Android

Native Android app for a 2024 Mazda MX-5 ND and CANable `gs_usb` adapter. It connects directly to the adapter over USB host/OTG; no companion service is required.

## What It Does

- Detects the CANable USB device (`1D50:606F`) and requests Android USB permission.
- Configures the adapter for 500 kbit/s classic CAN and opens in listen-only mode.
- Starts no CAN data transmission until the user explicitly taps **Start active reads**.
- Active mode alternates the standard OBD-II engine RPM (`01 0C`) and vehicle speed (`01 0D`) read requests at up to 10 requests per second total (nominally 5 Hz per PID). Both responses have been verified on this vehicle while stationary.
- Shows speed, RPM with a bottom-up 0-8,000 bar, and an unavailable gear field. No numeric gear signal for model year 2024 has been verified.
- Separates the Dashboard, Logs, Configuration, and Debug pages; CAN counters and the USB/CAN event log are kept on Logs.
- Provides a persistent shift-up threshold (default 6,000 RPM, configurable from 1,000 to 8,000 RPM). Transparent edge lighting gradually brightens from yellow at 75% of the threshold through orange at 90%, reaching bright red at or above the threshold. The dashboard stays visible and interactive. Transitions are smoothed over 100 ms; lighting fades with falling RPM and clears when the reading expires.
- Provides USB/CAN diagnostics: adapter VID/PID, interface and endpoint addresses, firmware/hardware versions, CAN feature flags and clock, RX/TX counts, last raw frame, transmit confirmations, timestamps, and recent errors.
- Stops on USB errors, missing transmit confirmation, or CAN bus-off. Measurements expire after three seconds.

## PC Simulator

For car-free development, keep the phone connected to the computer over USB for ADB, open the app, and run `python3 tools/can_simulator.py` from the repository root. Select the authorized ADB transport and connect. The panel loads all UDS signals from the root `default.json`, creates synthetic CAN response frames, and forwards them over `adb forward` to the app's local socket. Simulated frames use the regular display decoder and are marked `SIM` in Logs. **Start active reads** on the app to send OBD queries back to the PC; the simulator answers from its RPM/speed controls. Slider updates do not block the panel while reads are active. No frame reaches the USB CAN adapter or vehicle bus. The panel displays an event log and writes persistent diagnostics to `~/.local/state/mx5-can-simulator.log`. Wireless debugging may remain enabled; select the USB transport in the PC panel when both appear.

The host-side encoder tests run with `python3 -m unittest discover -s tools -v` and require Python 3 plus Tkinter, but no external Python packages.

## Debug Values and Live Polling

Debug lists standard OBD RPM/speed and all 91 UDS signals from the packaged `default.json` catalogue. Each row displays its request/response IDs, last value, raw response bytes, CAN/SIM source, and FRESH/STALE status. The refresh icon sends one diagnostic request; the Live checkbox polls that value with a 100 ms target interval. The pause icon stops all Debug reads. Live remains selected when switching pages, but never resumes automatically after a disconnect or app restart.

Stop Dashboard active reads before using Debug. Debug and Dashboard polling are mutually exclusive. Debug targets 10 Hz per selected value; the existing Dashboard still alternates RPM/speed at 10 requests/s total. Debug serializes requests rather than building a backlog, so slower ECU/USB responses and many selected values reduce the achieved rate. Negative replies, invalid responses and timeouts stop the affected Live signal; disconnects stop all selections. Last values remain visible and become stale after three seconds.

On the car, every read-once/Live activation requires confirmation while parked. Some catalogue definitions target different model years and have not been verified on the 2024 MX-5. Only standard OBD/UDS read requests (services 01/22) are sent, never ECU writes or actuator commands. Single-frame diagnostic replies are supported; ISO-TP multi-frame replies are not assembled and time out. The CANable returns to listen-only when no Debug Live selection or pending request remains. Vehicle-side Debug/Live behavior still needs physical validation; simulator RPM/speed Live has been measured at approximately 10 Hz per value on the Pixel.

## Wireless ADB and Screen Mirroring

Install `scrcpy` on Arch Linux if needed:

```bash
sudo pacman -S scrcpy
```

No `sudo` is needed to run `adb` or `scrcpy`. To pair the Pixel wirelessly, connect the phone and computer to the same trusted Wi-Fi network. On the Pixel, open **Settings → System → Developer options → Wireless debugging**, enable it, then choose **Pair device with pairing code**. In a computer terminal, use the IP address and pairing port shown by that dialog:

```bash
adb pair PIXEL_IP:PAIRING_PORT
```

Enter the pairing code when ADB prompts. Return to the Wireless debugging page and use its main IP address and connection port (these can differ from the pairing port):

```bash
adb connect PIXEL_IP:ADB_PORT
adb devices -l
scrcpy --serial=PIXEL_IP:ADB_PORT --no-audio
```

`scrcpy` opens a live mirror in a desktop window. When the phone is still connected to the computer by USB, `scrcpy --no-audio` can use that USB ADB transport instead; use `adb devices -l` to find its serial and pass it with `--serial`. If both USB and Wi-Fi devices are listed, specify which one to avoid ambiguity.

For CANable use, the Pixel's single USB-C port must be connected to the adapter in host/OTG mode. Pair wireless ADB before unplugging the computer cable, then use the Wi-Fi address and port with `adb connect` and `scrcpy`. If Android Auto changes the Wi-Fi network and the connection drops, put the Pixel and computer back on the same trusted network, check the current address/port again in Wireless debugging, then reconnect. Disable Wireless debugging when finished, especially on networks you do not trust.

## Safety and Hardware

Use the Pixel 9 USB-C port in USB host/OTG mode with a data-capable USB-C OTG adapter. The CANable takes power from the phone; if Android reports excess USB power use, use a powered OTG hub. The exact phone/cable/adapter combination still needs a physical test.

Before connecting the CANable to the phone, disconnect it from any host computer. Only one USB host can own the adapter at a time.

The first USB session configures the adapter's CAN controller to listen-only at 500 kbit/s. This does not send CAN data frames. Active mode is a separate, explicit action and sends only the two standard OBD read requests above. Do not use the app while driving; test while parked in a ventilated area. This is a telemetry prototype, not a safety instrument.

## Build Requirements

- JDK 17
- Android SDK Platform 35 and Build-Tools 35.0.0
- USB host/OTG Android phone for hardware testing

### Arch Linux Host

Install the supported JDK from the Arch repositories:

```bash
sudo pacman -S jdk17-openjdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
```

Install the Android command-line tools into a user-owned SDK directory (for example `$HOME/Android/Sdk`), then configure the environment:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

The JDK package needs `sudo`; the SDK tools and components can be installed as your regular user. Keep these environment variables in your shell configuration if you want them in future terminals.

With Android command-line tools installed, install the SDK components:

```bash
sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
```

If using the newer `android` SDK CLI instead of `sdkmanager`, its package names use slashes:

```bash
android sdk install platforms/android-35 build-tools/35.0.0 platform-tools
```

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK root, then from this directory run:

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected Android device with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Protocol Test

The protocol test exercises recorded RPM/speed responses, request encoding, echo filtering, malformed-frame rejection, shift-light limits, and UDS numeric/bit/signed/negative response decoding:

```bash
mkdir -p /tmp/mx5-protocol-test
javac -d /tmp/mx5-protocol-test \
  app/src/main/java/com/mx5/dashboard/CanFrame.java \
  app/src/main/java/com/mx5/dashboard/Obd.java \
  app/src/main/java/com/mx5/dashboard/RpmAlert.java \
  app/src/main/java/com/mx5/dashboard/DiagnosticSignal.java \
  tests/ProtocolTest.java
java -cp /tmp/mx5-protocol-test ProtocolTest
```

The app has been compiled as a debug APK. Hardware USB enumeration, Android's permission dialog, CAN reception, and vehicle responses still require testing with the Pixel 9 and CANable physically connected. The APK is unsigned for release and intended for local debug installation only.