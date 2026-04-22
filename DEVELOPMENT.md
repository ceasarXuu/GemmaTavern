# Development Notes

## Local build

The Android Gradle wrapper lives under `Android/src`.

Typical commands from the repository root:

1. `Set-Location .\Android\src`
2. `./gradlew.bat :app:assembleDebug`
3. `./gradlew.bat :app:testDebugUnitTest`

If Gradle starts failing with Kotlin daemon or incremental cache errors such as
`Storage ... is already registered`, do not keep retrying the same parallel or
incremental path. Stop the daemon and rerun serially:

1. `./gradlew.bat --stop`
2. `./gradlew.bat :app:testDebugUnitTest --no-daemon`
3. `./gradlew.bat :app:assembleDebug --no-daemon`

Do not run two Gradle tasks in parallel against the same `Android/src` module
directory. Concurrent unit-test and assemble runs collided on KAPT snapshot and
incremental cache files in this workspace and produced false-negative build
failures.

For release validation, also run:

1. `./gradlew.bat :app:lintRelease`
2. `./gradlew.bat :app:assembleRelease`

## Device verification note

On the current MIUI test device, `adb install -r` does not reliably leave the
app in the foreground after an overwrite install.

When validating a fresh debug or release APK on-device, explicitly rerun:

1. `adb shell am start -W -n selfgemma.talk/.MainActivity`
2. `adb shell pidof selfgemma.talk`
3. `adb shell dumpsys activity activities | Select-String -Pattern 'selfgemma.talk/.MainActivity|ResumedActivity' -Context 0,1`

Do not assume the previous foreground state survived the install step.

On the current Android 16 test device, `adb shell pidof -s selfgemma.talk`
returned an empty result even while the process was alive. Prefer
`adb shell pidof selfgemma.talk`, and if that ever looks ambiguous, confirm with
`adb shell ps -A | Select-String -Pattern 'selfgemma.talk'`.

## Targeted instrumentation note

For single Android instrumentation classes, the most reliable path on this
workspace has been:

1. `Set-Location .\Android\src`
2. `./gradlew.bat :app:installDebug :app:installDebugAndroidTest`
3. `adb shell am instrument -w -r -e class <fqcn> selfgemma.talk.test/androidx.test.runner.AndroidJUnitRunner`

Using Gradle runner-arg properties such as
`-Pandroid.testInstrumentationRunnerArguments.class=...` triggered a plugin
configuration failure around the Protobuf Gradle plugin on this setup, so avoid
that path unless it is revalidated later.

## Optional service configuration

The default open-source build intentionally ships without private service credentials.

Optional settings live in `Android/src/gradle.properties`:

- `HUGGINGFACE_CLIENT_ID=`
- `HUGGINGFACE_REDIRECT_URI=selfgemma.talk.auth:/oauth2redirect`
- `ENABLE_FIREBASE=false`

### Hugging Face OAuth

Gated Hugging Face model downloads require your own Hugging Face OAuth app.

When you configure it:

1. Set `HUGGINGFACE_CLIENT_ID`.
2. Set `HUGGINGFACE_REDIRECT_URI`.
3. Keep the redirect URI scheme aligned with your Hugging Face app registration.

If these values are left empty, the app will still build, but gated Hugging Face downloads stay disabled.

### Firebase / FCM

Firebase is off by default for the public repository.

- `google-services.json` is not included.
- `ENABLE_FIREBASE` should remain `false` unless you maintain your own Firebase project.
- If you fully wire Firebase for your fork, keep the Google Services plugin and runtime configuration aligned with that setup.

## Public documentation boundary

- `README.md`, `DEVELOPMENT.md`, and `RELEASING.md` are the source of truth for build and release flow.
- Stable architecture and validation references live under `docs/`.
- Internal plans, one-off reports, and temporary working notes should stay out of the repository.
