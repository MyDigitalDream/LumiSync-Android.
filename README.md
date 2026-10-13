# LumiSync 0.4

Android app with experimental Bluetooth LED control and GE Cync control through a Home Assistant server. Direct phone-to-Cync authentication is not implemented. Bluetooth protocols differ between controllers; the provisional Lotus Lantern-style commands are not confirmed for your hardware.

## Build and install

GitHub Actions → Build LumiSync APK → Run workflow. Download the LumiSync-debug-apk artifact from a successful run, extract app-debug.apk and install it on Android 8 or newer. Allow installs from your file manager when prompted. The source ZIP itself is not an installable app.

Local build: JDK 17, Android SDK 35, Gradle 8.9; run `gradle :app:assembleDebug`. APK: app/build/outputs/apk/debug/app-debug.apk.

## Bluetooth

Enable Bluetooth, grant Nearby Devices permission (location on Android 11 and earlier), scan, select your LED controller, then try On/Off and RGB. No Bluetooth radio is required to use the Cync screen. Commands are experimental and do not confirm the physical light state. If controls do not work, provide the original controller app name, LED model, advertising name and displayed characteristic UUID. BLE writes are not yet queued; wait between taps.

## GE Cync Wi-Fi

1. Set up the lights in GE Cync.
2. On a separate running Home Assistant server, add the Cync integration under Settings → Devices & services. Requires a Cync account and at least one Wi-Fi connected device.
3. Configure trusted HTTPS access to Home Assistant. HTTP and self-signed certificates are not supported by LumiSync.
4. Create a Home Assistant long-lived access token in your profile.
5. In LumiSync, open Cync controls, enter the HTTPS server URL and token, then connect. Select the Cync light by entity ID; the list includes all Home Assistant light entities.
6. Use On/Off, brightness and RGB where the light supports them. Refresh to read the state.

The Home Assistant token remains in memory for the app process session (including navigation between Bluetooth and Cync) and is not saved to disk or Android view state. App backups are disabled; redirects are refused. Never enter your GE password into LumiSync. Home Assistant's Cync integration handles GE authentication. Revoking a Home Assistant token ends its access. No music sync, strip segments or dynamic Cync effects in this build.

References: https://www.home-assistant.io/integrations/cync/ and https://developers.home-assistant.io/docs/api/rest/

## Validation

GitHub Actions compiles the APK, runs Android lint, and launches it in an Android 15 emulator for navigation, disconnected controls, color selection and setup validation checks. UI screenshots are retained as a workflow artifact. No physical Bluetooth or Cync light testing has been performed.

## Interface in 0.3

Dark dashboard with separate Bluetooth and GE Cync tabs; a nearby-device picker; explicit connection states; large power buttons; a full HSV color wheel and six named presets; color intensity; and contextual connection help. Cync setup collapses after connection, with capability-aware light controls, state refresh, and a disconnect action that clears the in-memory session. Bluetooth writes are guarded while one request is pending.

The first 0.3 install may require uninstalling 0.2 because the earlier CI build used a different debug signing key. Future debug builds reuse the cached key while it remains available; this is a test build, not a production release signing setup.

## Cync-aware Bluetooth discovery in 0.4

Tap **Find a light** on Bluetooth, or **Find Cync nearby** on GE Cync. The picker prioritizes likely lights and labels Cync name matches, possible Telink/Cync mesh devices, and other Bluetooth mesh devices. Enable **Show other Bluetooth devices** for unrecognized or unnamed lights. Later advertisements update the same result rather than being discarded.

Detection is based on advertising names, service UUIDs and manufacturer identifier 0x0211. That company identifier and Telink services are shared across brands: they indicate a *possible* Cync/mesh device, not a verified GE product. Detection does not establish ownership or authenticate a device. Renamed devices or models using other advertisements may remain unrecognized.

Selecting a result performs read-only GATT service discovery. Cync/mesh candidates are kept out of the generic LED write path. The app releases the BLE connection and offers **Open Cync app**, **Link to Home Assistant**, and **Copy device details**. It performs no pairing, resets, mesh provisioning or experimental mesh writes. Direct authenticated Cync Bluetooth control is **not implemented** in this release.

When linking a found device to Home Assistant, choose and confirm the matching physical light once. The Bluetooth-address-to-entity mapping is saved locally and reused on subsequent scans. This is a user-confirmed mapping, not automatic identity matching. The Home Assistant token remains in memory only. Detection itself needs no server or Cync login. No device diagnostics are uploaded automatically.

Protocol references:
- https://cyncsupport.gelighting.com/s/article/light-bulb-faq
- https://github.com/google/python-dimond/blob/master/dimond/__init__.py
- https://github.com/google/python-laurel/blob/master/laurel/__init__.py
- https://github.com/Proxy-alt/cync-lan/blob/feature/ha-custom-component/docs/ble_provisioning_protocol.md

Nine discovery unit tests cover name boundaries, shared-vendor ambiguity, service discovery, merging advertisements, and blocking generic writes on mesh devices. Physical Cync hardware detection and control have not been tested in the development environment.
