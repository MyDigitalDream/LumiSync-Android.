# LumiSync 0.2

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

The Home Assistant token remains in memory for the screen session and is not saved to disk or Android view state. App backups are disabled; redirects are refused. Never enter your GE password into LumiSync. Home Assistant's Cync integration handles GE authentication. Revoking a Home Assistant token ends its access. No music sync, strip segments or dynamic Cync effects in this build.

References: https://www.home-assistant.io/integrations/cync/ and https://developers.home-assistant.io/docs/api/rest/

## Validation

No Android SDK or Gradle is installed in the editing environment. GitHub Actions is the compilation check. No physical Bluetooth or Cync light testing has been performed.
