# Problem P-001: release APK startup crash
- Status: fixed
- Created: 2026-04-24 21:04
- Updated: 2026-04-24 21:17
- Objective: Identify and fix the cause of the installed 0.1.1 release APK crashing immediately on app launch.
- Symptoms:
  - User reports the release APK installed for real-device testing cannot start and crashes immediately after launch.
- Expected behavior:
  - The 0.1.1 release APK launches MainActivity and remains usable.
- Actual behavior:
  - The app exits immediately after launch on the user's real device.
- Impact:
  - Public 0.1.1 release APK may be unusable.
- Reproduction:
  - Install release APK 0.1.1 on attached Android device and launch selfgemma.talk/.MainActivity.
- Environment:
  - Windows PowerShell, D:\GemmaTavern, commit 72d1566d, installed package selfgemma.talk versionCode 25 versionName 0.1.1.
- Known facts:
  - Device package manager reports versionCode=25 and versionName=0.1.1 installed.
  - Release startup crash logs show protobuf lite cannot find field theme_ on obfuscated class qa.n.
  - R8 mapping shows qa.n is selfgemma.talk.proto.Settings and theme_ was renamed to e.
  - Keeping selfgemma.talk.proto generated classes prevents the startup crash in a rebuilt release APK.
- Ruled out:
  - none
- Fix criteria:
  - A release APK built from the fix installs over the existing app and launches without the immediate crash, with logcat showing no matching startup fatal exception.
- Current conclusion: The release crash was caused by R8 renaming app-generated protobuf lite fields required by runtime descriptors.
- Related hypotheses:
  - H-001
  - H-002
- Resolution basis:
  - H-002 + E-004
- Close reason:
  - not closed

## Hypothesis H-001: startup crash has a fatal exception in release-only runtime path
- Status: confirmed
- Parent: P-001
- Claim: Launching the 0.1.1 release APK triggers a fatal exception that is visible in logcat and points to a release-only runtime path.
- Layer: root-cause
- Factor relation: single
- Depends on:
  - none
- Rationale:
  - The previous validation confirmed installation and launch status, but the user now reports immediate real-device crash after using the installed build.
- Falsifiable predictions:
  - If true: logcat after launching selfgemma.talk/.MainActivity will contain a FATAL EXCEPTION or AndroidRuntime crash for selfgemma.talk.
  - If false: launch will either remain stable or fail without an app-process fatal exception.
- Verification plan:
  - Clear logcat, launch the installed release app, and capture AndroidRuntime and process crash logs.
- Related evidence:
  - E-001
  - E-002
- Conclusion: Confirmed by a fresh release launch log showing AndroidRuntime crashes during application creation.
- Next step: Verify the proto keep-rule fix with a rebuilt release APK.
- Blocker:
  - none

## Evidence E-001: installed package version
- Related hypotheses:
  - H-001
- Direction: neutral
- Type: environment
- Source: adb shell dumpsys package selfgemma.talk
- Raw content:
  ```text
  versionCode=25 minSdk=31 targetSdk=35
  versionName=0.1.1
  lastUpdateTime=2026-04-24 20:49:44
  ```
- Interpretation: The attached device has the intended 0.1.1 package installed, so startup logs will apply to the release candidate under investigation.
- Time: 2026-04-24 21:04

## Hypothesis H-002: R8 obfuscates protobuf lite fields required by runtime descriptors
- Status: confirmed
- Parent: P-001
- Claim: Release minification renames fields in app-generated protobuf lite classes, causing GeneratedMessageLite runtime descriptor lookup to fail during Settings parsing.
- Layer: root-cause
- Factor relation: single
- Depends on:
  - H-001
- Rationale:
  - The crash text names the original generated field theme_ while listing only obfuscated field names on qa.n.
- Falsifiable predictions:
  - If true: the release mapping will show selfgemma.talk.proto.Settings mapped to qa.n and theme_ renamed to another member name.
  - If false: qa.n will not be Settings or theme_ will not be renamed.
- Verification plan:
  - Add a keep rule for selfgemma.talk.proto generated classes, rebuild release, install it, and verify startup no longer crashes.
- Related evidence:
  - E-002
  - E-003
- Conclusion: Confirmed by the combination of crash text and release R8 mapping.
- Next step: Build and install a release APK with proto keep rules.
- Blocker:
  - none

## Evidence E-002: launch crash stack trace
- Related hypotheses:
  - H-001
  - H-002
- Direction: supports
- Type: log
- Source: adb logcat after launching selfgemma.talk/.MainActivity
- Raw content:
  ```text
  FATAL EXCEPTION: main
  Process: selfgemma.talk, PID: 28572
  java.lang.RuntimeException: Unable to create application selfgemma.talk.SelfGemmaTalkApplication:
  java.lang.RuntimeException: Field theme_ for qa.n not found.
  Known fields are [public int qa.n.e, ...]
  Caused by: java.lang.RuntimeException: Field theme_ for qa.n not found.
  ```
- Interpretation: The app crashes before MainActivity is usable because protobuf runtime lookup cannot find the expected generated field name.
- Time: 2026-04-24 21:08

## Evidence E-003: release R8 mapping for Settings
- Related hypotheses:
  - H-002
- Direction: supports
- Type: code-location
- Source: Android/src/app/build/outputs/mapping/release/mapping.txt
- Raw content:
  ```text
  selfgemma.talk.proto.Settings -> qa.n:
      int theme_ -> e
  ```
- Interpretation: R8 renamed the exact protobuf class and field reported by the runtime crash, explaining why descriptor lookup fails only in minified release builds.
- Time: 2026-04-24 21:09

## Evidence E-004: fixed release APK launch validation
- Related hypotheses:
  - H-002
- Direction: supports
- Type: fix-validation
- Source: rebuilt release APK with Android/src/app/proguard-rules.pro keep rule, adb install and launch
- Raw content:
  ```text
  adb install -r Android\src\app\build\outputs\apk\release\app-release.apk
  Success
  adb shell am start -W -n selfgemma.talk/.MainActivity
  Status: ok
  LaunchState: COLD
  Activity: selfgemma.talk/.MainActivity
  TotalTime: 340
  WaitTime: 343
  Complete
  logcat fatal check: no FATAL EXCEPTION, AndroidRuntime crash, Field theme_, or Unable to create application entries.
  ```
- Interpretation: The original immediate startup crash no longer reproduces after keeping app-generated protobuf classes.
- Time: 2026-04-24 21:17
