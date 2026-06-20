# Vertex Proxy Android APK Handoff

## Purpose

This workspace is an Android APK build copy of a Go-based Vertex/Gemini proxy.

The APK runs a local HTTP proxy service on the Android device:

- Admin UI: `http://127.0.0.1:2156/admin/`
- OpenAI-compatible API base: `http://127.0.0.1:2156/v1`
- Model list: `http://127.0.0.1:2156/v1/models`

The user wants Android agents to call Gemini models through this local endpoint. Admin password, API keys, proxy settings, nodes, and exposed models should be configured from the browser admin UI and persisted.

## Workspace

- APK build copy: `E:\project\vertex-proxy-apk-build`
- Do not modify the original project. The user explicitly asked to keep the original project untouched.
- Current APK: `E:\project\vertex-proxy-apk-build\vertex-proxy-local-proxy.apk`

## Current APK

- `versionCode=12`
- `versionName=0.12-always-on`
- Latest commit: `f21718c Make Android proxy service always on`
- APK size: `13124137`
- APK timestamp: `2026/6/20 15:34:28`

Verified:

- `go test ./...` passed.
- APK build passed.
- APK signature verification passed for v1/v2/v3.
- APK does not contain `assets/config/nodes.json`.
- APK contains `classes.dex` and `lib/arm64-v8a/libvproxy.so`.

## User Requirements

- No default API keys in the APK.
- No bundled proxy nodes or subscriptions in the APK.
- Do not modify the original project.
- Admin UI settings must persist: API keys, exposed models, proxy settings, and nodes.
- UI should be mostly Chinese where user-facing.
- Log window should behave like a Windows terminal: no forced jump to top or bottom while the user scrolls.
- Service should keep running in the background as much as Android allows.
- External agent calls should not depend on the user opening the log window.

## Main Changes Made

### Android side

`AndroidManifest.xml`

- Bumped to `versionCode=12` and `versionName=0.12-always-on`.
- Added:
  - `WAKE_LOCK`
  - `RECEIVE_BOOT_COMPLETED`
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- Registered `BootReceiver`.

`src/com/local/vproxy/ProxyService.java`

- Runs the Go native process `libvproxy.so` as a foreground service.
- Holds a `PARTIAL_WAKE_LOCK`.
- Reads only the tail of log files every second.
- Warms local endpoints every second:
  - `/health`
  - `/v1/models`
- Restarts the native process if it exits.
- Keeps the foreground service alive when the task is swiped away.
- Notification opens the live log window.

`src/com/local/vproxy/LogActivity.java`

- Live log window.
- Monospace text.
- Does not redraw when log text is unchanged.
- Auto-follows only when the user is already near the bottom.
- Does not steal scroll position while the user is reading history.

`src/com/local/vproxy/MainActivity.java`

- Requests Android battery-optimization exemption when the user saves and starts the service.
- Still directs the user to configure keys, models, proxy, and nodes in the browser admin UI.

`src/com/local/vproxy/BootReceiver.java`

- Starts the foreground service after boot if an admin password has already been saved.

### Go backend side

Earlier commits already added/fixed:

- Android private config paths through:
  - `VPROXY_CONFIG_DIR`
  - `VPROXY_CONFIG`
  - `VPROXY_MODELS`
  - `VPROXY_API_KEYS`
- Admin UI writes keys/models/settings into the private app config directory.
- Clash YAML node import support.
- No bundled node subscription file in the APK.
- No default user API key written by the APK.

## Useful Commit History

- `f21718c Make Android proxy service always on`
- `5fa7b8d Keep proxy service alive in background`
- `b3fbba4 Add live log window and background monitor`
- `14fd96e Remove bundled nodes and auto warm service`
- `74ffea4 Bundle Android nodes and remove default local proxy`
- `5306af5 Fix node import and service readiness`
- `892f8bd Persist Android runtime config paths`
- `cbeb7bd Build Android APK with DNS fallback`

## Build

Run from `E:\project\vertex-proxy-apk-build`:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

Go path used during work:

```text
D:\Programming languages\go\bin\go.exe
```

Android SDK/JDK used by build script:

```text
C:\Users\zhao\AppData\Local\CodexAndroidBuild\android-sdk
C:\Users\zhao\AppData\Local\CodexAndroidBuild\jdk17
```

Note: global `JAVA_HOME` may point to invalid `D:\Java\jdk-26`. If running Android build tools manually, set:

```powershell
$env:JAVA_HOME='C:\Users\zhao\AppData\Local\CodexAndroidBuild\jdk17'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

## Verification Commands

```powershell
$env:PATH='D:\Programming languages\go\bin;' + $env:PATH
$env:GOPROXY='https://goproxy.cn,direct'
go test ./...
```

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

```powershell
tar -tf .\vertex-proxy-local-proxy.apk | Select-String -Pattern 'assets/config/nodes.json|classes.dex|lib/arm64-v8a/libvproxy.so|AndroidManifest.xml|assets/config'
```

```powershell
$env:JAVA_HOME='C:\Users\zhao\AppData\Local\CodexAndroidBuild\jdk17'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'C:\Users\zhao\AppData\Local\CodexAndroidBuild\android-sdk\build-tools\35.0.0\apksigner.bat' verify --verbose .\vertex-proxy-local-proxy.apk
```

## Known Risks / Next Debugging Targets

- Some Android ROMs may still kill or freeze foreground services, especially Chinese OEM ROMs. The code requests battery optimization exemption, but the user may still need to allow background running, autostart, unrestricted battery, and task locking in system settings.
- The user previously reported that admin settings and external agent calls only worked after opening the log window. Current mitigation is foreground service + wake lock + endpoint warmup. If it still happens, collect `adb logcat` to check whether the service is frozen/killed or network is restricted.
- Backend config uses caches. Admin writes should invalidate caches. If settings still disappear or do not take effect, first check whether files are written to the Android private config directory, not assets or the wrong working directory.
- If node import issues return, inspect:
  - `src-go/internal/api/nodes_admin.go`
  - `src-go/internal/nodes/store.go`

## Suggested Skills For Next Codex

- `diagnose`: for background-service, config-refresh, or agent-stuck issues.
- `superpowers:verification-before-completion`: before claiming any APK fix, rebuild, verify signature, and inspect APK contents.
