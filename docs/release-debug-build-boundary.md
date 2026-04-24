# Release and Debug Build Boundary

This project keeps internal diagnostics behind build-type flags:

- `ENABLE_INTERNAL_DIAGNOSTICS` is enabled only for `debug` and `benchmark` builds.
- `ENABLE_BENCHMARK_UI` is enabled only for `debug` and `benchmark` builds.
- The release manifest must not declare `profileable android:shell="true"`.
- Benchmark/evaluation activities belong to the `benchmark` source set manifest, not `main`.

When changing diagnostics, benchmark screens, debug export, or test-only launch surfaces, verify:

1. `./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin`
2. `./gradlew :app:assembleDebug :app:assembleRelease`
3. Inspect the release APK manifest for missing `profileable` and missing benchmark/eval activities.
4. Install and launch debug on a device after UI-affecting changes.
