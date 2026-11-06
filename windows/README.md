# LumiSync PC Bridge Preview 0.1

**Developer preview, not a working turnkey Synapse integration.** Intended for Windows 11 x64 with Synapse 4. The user's REST SDK 4.01.00 was confirmed by screenshot; native Chroma Connect compatibility and physical lights have not been tested. No application ID has been issued for LumiSync. Live sync cannot run without that prerequisite.

## What you can use now

Extract the ZIP, open `LumiSync-Bridge.exe`, and select **Check local Chroma SDK**. The program is self-contained and does not require a separate .NET installation. It is an unsigned preview. It installs no drivers, startup entries, firewall rules, or Windows services. Close it to stop.

If you already have Home Assistant with Cync configured and trusted HTTPS access, enter its URL and a Home Assistant long-lived access token. Click **Load color lights**, explicitly select your physical Cync light, and click **Test selected light: teal**. This sends a real command to that light. The list includes all available color lights, not only GE products. A successful HTTP response is not proof the physical light changed.

Google Home linking is not a Home Assistant connection and does not grant this bridge access. Do not enter your Google, GE, or Razer password. The token is kept only in process memory and is cleared from the input after connection. Disconnect clears the session. Nothing is uploaded automatically, and no credentials are logged or saved.

## Live Synapse effects: blocked prerequisite

Obtain a Razer-issued **LumiSync** application ID through the Connected Devices / IoT integration program: https://developer.razer.com/contact/ . Installing the REST SDK, creating a random GUID, or renaming this app will not register it. Do not use another product's ID. Razer may require an agreement or additional SDK setup; access is not guaranteed.

With the issued ID and Razer's 64-bit Chroma Connect API installed in Windows System32 (`RzChromaConnectAPI64.dll`), enter the ID and choose **Connect Chroma receiver**. Enable the registered integration in the Razer Chroma app's Chroma Connect area. The five swatches display real received callback colors only. Missing registration/DLLs/API entry points are shown as errors. This receiver is experimental and must be validated with Razer's current SDK and physical hardware before calling it compatible.

After real frames arrive, choose a Chroma zone and a light, then **Start live sync**. One selected light is supported in this preview. Output is limited to one command per second with latest-frame coalescing; cloud Cync latency may be longer. This is not frame-accurate gaming lighting. Black switches the light off; a following nonblack color turns it back on. Stop cancels pending requests and leaves the last physical state. A command already accepted by Home Assistant may still finish. Network errors stop output; reconnect/restart explicitly.

The Android app is not modified by this preview. Phone pairing, direct GE Cync authentication, Bluetooth forwarding, virtual USB emulation, and Google Home API integration are not implemented. Android Cync-aware discovery remains on its existing feature branch.

## Build and verification

.NET SDK 8 on Windows:

```
dotnet run --project windows/LumiSync.Checks -c Release
dotnet publish windows/LumiSync.Bridge -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o bridge-package
```

The Windows workflow checks RGB byte order, truncated frames, callback buffer ownership, burst coalescing, non-live clearing, URL validation, light capability filtering, authentication, service payloads, black/off semantics, redirects, and cancellation. A separate window launch check saves a screenshot. These automated checks do not verify the native SDK or physical light behavior.

## Next development gate

Validate receiver registration and actual effect callbacks on the target PC with a legitimately issued ID before adding Android pairing or claiming live Chroma support. Windows USB emulation remains research only and is not presented as a working alternative.

References:
- https://developer.razer.com/connected-devices/
- https://assets.razerzone.com/dev_portal/REST/html/index.html
- https://github.com/TheDouglife/chroma-rgb-connect/tree/c2766b571338d95697db3a78ee76afae076d90e0/src/SDK/ChromaConnect.SDK.Synapse
- https://developers.home-assistant.io/docs/api/rest/
