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

On the current Android 16 test device, both `adb shell pidof selfgemma.talk`
and `adb shell pidof -s selfgemma.talk` have shown intermittent false-empty
results across different runs. Treat both as hints, not the source of truth.
Always fall back to:

1. `adb shell ps -A | Select-String -Pattern 'selfgemma.talk'`
2. `adb shell dumpsys activity activities | Select-String -Pattern 'selfgemma.talk/.MainActivity|ResumedActivity' -Context 0,1`

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

## Roleplay debug export retrieval

The stable debug-time path for pulling a specific roleplay chat out of a real
device is now the `Download/GemmaTavern/debug-exports/` bundle, not a live
Room database copy.

Recommended flow:

1. Trigger `Export Debug Bundle` from the session list or the chat overflow menu.
2. Read the latest pointer:
   `adb shell cat /sdcard/Download/GemmaTavern/debug-exports/latest-debug-export.json`
3. Pull the resolved bundle:
   `adb pull /sdcard/Download/GemmaTavern/debug-exports/<fileName>.json <local-target>`

If you are validating the MediaStore writer itself, the reliable device path on
this workspace was:

1. `Set-Location .\Android\src`
2. `./gradlew.bat :app:installDebug :app:installDebugAndroidTest --no-daemon`
3. `adb shell am instrument -w -r -e class selfgemma.talk.domain.roleplay.usecase.WriteRoleplayDebugBundleAndroidTest selfgemma.talk.test/androidx.test.runner.AndroidJUnitRunner`

Do not fall back to hot-copying `selfgemma_talk.db` plus WAL/SHM while the app
is live. That path produced malformed snapshots during debugging and is no
longer the preferred export route.

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

## Roleplay tool permissions

Permission-gated roleplay tools must stay hidden from the runtime model until
both conditions are true:

1. the user explicitly enables the tool family in Settings, and
2. the matching Android runtime permission has already been granted.

Do not register location or calendar tools speculatively and then let the
model discover they cannot run. Filter them out before `resetConversation(...)`
so the model only sees tools it can actually use in that turn.

For zero-config network tools such as Wikipedia, Open-Meteo weather, or
OpenStreetMap / Nominatim place lookup, prefer public endpoints with no project
API key. That keeps the open-source build usable out of the box, but recheck
rate limits and availability before adding more traffic-sensitive providers.

## Debug export feedback

Roleplay debug export success feedback now uses the system `Toast` with
`Toast.LENGTH_SHORT` rather than an in-layout status line. When you verify this
flow, do not expect a persistent message inside the chat or session list; the
reliable regression checks are:

1. `RoleplayChatViewModelTest` and `SessionsViewModelTest` for the 2-second
   status lifecycle.
2. `:app:assembleDebug` and `:app:installDebug`.
3. Manual device confirmation that the toast appears and clears quickly after
   export.

## Roleplay external evidence architecture

When a roleplay tool returns real-world facts, do not let those facts flow only
through assistant prose or recursive session summaries. The reliable pattern in
this workspace is:

1. persist the tool trace in `tool_invocations`,
2. persist structured real-world facts in `external_facts`,
3. render those facts in prompt assembly as a dedicated `External Evidence`
   section,
4. keep stable session summary and retrieval queries separate from stale
   assistant factual claims.

This prevents a wrong real-world answer from being repeated simply because it
was present in recent assistant text or older summary text.

## Android schema and migration verification

For Room schema changes in this app, do not stop at `assembleDebug`. The
reliable validation chain is:

1. `:app:testDebugUnitTest` for the affected architecture slice,
2. `:app:assembleDebug`,
3. `:app:installDebug`,
4. `:app:installDebugAndroidTest`,
5. run at least one targeted instrumentation class if the change affects
   exports, storage, or Android-only code,
6. `adb shell am start -W -n selfgemma.talk/.MainActivity`,
7. verify foreground state with `adb shell pidof selfgemma.talk` and
   `adb shell dumpsys activity activities`.

On this device, `run-as selfgemma.talk sqlite3 ...` was not a reliable shortcut
for checking the database after migration because executing `sqlite3` under
`run-as` failed with `Permission denied`. Use app launch plus instrumentation
instead of assuming an in-shell SQLite client is available.

## Public documentation boundary

- `README.md`, `DEVELOPMENT.md`, and `RELEASING.md` are the source of truth for build and release flow.
- Stable architecture and validation references live under `docs/`.
- Internal plans, one-off reports, and temporary working notes should stay out of the repository.
