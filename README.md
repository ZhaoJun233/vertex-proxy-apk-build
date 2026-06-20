# Vertex Proxy Android APK Build

This repository contains an Android APK build of a local Vertex/Gemini proxy.
The app runs a local HTTP service on the phone and exposes an OpenAI-compatible
endpoint for other Android agents.

This Android build was made from the local source project at
`E:\project\vertex-master`.

## APK

- APK path: `vertex-proxy-local-proxy.apk`
- Package: `com.local.vproxy`
- Current version: `0.17-preserve-nodes`
- API base URL: `http://127.0.0.1:2156/v1`
- Admin UI: `http://127.0.0.1:2156/admin/`

The APK does not include default API keys or proxy nodes. User settings are
stored in the app private config directory after first launch.

## Signature Notice

This APK is signed with the local Android debug keystore included in this build
workspace, not with an upstream project release key or Play Store production
key.

- Keystore file: `debug.keystore`
- Alias: `androiddebugkey`
- Certificate owner/source: `CN=Android Debug, O=Android, C=US`
- Created: `2026-06-20 12:48:43 CST`
- SHA1: `4A:D1:FE:99:5B:47:B5:27:C0:8E:1E:41:06:41:43:ED:A8:DB:C5:B3`
- SHA256: `9D:89:11:EC:86:2C:1A:C6:2E:A4:F6:84:D6:CF:2F:1D:94:2B:72:74:AA:12:EF:F7:38:6B:99:04:1D:C8:72:D6`

Treat the APK as a local/test build. Re-sign it with your own release key before
public distribution.

## Basic Usage

1. Install `vertex-proxy-local-proxy.apk` on the Android device.
2. Open `Vertex Proxy`.
3. Enter an admin password and tap the save/start button.
4. Grant notification, battery optimization, and exact alarm permissions when
   prompted. On aggressive OEM systems, also enable autostart and unrestricted
   background battery usage in system settings.
5. Open the admin UI in a browser:
   `http://127.0.0.1:2156/admin/`
6. Add an API key in the admin UI.
7. Configure the external agent:
   - Base URL: `http://127.0.0.1:2156/v1`
   - API key: the key created in the admin UI
   - Model example: `gemini-3.5-flash`

## Recommended Proxy Node Setup

For best reliability, import working proxy nodes before testing model calls.
Direct Google/reCAPTCHA access may timeout on many mobile networks.

Recommended flow:

1. In the admin UI, open the node/proxy page.
2. Import a subscription or node list.
3. Enable the working nodes.
4. Prefer the parallel node pool for mobile use:
   - `parallel_pool_enabled`: `true`
   - `parallel_pool_size`: `4`
5. Test nodes from the admin UI and remove or disable failed nodes.
6. Then test `/v1/models` and a short chat completion from the external agent.

Do not rely on an empty `proxy_url` unless the phone network can reach the
required Google endpoints directly.

## Notes

- The app keeps the service alive with a foreground service, wakelock, watchdog
  alarm, and silent media keepalive to handle aggressive Android background
  freezing.
- Restarting the service preserves imported subscription nodes and the parallel
  node pool. The app no longer clears `nodes.json` on startup.
- Health/model polling logs are intentionally quiet to avoid log noise and
  background I/O churn.

## Build

Run from this directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

The build script compiles the Go binary for Android arm64, packages the Java
Android wrapper, signs the APK with `debug.keystore`, and writes
`vertex-proxy-local-proxy.apk`.
