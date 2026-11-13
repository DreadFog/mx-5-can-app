# MX-5 Dashboard for Android

Native Android app for a 2024 Mazda MX-5 ND and CANable `gs_usb` adapter. It connects directly to the adapter over USB host/OTG; no companion service is required.

## What It Does

- Detects the CANable USB device (`1D50:606F`) and requests Android USB permission.
- Configures the adapter for 500 kbit/s classic CAN and opens in listen-only mode.
- Starts no CAN data transmission until the user explicitly taps **Start active reads**.
- Active mode alternates the standard OBD-II engine RPM (`01 0C`) and vehicle speed (`01 0D`) read requests at up to 10 requests per second total (nominally 5 Hz per PID). Both responses have been verified on this vehicle while stationary.
- Shows speed, RPM with a bottom-up 0-8,000 bar, and an unavailable gear field. No numeric gear signal for model year 2024 has been verified.
- Separates the Dashboard, Logs, Configuration, Debug, and Destination pages; CAN counters and the USB/CAN event log are kept on Logs.
- Provides a persistent shift-up threshold (default 6,000 RPM, configurable from 1,000 to 8,000 RPM). Transparent edge lighting gradually brightens from yellow at 75% of the threshold through orange at 90%, reaching bright red at or above the threshold. The dashboard stays visible and interactive. Transitions are smoothed over 100 ms; lighting fades with falling RPM and clears when the reading expires.
- Provides USB/CAN diagnostics: adapter VID/PID, interface and endpoint addresses, firmware/hardware versions, CAN feature flags and clock, RX/TX counts, last raw frame, transmit confirmations, timestamps, and recent errors.
- Stops on USB errors, missing transmit confirmation, or CAN bus-off. Measurements expire after three seconds.

## PC Simulator

For car-free development, keep the phone connected to the computer over USB for ADB, open the app, and run `python3 tools/can_simulator.py` from the repository root. Select the authorized ADB transport and connect. The panel loads all UDS signals from the root `default.json`, creates synthetic CAN response frames, and forwards them over `adb forward` to the app's local socket. Simulated frames use the regular display decoder and are marked `SIM` in Logs. **Start active reads** on the app to send OBD queries back to the PC; the simulator answers from its RPM/speed controls. Slider updates do not block the panel while reads are active. No frame reaches the USB CAN adapter or vehicle bus. The panel displays an event log and writes persistent diagnostics to `~/.local/state/mx5-can-simulator.log`. Wireless debugging may remain enabled; select the USB transport in the PC panel when both appear.

The host-side encoder tests run with `python3 -m unittest discover -s tools -v` and require Python 3 plus Tkinter, but no external Python packages.

## Debug Values and Live Polling

Debug lists standard OBD RPM/speed and the UDS signals matching the configured model year from the packaged `default.json` catalogue. Each row displays its request/response IDs, last value, raw response bytes, CAN/SIM source, and FRESH/STALE status. The refresh icon sends one diagnostic request; the Live checkbox polls that value with a 100 ms target interval. The pause icon stops all Debug reads. Live remains selected when switching pages, but never resumes automatically after a disconnect or app restart.

Set Model Year and Transmission (Manual/Automatic) in Configuration, then save the vehicle configuration. Defaults are 2024 and Manual; both settings survive app restarts. Saving stops Debug reads, clears its last values/discovery result, and rebuilds its catalogue; 2024 exposes 34 signals in Manual or 38 in Automatic, including RPM/speed. Manual excludes engine-independent automatic-transmission DIDs `17B3`, `1E1C`, `1E24`, `1EC7` and `1EE4` when addressed to `7E1`. Other transmission/clutch definitions are not blindly hidden. Catalogue `filter` rules use inclusive alternatives: `from` means that year or newer, `to` means that year or older, and `years` lists additional matching years. Commands without a `filter` remain available. `dbg` and `dbgfilter` are exploratory metadata, not vehicle-year exclusion rules; matching the profile does not establish ECU support. The PC simulator retains its complete 91-signal catalogue.

### Engine OBD PID Discovery

Use the search icon above the Debug values when Dashboard polling and Debug Live reads are stopped. A physical scan requires parked-vehicle confirmation. The app requests only the standard Service 01 support blocks from the engine (`7E0` requests, `7E8` replies). It starts with PID `00`, follows the continuation bit through successive 32-PID blocks, and stops at the first final block or after `E0` (maximum eight requests). Replies are serialized, with a timeout for each pending request; no unknown Mazda DIDs, ECU writes, or automatic polling of discovered PIDs are issued. The pause icon cancels discovery. CAN returns to listen-only after outstanding transmission/response handling completes.

The result lists advertised standard PIDs and the number of blocks read, labelled CAN or SIM. A refusal, disconnect, invalid reply or timeout leaves a Partial result; this does not mean that every remaining PID is unsupported. Results are session-only and reset on restart/profile change. This basic framework scans only the engine, not every ECU, and does not yet provide decoders/buttons for the complete discovered set. The simulator answers support queries for its standard RPM/speed values. A test on the stationary 2024 manual MX-5 returned 52 advertised PIDs through blocks `00` to `A0`, including `42` (ECU voltage), `49`/`4A` (accelerator), `A4` (gear/ratio) and `A6` (odometer); advertised support is not a validation of each returned measurement.

Stop Dashboard active reads before using Debug. Debug and Dashboard polling are mutually exclusive. Debug targets 10 Hz per selected value; the existing Dashboard still alternates RPM/speed at 10 requests/s total. Debug serializes requests rather than building a backlog, so slower ECU/USB responses and many selected values reduce the achieved rate. Negative replies, invalid responses and timeouts stop the affected Live signal; disconnects stop all selections. Last values remain visible and become stale after three seconds.

On the car, every read-once/Live activation requires confirmation while parked. Some catalogue definitions target different model years and have not been verified on the 2024 MX-5. Only standard OBD/UDS read requests (services 01/22) are sent, never ECU writes or actuator commands. Single-frame diagnostic replies are supported; ISO-TP multi-frame replies are not assembled and time out. The CANable returns to listen-only when no Debug Live selection or pending request remains. Vehicle-side Debug/Live behavior still needs physical validation; simulator RPM/speed Live has been measured at approximately 10 Hz per value on the Pixel.

## GPS and Destination

The **Destination** panel searches addresses and places through Nominatim and displays an OpenStreetMap map. Select a search result or long-press the map, then tap **Start guidance** while parked. The selected destination is saved locally, but guidance never starts automatically. Grant precise location permission and enable phone location. The stop icon or the foreground navigation notification stops guidance. Selecting another destination does not change the active route until Start guidance is tapped again.

OSRM computes a driving route. A location foreground service follows it independently of the phone activity, including while the activity is backgrounded or destroyed. The service does not restart automatically after process termination. It waits for a recent fix accurate to 50 m or better, hides instructions after 10 seconds without a reliable fix, and recalculates when more than 60 m off route. Failed requests retry with subsequent location updates, no more often than every 15 seconds.

The turn tile uses rally-style light, medium, and sharp left/right arrows, a dedicated roundabout sign with the exit number when available, a U-turn arrow, and a destination flag. Distances follow route geometry and round upward in 50 m increments (51 m displays as 100 m). These are OSRM road-navigation instructions, not surveyed rally pace notes. **The small speed indicator uses only the existing vehicle OBD/CAN values**, or explicitly labelled SIM values during simulator testing. GPS speed is never acquired or displayed; absent or expired vehicle speed shows `--`.

Internet is required for search, initial routing, recalculation, and uncached map tiles. A loaded route can continue offline while the vehicle stays on that route. Nominatim receives the typed search, OSRM receives start/destination coordinates, and the OSM tile server receives map tile requests. Searches are explicit (no autocomplete), with one in-flight request and at least one second between searches. Map attribution is displayed. Public Nominatim and OSRM demo servers have usage limits and no availability guarantee; use appropriately configured production/self-hosted services before distributing this prototype. Offline map downloads and voice guidance are not implemented.

## Experimental Android Auto

Both debug and release APKs include the Car App Library service, labelled **MX-5 Experimental**, and its navigation surface. Android Auto support is no longer conditional on the debug source set. This remains a prototype, not an officially approved vehicle-dashboard category. Basic turn guidance does not establish compliance with all Android Auto navigation app requirements or guarantee acceptance by a real head unit. It may interfere with another navigation app. Test only while parked.

The Auto screen draws the next maneuver in the left half and RPM in the right half, with a small vehicle speed indicator at the bottom right. Select and start the destination in the phone's Destination panel. With no active route, the left tile shows navigation status. RPM threshold and edge lighting follow the phone configuration, updating approximately ten times per second. Vehicle values expire after three seconds, with SIM/CAN source shown for fresh measurements. The host controls system UI and available screen area. Phone Debug controls are not projected, and the Auto screen has no CAN transmit controls.

Acquisition still belongs to the phone activity: open the phone dashboard, connect the adapter or PC simulator, and start the desired reads there while parked. The Auto service does not open USB, start polling, or request USB permission. Android may destroy the phone activity in the background; if it does, acquisition stops and the Auto screen loses its values. A standalone foreground acquisition service is not implemented by this prototype.

### Desktop Head Unit Setup on Arch

For a real-car CANable test, use wireless Android Auto: the Pixel's single USB-C port is needed for the CANable in USB host/OTG mode. Wired Android Auto and the direct CANable connection cannot share that port as two separate USB sessions; a normal USB hub does not solve the conflicting host roles.

Install the Linux libraries required by Google's DHU (this step needs `sudo`):

```bash
sudo pacman -S --needed libc++ libc++abi
```

Install the DHU in your user-owned Android SDK if it is not already available:

```bash
sdkmanager --install "extras;google;auto"
```

Build and install the debug APK, choosing a serial from `adb devices -l` if multiple transports are connected:

```bash
cd android
./gradlew assembleDebug
adb -s PIXEL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

In Android Auto settings on the Pixel, tap the version information ten times to enable its developer mode. In the overflow menu choose **Start head unit server**. Ensure **Add new cars to Android Auto** is enabled, keep the phone unlocked, then run on the PC:

```bash
adb -s PIXEL_SERIAL forward tcp:5277 tcp:5277
"$ANDROID_HOME/extras/google/auto/desktop-head-unit" --adb=5277
```

Complete any Android Auto onboarding prompts on the phone. Look for **MX-5 Experimental** in the DHU app launcher. The CAN simulator's port 39082 is independent of the DHU's port 5277, so both can be used together. Test simulated data first; do not operate these controls while driving.

The **Unknown sources** developer option is not a general override for Car App Library apps. Debug/DHU testing and real-vehicle install-source checks differ; a real head unit may reject an ADB-installed app, and Google Play Internal App Sharing may be needed. Do not assume that appearing in Android package discovery means the host has accepted the app. See [Google's real-vehicle testing rules](https://developer.android.com/training/cars/testing#unknown-sources) and the [DHU guide](https://developer.android.com/training/cars/testing/dhu).

Debug builds accept all hosts to permit local testing; do not distribute a debug APK on an untrusted device. Release builds use the Car App Library's default host validator, which requires the host's template-renderer permission. A broader host allowlist, release signing, and Android Auto distribution approval remain separate requirements. The Auto service exposes telemetry to accepted hosts but cannot transmit on CAN.

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
  app/src/main/java/com/mx5/dashboard/ModelYearFilter.java \
  app/src/main/java/com/mx5/dashboard/DashboardTelemetry.java \
  app/src/main/java/com/mx5/dashboard/TransmissionFilter.java \
  app/src/main/java/com/mx5/dashboard/ObdPidDiscovery.java \
  app/src/main/java/com/mx5/dashboard/DiagnosticReply.java \
  app/src/main/java/com/mx5/dashboard/IsoTpReceiver.java \
  app/src/main/java/com/mx5/dashboard/NavigationRoute.java \
  app/src/main/java/com/mx5/dashboard/NavigationState.java \
  tests/ProtocolTest.java
java -cp /tmp/mx5-protocol-test ProtocolTest
```

This also exercises maneuver classification, route progression, arrival, GPS expiry, and 50 m increments. Run the OSRM JSON fixtures and the nonvisual Destination panel lifecycle regression test using `./gradlew :app:testDebugUnitTest`. The lifecycle test switches away from and back to Destination while a route update arrives, verifying that osmdroid overlays survive temporary view detachment and the activity closes cleanly.

The app has been compiled as a debug APK. Hardware USB enumeration, Android's permission dialog, CAN reception, and vehicle responses still require testing with the Pixel 9 and CANable physically connected. The APK is unsigned for release and intended for local debug installation only.