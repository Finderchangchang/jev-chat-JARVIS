# Android device regression validation

## Observed result (2026-09-23)

- Physical device: vivo V2352A, Android 14 / API 34.
- Installed the separate `com.jev.probe.devicetest` and instrumentation packages; the existing production app and its data were preserved.
- Instrumentation result: `Time: 5.062`, `OK (15 tests)`, `INSTRUMENTATION_CODE: -1`.
- The 12 scheduling/fill regression tests ran on Android, alongside three device UI tests: real `ACTION_SET_TEXT`, stale reply rejection, and switching chats during a delayed retry while preserving the new draft.
- Device UI tests use synthetic chats, real accessibility nodes and the production `ReplyFiller`. They do not exercise the complete `ChatCaptureService` or a live model provider.
- Initial USB installation was rejected by the OEM installer. Pushing the APK and invoking `pm install` succeeded after device confirmation.
- Initial `ActivityScenario` launch timed out. Explicitly targeting the isolated application package and foregrounding the app before instrumentation resolved the test launch problem.

## WeChat validation limitation

The user opened File Transfer Assistant. The foreground WeChat `ChattingUI` window had the `SECURE` flag; `screencap` produced a zero-byte file and UI Automator returned only an empty root (381-byte XML). The test service connected, but capture/overlay/fill could not be reliably verified. No WeChat messages were sent. Do not interpret the 15 passing tests as WeChat end-to-end coverage.

The local mock API was prepared for deterministic responses without sending chat data to an external provider. No end-to-end API result was established. Temporary accessibility settings and USB power settings were restored, and the local port reverse was removed after the attempt.

## Reproduce

Build with an Android SDK, then confirm any OEM install prompts on the device:

```powershell
./gradlew.bat -PisolatedDeviceTest assembleDebug assembleDebugAndroidTest
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/jev-device-test.apk
adb shell pm install -r /data/local/tmp/jev-device-test.apk
adb push app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /data/local/tmp/jev-device-tests.apk
adb shell pm install -r -t /data/local/tmp/jev-device-tests.apk
adb shell am start -W -n com.jev.probe.devicetest/com.jev.probe.MainActivity
adb shell am instrument -w -r -e timeout_msec 15000 com.jev.probe.devicetest.test/androidx.test.runner.AndroidJUnitRunner
```

Keep the device unlocked and idle during the run. Debug-only fixture activities and localhost HTTP configuration are excluded from release builds. No real API keys are needed.
