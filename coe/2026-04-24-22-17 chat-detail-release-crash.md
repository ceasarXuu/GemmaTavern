# Problem P-001: release chat detail crashes on entry
- Status: fixed
- Created: 2026-04-24 22:17
- Updated: 2026-04-24 22:38
- Objective: Identify and fix the 0.1.1 release crash when opening a roleplay chat detail page.
- Symptoms:
  - User reports the app still crashes immediately after entering the chat detail page.
- Expected behavior:
  - Opening a chat detail page should render the session and user persona state without terminating the app.
- Actual behavior:
  - The release process crashes on the main dispatcher while composing chat UI state.
- Impact:
  - Users cannot enter an existing or newly-created chat session in the release APK.
- Reproduction:
  - Install the 0.1.1 release APK, launch the app, then enter a chat detail page.
- Environment:
  - Windows PowerShell, D:\GemmaTavern, installed package selfgemma.talk versionCode 25 versionName 0.1.1.
- Known facts:
  - Dropbox crash entries show `ClassCastException: H6.p cannot be cast to ca.g0` for current 0.1.1 release runs.
  - R8 mapping resolves `H6.p` to `com.google.gson.internal.LinkedTreeMap`.
  - R8 mapping resolves `ca.g0` to `StPersonaDescriptor`.
  - R8 mapping resolves `ca.h0.a()` to `StUserProfile.activePersonaDescriptor()`.
  - R8 mapping resolves the coroutine frame to `RoleplayChatViewModel$uiState$1`.
  - Session user profile JSON is persisted in Room through `RoleplayInteropJsonCodec`.
  - After the fix, mapper tests cover stable field output and release-obfuscated field input.
  - After the fix, the rebuilt release APK opens the existing chat detail page on device without a new crash.
- Ruled out:
  - The earlier protobuf `theme_` startup crash is not the current crash signature.
  - The earlier LiteRT LM JNI method lookup abort is not the current crash signature.
- Fix criteria:
  - Existing release-written session persona JSON with obfuscated field names decodes without `LinkedTreeMap` casts.
  - New session persona JSON writes stable field names.
  - Release chat detail entry no longer produces the `H6.p cannot be cast to ca.g0` crash.
- Current conclusion: The release build persisted and decoded `StUserProfile` through Gson against minified domain classes, so the `personaDescriptions` map could hold raw `LinkedTreeMap` values instead of `StPersonaDescriptor` instances. The fixed build uses an explicit stable schema for session user profiles and keeps roleplay interop JSON models stable in release.
- Related hypotheses:
  - H-001
- Resolution basis:
  - H-001 + E-004 + E-005
- Close reason:
  - release APK validated on device

## Hypothesis H-001: release Gson/minification corrupts persisted session persona JSON
- Status: confirmed
- Parent: P-001
- Claim: `sessionUserProfileJson` is written and read through Gson against minified Kotlin data classes, causing release chat UI state to receive a `Map<String, LinkedTreeMap>` where it expects `Map<String, StPersonaDescriptor>`.
- Layer: root-cause
- Factor relation: sufficient
- Depends on:
  - none
- Rationale:
  - The crash is a direct cast from `LinkedTreeMap` to `StPersonaDescriptor` inside `StUserProfile.activePersonaDescriptor()`.
- Falsifiable predictions:
  - If true: deobfuscating the crash stack points at `RoleplayChatViewModel` reading `userProfile.personaDescription` or avatar fields from a session profile.
  - If false: the deobfuscated stack will point at unrelated message or model code.
- Verification plan:
  - Replace `StUserProfile` Room JSON persistence with an explicit stable schema decoder/encoder, keep JSON-backed roleplay model fields in release, then rebuild/install and reproduce chat-detail entry.
- Related evidence:
  - E-001
  - E-002
  - E-003
- Conclusion: Confirmed by mapping and code inspection, then fixed by explicit schema encoding/decoding and release keep rules.
- Next step: Publish the rebuilt 0.1.1 release APK.
- Blocker:
  - none

## Evidence E-001: current release crash signature
- Related hypotheses:
  - H-001
- Direction: supports
- Type: log
- Source: adb dropbox data_app_crash
- Raw content:
  ```text
  Process: selfgemma.talk
  Package: selfgemma.talk v25 (0.1.1)
  java.lang.ClassCastException: H6.p cannot be cast to ca.g0
      at ca.h0.a(Unknown Source:10)
      at fa.l0.invokeSuspend(Unknown Source:258)
  ```
- Interpretation: The current crash is a release class-cast failure in chat UI state construction.
- Time: 2026-04-24 22:17

## Evidence E-002: R8 mapping resolves the cast to session persona profile data
- Related hypotheses:
  - H-001
- Direction: supports
- Type: code-location
- Source: Android/src/app/build/outputs/mapping/release/mapping.txt
- Raw content:
  ```text
  com.google.gson.internal.LinkedTreeMap -> H6.p
  StPersonaDescriptor -> ca.g0
  StUserProfile -> ca.h0
  StUserProfile.activePersonaDescriptor():88 -> a
  RoleplayChatViewModel$uiState$1 -> fa.l0
  ```
- Interpretation: A Gson map object is reaching `StUserProfile.activePersonaDescriptor()` instead of a typed persona descriptor.
- Time: 2026-04-24 22:20

## Evidence E-003: chat UI reads active persona fields on entry
- Related hypotheses:
  - H-001
- Direction: supports
- Type: code-location
- Source: Android/src/app/src/main/java/selfgemma/talk/feature/roleplay/chat/RoleplayChatViewModel.kt
- Raw content:
  ```text
  userPersonaAvatarUri = userProfile.activeAvatarUri
  userPersonaDescription = userProfile.personaDescription
  ```
- Interpretation: Entering chat detail accesses `activePersonaDescriptor()` while building the initial UI state.
- Time: 2026-04-24 22:22

## Evidence E-004: mapper regression test covers stable and obfuscated session user profile JSON
- Related hypotheses:
  - H-001
- Direction: supports
- Type: fix-validation
- Source: Gradle unit test
- Raw content:
  ```text
  .\gradlew.bat :app:testDebugUnitTest --tests selfgemma.talk.data.roleplay.mapper.RoleplayMappersTest
  BUILD SUCCESSFUL in 39s
  ```
- Interpretation: The codec now reads the release-obfuscated profile JSON shape and writes stable field names for new session profile rows.
- Time: 2026-04-24 22:27

## Evidence E-005: rebuilt release opens chat detail on device without current crash
- Related hypotheses:
  - H-001
- Direction: supports
- Type: fix-validation
- Source: release APK install, adb UI navigation, crash buffer, and UI hierarchy
- Raw content:
  ```text
  adb install -r Android\src\app\build\outputs\apk\release\app-release.apk
  Success
  adb shell input tap 610 475
  adb shell pidof selfgemma.talk
  4436
  UI hierarchy contains chat detail title, back button, input box, and send action.
  No new 22:28+ data_app_crash for selfgemma.talk; latest ClassCastException remains 22:15 before the fix.
  ```
- Interpretation: The same release package can enter the chat detail page without reproducing the `LinkedTreeMap` to `StPersonaDescriptor` crash.
- Time: 2026-04-24 22:38
