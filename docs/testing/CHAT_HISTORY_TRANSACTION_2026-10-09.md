# Room history transaction execution — 2026-10-09

- Emulator: existing Pixel_6 AVD, `emulator-5554`, Android 17, API 37.
- App/test APK built with `assembleDebug` / `assembleDebugAndroidTest`.
- The tests use a fresh in-memory Room database per case, not paid API calls or the user's production chat database.
- Result: **OK (5 tests)**, AndroidJUnitRunner. Instrumentation final code `-1` means runner completion, not test failure.

Passed cases:

1. `lateReplyCannotResurrectADeletedConversation`
2. `mismatchedReplyRollsBackWithoutChangingTheBadge`
3. `completeReplyPersistsMessageAndModelBadgeTogether`
4. `historyIncludesOnlyTheActualChatAndItsProject`
5. `missingHistoryIsAnErrorInsteadOfAnEmptyValidConversation`

Gradle's offline `connectedDebugAndroidTest` could not resolve the uncached UTP plugin `android-test-plugin-host-additional-test-output:32.1.0`. We did not treat that command as a passed check. The already compiled APKs were instead installed and run directly:

```powershell
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell am instrument -w -r -e class com.mobilegroup20.modelpilot.chat.ChatHistoryTransactionTest com.mobilegroup20.modelpilot.test/androidx.test.runner.AndroidJUnitRunner
```

The runner reported a success status for all five named cases and the final summary:

```text
Time: 0.268
OK (5 tests)
INSTRUMENTATION_CODE: -1
```

This validates actual Room snapshot/persistence behavior on an emulator. It does not establish the quality of live-model answers, screen rotation behavior, SAF UI, measured UI frame rates or physical-device performance. Those remain separate manual acceptance tasks.
