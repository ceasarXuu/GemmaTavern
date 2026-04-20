# Frontend Performance Standard

## Scope

This standard currently covers the main user-facing flows that matter most in SelfGemma Talk:

- cold and warm app startup into the roleplay main entry
- main-tab switching between Messages, Roles, and Settings
- role catalog scrolling as the representative long-list interaction
- sessions list scrolling with many chats
- long conversation history rendering and scrolling
- chat page enter and exit from the sessions list
- role creation editor open, input, and save

## Runtime Monitoring

The app now records per-foreground-session frame health with JankStats and PerformanceMetricsState.

- activity scope is attached in MainActivity
- route scope is attached from the top-level Compose navigation host
- main tab, sessions list scrolling, role catalog list scrolling, and roleplay chat scrolling are emitted as UI state labels
- route-level state labels include chat enter and exit transitions
- frame summaries are dumped on each foreground session end under the log tag FrontendPerf

Tracked runtime indicators:

- jank rate
- slow-frame rate using a 16 ms budget
- frozen frame count using a 700 ms threshold
- frame duration p50, p95, p99, and max
- main-tab switch latency p50, p95, p99, and max
- chat enter and chat exit latency p50, p95, p99, and max

## Excellent Targets

Startup targets are validated with Macrobenchmark:

- cold startup p50 <= 900 ms
- cold startup p95 <= 1200 ms
- warm startup p50 <= 450 ms
- warm startup p95 <= 650 ms

Foreground rendering targets are validated with JankStats session summaries and FrameTimingMetric:

- foreground session jank rate <= 5%
- foreground session slow-frame rate <= 10%
- foreground session p95 frame <= 16 ms
- foreground session p99 frame <= 32 ms
- frozen frames = 0

Critical interaction targets:

- main-tab switch p95 <= 150 ms
- main-tab switch p99 <= 220 ms
- chat enter p95 <= 150 ms
- chat exit p95 <= 150 ms
- role list scrolling jank rate <= 3%

## Benchmark Harness

The repository now contains a dedicated Macrobenchmark module at Android/src/macrobenchmark.

Benchmarks included:

- StartupBenchmark.coldStartup
- StartupBenchmark.warmStartup
- RoleplayFlowBenchmark.mainTabsAndRoleCatalogScroll
- RoleplayChatNavigationBenchmark.openAndCloseChatFromSessions
- RoleplayStressBenchmark.manyChatsScroll
- RoleplayStressBenchmark.longConversationHistoryScroll
- RoleplayStressBenchmark.manyRolesScroll
- RoleplayStressBenchmark.createRoleFromCatalog

- startup benchmarks currently use CompilationMode.Ignore to preserve installed app data and downloaded models on the connected device
- the main flow benchmark uses CompilationMode.Full on Android 14+ devices so steady-state UI measurements are not polluted by JIT warmup
- the main flow benchmark prewarms the Roles tab in setupBlock; startup cost is measured separately by the startup benchmarks
- stress benchmarks seed roleplay fixture data through the benchmark-only seeding activity before every iteration
- stress benchmarks launch benchmark-only surfaces directly instead of relying on selector discovery, which keeps the suite stable on devices with incomplete Compose UI trees
- shared benchmark helpers wake the device, unlock it, and force a fresh surface launch per iteration so frame samples are valid on physical hardware
- touch gestures now verify that selfgemma.talk is still the foreground package before swiping or tapping; if the launcher takes focus, the benchmark fails fast instead of continuing into launcher edit mode
- the stress fixture covers many chats, long message history, many roles, and create-role editor input

## Run Loop

Preferred stable path from Android/src:

```powershell
.\scripts\run-frontend-perf.ps1 -Suite full -Runner auto
```

On ONNZ95CAEMMZSKTS, auto mode intentionally chooses the adb workflow first because Gradle's installer can trigger interactive install confirmation on MIUI and break unattended runs.

The wrapper script now:

- assembles both benchmark APKs before every run
- pins all scripted benchmark execution in this workspace to device serial ONNZ95CAEMMZSKTS
- keeps Gradle connectedBenchmarkAndroidTest available for explicit runs, but auto mode prefers the adb path on ONNZ95CAEMMZSKTS to avoid MIUI install confirmation prompts
- falls back to adb install -r plus direct instrumentation when the Gradle benchmark task fails on the current environment
- skips repeated adb installs when the APK hashes match the last build installed on the same device
- re-installs automatically if either benchmark package is missing from ONNZ95CAEMMZSKTS even when the cached APK hash is unchanged
- keeps the connected device awake and sends wake/unlock key events before adb instrumentation starts
- validates the instrumentation log and fails the run if zero-frame or 4950 ms sentinel metrics are detected
- automatically retries once without reinstall when the first run after an install returns invalid sentinel metrics
- fails fast if a different device serial is requested
- preserves app data and downloaded models during adb installs

From Android/src:

```powershell
.\gradlew.bat :app:assembleBenchmark :macrobenchmark:assembleBenchmark :macrobenchmark:connectedBenchmarkAndroidTest
```

Force the adb path directly when you want the most reliable device run:

```powershell
.\scripts\run-frontend-perf.ps1 -Suite stress -Runner adb -DeviceSerial <adb-serial>
```

This workspace currently pins benchmark runs to ONNZ95CAEMMZSKTS. Passing another DeviceSerial fails fast instead of selecting a different connected device.

Known limitation:

- explicit Gradle runs on ONNZ95CAEMMZSKTS can still hit MIUI install confirmation restrictions even when the benchmark task itself is otherwise healthy
- the device exposes no adb-writeable classic MIUI USB-install toggle such as install_via_usb, and Android's standard verifier is already disabled on this phone
- package manager install commands on the device do not expose a flag that bypasses OEM security-center confirmation for new APK writes
- `cmd package install-existing` is non-interactive only for packages that already exist on-device; it does not solve unattended version updates
- auto runner mode is therefore the recommended default for unattended runs in this workspace
- if the adb path reports invalid frame samples, wake and unlock the device manually and rerun the suite

Practical conclusion for ONNZ95CAEMMZSKTS:

- fully unattended benchmark execution is supported today through `run-frontend-perf.ps1 -Runner auto`, which now defaults to the adb path on this device
- a fully unattended pure-Gradle install path is not currently defensible from code alone on this MIUI/HyperOS build
- if pure Gradle unattended installs are required later, the remaining options are manual device-side policy changes such as enabling any available developer-option USB install switch, relaxing MIUI security-center or Mi Protect prompts, or using privileged control such as root or device-owner tooling

Available suites:

- full: startup + core flow + stress scenarios
- startup: StartupBenchmark only
- core: RoleplayFlowBenchmark only
- stress: RoleplayStressBenchmark only

Run a single benchmark:

```powershell
.\scripts\run-frontend-perf.ps1 -Runner auto -ClassFilter selfgemma.talk.macrobenchmark.StartupBenchmark#coldStartup
```

Seeded stress scenarios currently include:

- 33 roles total, including many custom roles for catalog density
- 19 sessions total, including a pinned long-history session
- 700+ persisted roleplay messages with both short and long-form content

When Gradle execution succeeds, result artifacts are copied to:

```text
Android/src/macrobenchmark/build/outputs/connected_android_test_additional_output/
```

When the wrapper falls back to adb instrumentation, it also stores the raw instrumentation log and a pulled copy of the device traces in timestamped subdirectories under the same output root.

## Iteration Policy

Each optimization round should follow the same loop:

1. record runtime FrontendPerf summaries on device
2. run macrobenchmarks on the same device and variant
3. identify the worst failing flow by p95, jank rate, or frozen frames
4. apply the smallest change that removes the root cause
5. rerun the affected benchmark before moving to the next hotspot
