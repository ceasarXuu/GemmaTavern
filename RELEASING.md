# Releasing for Git Distribution

This project is distributed through Git and Git-hosted release assets only.

It is not prepared for Play Store or other app store submission, so the release process here focuses on source hygiene, reproducible builds, and side-load verification.

## Release scope

- Source repository on Git
- Optional Android APK attached to a Git release
- No store metadata, store signing, or store review requirements

## Required checks

Run from the repository root:

1. `Set-Location .\Android\src`
2. `./gradlew.bat :app:testDebugUnitTest`
3. `./gradlew.bat :app:lintRelease`
4. `./gradlew.bat :app:assembleRelease`

Expected APK output:

- `Android/src/app/build/outputs/apk/release/app-release.apk`
- `Android/src/app/build/outputs/apk/release/versioned/GemmaTavern-<version>-release.apk`

## Release signing

Public release APKs should not use the Android debug certificate. Local signing
configuration is read from `Android/src/release-signing.properties`, which is
ignored by Git and must not be committed.

Expected local file shape:

```properties
storeFile=keystores/gemmatavern-release.jks
storePassword=...
keyAlias=gemmatavern_release
keyPassword=...
```

If this file is absent, Gradle falls back to debug signing and prints a warning.
Treat that APK as a local test build only, not a public release asset.

If a previously published APK used the debug certificate, Android will reject
in-place upgrades to a release-signed APK with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
That transition requires uninstalling the old debug-signed app before installing
the release-signed app.

To inspect the certificate used by an APK:

```powershell
$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
  Sort-Object Name -Descending |
  Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs .\app\build\outputs\apk\release\app-release.apk
```

## Public APK install-surface hygiene

The GitHub release APK is sideloaded, so some device vendors can still show
non-store install warnings. Keep the release package as quiet as possible:

- sign with the local release keystore, not the debug key,
- keep debug and benchmark entry points out of release builds,
- remove unused Firebase, GCM, advertising, account, phone-state, and legacy
  external-storage manifest entries from the public release surface,
- verify the merged release permissions before publishing:

```powershell
$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
  Sort-Object Name -Descending |
  Select-Object -First 1
& (Join-Path $buildTools.FullName 'aapt2.exe') dump permissions .\app\build\outputs\apk\release\app-release.apk
```

## Optional integration caveats

The repository intentionally does not ship private service credentials.

- Hugging Face OAuth is disabled by default unless `HUGGINGFACE_CLIENT_ID` and `HUGGINGFACE_REDIRECT_URI` are configured in `Android/src/gradle.properties`.
- Firebase and FCM remain optional integration points; `google-services.json` is not included and `ENABLE_FIREBASE` should stay `false` for the default OSS build.
- A Git-distributed APK should document which optional cloud features are unavailable in the published binary.

## Side-load verification

For Android device verification, prefer an in-place install that preserves app data:

1. `adb devices`
2. `adb install -r .\app\build\outputs\apk\release\app-release.apk`
3. `adb shell am start -W -n selfgemma.talk/.MainActivity`

After launch, inspect startup crashes before publishing the APK:

1. `adb logcat -c`
2. Launch the installed release APK.
3. `adb logcat -d -v time AndroidRuntime:E ActivityTaskManager:W ActivityManager:W selfgemma.talk:E '*:S'`

If release minification is enabled, treat any startup `FATAL EXCEPTION` as a release blocker even when
`am start -W` returns `Status: ok`.

## Repository hygiene before tagging

- Do not commit Android build outputs or backup artifacts.
- Keep `Android/src/build-backups/` out of version control.
- Make sure release notes explain any optional integrations or known limitations in the attached APK.

## GitHub release tooling fallback

If `gh` is not installed in the local environment, a maintainer may still create the GitHub Release
through the GitHub REST API after pushing the release tag.

Practical rule:

- treat `git push origin <tag>` as the required source-of-truth step,
- then create the hosted release entry and upload the APK with either GitHub CLI or an authenticated API call,
- keep the release notes aligned with the exact APK that passed local checks and side-load verification.
