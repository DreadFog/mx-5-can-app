# MX-5 Dashboard for Android

Native Android app for a 2024 Mazda MX-5 ND and CANable `gs_usb` adapter. It connects directly to the adapter over USB host/OTG; no companion service is required.

## What It Does

- Detects the CANable USB device (`1D50:606F`) and requests Android USB permission.
- Configures the adapter for 500 kbit/s classic CAN and opens in listen-only mode.
- Automatically starts read-only OBD/UDS telemetry once a CANable session is authorized and connected, or the PC simulator connects. There is no Start active reads button; disconnect the source to stop dashboard reads. Test only while parked.
- Serializes dashboard requests with at most one response outstanding and at most 10 requests per second total. The visible Android Auto page selects the request group; without a visible Auto page, the phone uses the main group.
- Shows speed, RPM with a bottom-up 0-8,000 bar, and ECU-reported actual gear when PID `A4` declares it supported. Gear is not estimated from speed/RPM. Numeric gear decoding still requires vehicle-side validation.
- Separates the Dashboard, Logs, Configuration, Debug, and Destination pages; CAN counters and the USB/CAN event log are kept on Logs.
- Provides a persistent shift-up threshold (default 6,000 RPM, configurable from 1,000 to 8,000 RPM). Transparent edge lighting gradually brightens from yellow at 75% of the threshold through orange at 90%, reaching bright red at or above the threshold. The dashboard stays visible and interactive. Transitions are smoothed over 100 ms; lighting fades with falling RPM and clears when the reading expires.
- Provides USB/CAN diagnostics: adapter VID/PID, interface and endpoint addresses, firmware/hardware versions, CAN feature flags and clock, RX/TX counts, last raw frame, transmit confirmations, timestamps, and recent errors.
- Stops on USB errors, missing transmit confirmation, or CAN bus-off. RPM, speed and gear expire after three seconds; slower dashboard measurements expire after ten seconds. Refused, invalid or timed-out dashboard requests back off for 60 seconds.

## PC Simulator

For car-free development, keep the phone connected to the computer over USB for ADB, open the app, and run `python3 tools/can_simulator.py` from the repository root. Select the authorized ADB transport and connect. Dashboard queries start automatically. The panel answers OBD/UDS requests using its Mazda and discovered-OBD controls; set the actual-gear support flag as well as the gear value to display a simulated gear. Simulated frames use the regular display decoder and are marked `SIM`. Slider updates do not block the panel while reads are active. No frame reaches the USB CAN adapter or vehicle bus. The panel displays an event log and writes persistent diagnostics to `~/.local/state/mx5-can-simulator.log`. Wireless debugging may remain enabled; select the USB transport in the PC panel when both appear.

The host-side encoder tests run with `python3 -m unittest discover -s tools -v` and require Python 3 plus Tkinter, but no external Python packages.

## Debug Values and Live Polling

Debug lists standard OBD signals from the matching discovered profile and UDS signals matching the configured model year from the packaged `default.json` catalogue. Each row displays its request/response IDs, last value, raw response bytes, CAN/SIM source, and FRESH/STALE status. Turn off **Automatic dashboard reads** at the top of Debug to suspend Dashboard polling, including when Android Auto is visible. Debug controls become available after any outstanding Dashboard request finishes or times out. The refresh icon sends one diagnostic request; the Live checkbox polls that value with a 100 ms target interval. Leaving Debug stops its Live selections but keeps automatic reads paused. Turn the switch back on to stop Debug Live/discovery and resume Dashboard reads. The pause is activity-local, survives phone-page changes and source reconnections, and defaults to automatic reads enabled when the phone activity restarts. The separate pause icon stops Debug reads; these never restart automatically after disconnection.

Set Model Year and Transmission (Manual/Automatic) in Configuration, then save the vehicle configuration. Defaults are 2024 and Manual; both settings survive app restarts. Saving stops Debug reads, clears its last values/discovery result, and rebuilds its catalogue; 2024 exposes 34 signals in Manual or 38 in Automatic, including RPM/speed. Manual excludes engine-independent automatic-transmission DIDs `17B3`, `1E1C`, `1E24`, `1EC7` and `1EE4` when addressed to `7E1`. Other transmission/clutch definitions are not blindly hidden. Catalogue `filter` rules use inclusive alternatives: `from` means that year or newer, `to` means that year or older, and `years` lists additional matching years. Commands without a `filter` remain available. `dbg` and `dbgfilter` are exploratory metadata, not vehicle-year exclusion rules; matching the profile does not establish ECU support. The PC simulator retains its complete 91-signal catalogue.

### Engine OBD PID Discovery

Use the search icon above the Debug values when Dashboard polling and Debug Live reads are stopped. A physical scan requires parked-vehicle confirmation. The app requests only the standard Service 01 support blocks from the engine (`7E0` requests, `7E8` replies). It starts with PID `00`, follows the continuation bit through successive 32-PID blocks, and stops at the first final block or after `E0` (maximum eight requests). Replies are serialized, with a timeout for each pending request; no unknown Mazda DIDs, ECU writes, or automatic polling of discovered PIDs are issued. The pause icon cancels discovery. CAN returns to listen-only after outstanding transmission/response handling completes.

The result lists advertised standard PIDs and the number of blocks read, labelled CAN or SIM. A refusal, disconnect, invalid reply or timeout leaves a Partial result; this does not mean that every remaining PID is unsupported. Results are session-only and reset on restart/profile change. This basic framework scans only the engine, not every ECU, and does not yet provide decoders/buttons for the complete discovered set. The simulator answers support queries for its standard RPM/speed values. A test on the stationary 2024 manual MX-5 returned 52 advertised PIDs through blocks `00` to `A0`, including `42` (ECU voltage), `49`/`4A` (accelerator), `A4` (gear/ratio) and `A6` (odometer); advertised support is not a validation of each returned measurement.

Debug and Dashboard polling are mutually exclusive, controlled by the **Automatic dashboard reads** switch. Changing that switch never starts a second request while the previous transport request is outstanding. Debug targets 10 Hz per selected value; Dashboard has a 10 requests/s aggregate cap. Both serialize requests rather than building a backlog, so slower ECU/USB responses and many selected values reduce the achieved rate. Negative replies, invalid responses and timeouts stop the affected Debug Live signal; disconnects stop all Debug selections. Last Debug values remain visible and become stale after three seconds.

On the car, every Debug read-once/Live activation requires confirmation while parked; automatic Dashboard reads do not show that confirmation. Some catalogue definitions target different model years and have not been verified on the 2024 MX-5. Only OBD/UDS read requests (services 01/22), plus ISO-TP flow control when needed, are sent, never ECU writes or actuator commands. The CANable returns to listen-only while automatic Dashboard reads are paused and no Debug Live selection or pending request remains. Vehicle-side Debug/Live behavior still needs physical validation; simulator RPM/speed Live has been measured at approximately 10 Hz per value on the Pixel.

## Body Colour

In the phone's **Configuration** page, select **Body Colour** to change the car illustration on the Android Auto details page. Each choice includes a colour swatch. Selection is saved immediately, survives activity/app restarts, and updates an existing Auto screen on its next refresh without restarting navigation or acquisition. A missing or unrecognised preference defaults to **Soul Red Crystal Metallic** (red). Appearance is independent of the diagnostic model-year/transmission profile and sends no CAN requests.

The seven choices are Soul Red Crystal Metallic, Machine Gray Metallic, Aero Gray Metallic, Deep Crystal Blue Mica, Jet Black Mica, Snowflake White Pearl Mica and Zircon Sand Metallic. These are paints offered on the ND in Mazda's 2024 documentation, with availability varying by market, trim and roof type; this is not an exhaustive catalogue of every historical ND special edition. Official references:

- [Mazda 2024 MX-5 media page](https://news.mazdausa.com/vehicles-2024-mx-5) and its [2024 MX-5 Spec Deck, Interior & Exterior Colors tables](https://news.mazdausa.com/download/2024+MX-5+Spec+Deck.pdf).
- [Mazda 2023 pricing and packaging announcement](https://news.mazdausa.com/2022-11-17-2023-Mazda-MX-5-Miata-Pricing-and-Packaging), including the Soul Red Crystal, Machine Gray, Snowflake White and Zircon Sand paint names/finishes.

The RGB swatches are illustrative approximations chosen for the flat schematic, not official Mazda RGB values, paint-matching references or a simulation of metallic/pearl finishes. Only the body fill changes: tyres, mirrors, glass, seats, lights and outlines retain their own colours. The body and detail vectors are separate layers so tinting cannot recolour the whole car or reopen the front-tyre contour defect.

## GPS and Destination

The **Destination** panel searches addresses and places through Nominatim and displays an OpenStreetMap map. Select a search result or long-press the map, then tap **Start guidance** while parked. The selected destination is saved locally, but guidance never starts automatically. Grant precise location permission and enable phone location. The stop icon or the foreground navigation notification stops guidance. Selecting another destination does not change the active route until Start guidance is tapped again.

OSRM computes a driving route. A location foreground service follows it independently of the phone activity, including while the activity is backgrounded or destroyed. The service does not restart automatically after process termination. It waits for a recent fix accurate to 50 m or better, hides instructions after 10 seconds without a reliable fix, and recalculates when more than 60 m off route. Failed requests retry with subsequent location updates, no more often than every 15 seconds.

The turn tile uses rally-style light, medium, and sharp left/right arrows, a dedicated roundabout sign with the exit number when available, a U-turn arrow, and a destination flag. Distances follow route geometry and round upward in 50 m increments (51 m displays as 100 m). These are OSRM road-navigation instructions, not surveyed rally pace notes. **The small speed indicator uses only the existing vehicle OBD/CAN values**, or explicitly labelled SIM values during simulator testing. GPS speed is never acquired or displayed; absent or expired vehicle speed shows `--`.

Internet is required for search, initial routing, recalculation, and uncached map tiles. A loaded route can continue offline while the vehicle stays on that route. Nominatim receives the typed search, OSRM receives start/destination coordinates, and the OSM tile server receives map tile requests. Searches are explicit (no autocomplete), with one in-flight request and at least one second between searches. Map attribution is displayed. Public Nominatim and OSRM demo servers have usage limits and no availability guarantee; use appropriately configured production/self-hosted services before distributing this prototype. Offline map downloads and voice guidance are not implemented.

## Experimental Android Auto

Both debug and release APKs include the Car App Library service, labelled **MX-5 Experimental**, and its navigation surface. Android Auto support is no longer conditional on the debug source set. This remains a prototype, not an officially approved vehicle-dashboard category. Basic turn guidance does not establish compliance with all Android Auto navigation app requirements or guarantee acceptance by a real head unit. It may interfere with another navigation app. Test only while parked.

The main Auto page draws the next maneuver on the left and RPM on the right, with vehicle speed at the bottom right. Above them are compact oil-temperature (`C`, engine icon), fuel-volume (`L`, pump icon) and actual-gear tiles. The roundabout sign circulates counterclockwise for right-hand traffic. Gear `0` is displayed as `N`; PID `A4` value `1` may also mean reverse, so the app does not invent an `R` indication. Unsupported or expired readings show `--`. Fresh simulated readings are marked `SIM`.

The hamburger action opens a second page: a top-view roadster with pressure (`bar`) and temperature (`C`) for each wheel, and a vehicle tile with coolant temperature, control-module voltage, intake-air temperature and oil pressure. Use the host Back action to return. The host positions the native menu action and controls the usable surface, so its exact placement is not guaranteed to be the requested top-right corner. Native actions work with touch and rotary controls.

Both pages draw from the top of the supplied surface, underneath the host's native action controls. This deliberately ignores the top inset: the tested Android Auto host kept reporting 88 pixels reserved even after its buttons disappeared, so using the visible area alone left an empty band indefinitely. The mini-tiles now occupy that band without waiting for a visibility callback. Native controls may temporarily cover their values when shown; the app cannot reliably detect their visibility with this library/host combination. The visible area's side and bottom boundaries remain respected, with the stable area as fallback when visibility is unknown. Android Auto still controls action hiding, touch/rotary behavior and the drawing surface itself.

The projected surface uses `Surface.lockHardwareCanvas()`. On the Pixel, repeated failures from the previous software `lockCanvas()` path were observed while investigating a stale layout; the subsequent hardware-rendered session no longer logged those failures in the consulted traces. Surface-size, visible/stable-area and computed drawing-area changes are logged under `AutoDashboard` (only on callbacks, not on every frame), so geometry changes can be distinguished from rendering failures without screenshots.

Only the selected page's diagnostic group is queried. An already outstanding request may finish during a page switch, but no new requests from the old group are sent. Main-page targets are RPM 5 Hz, speed and gear 2 Hz each, oil and fuel 0.5 Hz each; these share the 10 requests/s cap and slow down on missing/delayed responses. Detail measurements target 0.5 Hz; wheel values sharing a DID use one request for all four wheels. No background main-page polling is kept while Details is visible.

The catalogue's wheel-pressure/temperature DIDs `C901`/`C902` apply only through model year 2015. They are deliberately not queried for the 2024 profile: its wheel fields remain `--` until verified compatible signals are added. Oil temperature (`MX5_EOT`), fuel volume (`MX5_FLV`), actual gear (`GEAR_ACT` with its support flag), and oil pressure still need physical validation; catalogue availability is not proof of ECU support.

Select and start the destination in the phone's Destination panel. With no active route, the left tile shows navigation status. RPM threshold and edge lighting follow the phone configuration, updating approximately ten times per second. Speed always comes from CAN/SIM, never GPS. The Auto page does not expose Debug or CAN transmit controls, but selecting a page changes the phone's read-only polling group.

Acquisition still belongs to the phone activity: open the phone dashboard and connect the adapter or PC simulator while parked; reads start automatically. The Auto service does not open USB or request USB permission. Android may destroy the phone activity in the background; if it does, acquisition stops and the Auto screen loses its values. A standalone foreground acquisition service is not implemented by this prototype.

The engine, pump and menu icons come from [Pictogrammers Material Design Icons](https://github.com/Templarian/MaterialDesign), under Apache 2.0; attribution and the full license are packaged in the app assets. No suitably licensed minimalist ND SVG was verified during the online search. The top-view car is an original schematic Android vector, not downloaded artwork or an official Mazda illustration; a verified ND SVG remains a follow-up asset replacement.

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

### Personal-Use Install-Source Workaround

On 2026-11-17, the user confirmed that **MX-5 Experimental** launched in the Mazda's Android Auto after reinstalling the same debug APK with Google Play declared as its installer. The test used a Pixel 9 running Android Auto `18.0.664264-release`; no Play Console account, publication, or fee was required.

From the repository's `android/` directory, choose the phone's serial from `adb devices -l` and run:

```bash
adb -s PIXEL_SERIAL install -r -i com.android.vending app/build/outputs/apk/debug/app-debug.apk
adb -s PIXEL_SERIAL shell dumpsys package com.mx5.dashboard | rg 'installerPackageName=|initiatingPackageName='
```

The verified result was:

```text
installerPackageName=com.android.vending
initiatingPackageName=com.android.shell
```

Reconnect Android Auto in the car while parked, then look for **MX-5 Experimental**, including under **Customize launcher** in the phone's Android Auto settings. Successful package installation or the installer field alone does not prove that Android Auto accepts the app; opening it on the car is the actual check.

This is an unofficial installer-attribution workaround, not a genuine Play Store installation or Android Auto approval. It worked on the configuration above but may fail on other versions or stop working after updates. The **Unknown sources** option still does not override Car App Library restrictions. Google Play Internal App Sharing remains an officially documented real-vehicle testing route.

`-r` keeps existing app data when the APK's signature is compatible; this test preserved the installed app's data. Do not uninstall or clear data if installation fails. Later ADB installs may change the installer attribution, so use the same `-i com.android.vending` option and verify it again when deploying updates for car testing. This workaround does not change host validation, start CAN reads, or guarantee background acquisition.

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