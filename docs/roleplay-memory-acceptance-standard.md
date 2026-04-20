# Roleplay Memory Acceptance Standard

## Scope

This standard validates the offline roleplay memory stack under the actual product constraints:

- fully offline execution on device
- short-context models with an effective budget around 4k tokens
- long-term memory built from structured state, open threads, semantic atoms, summaries, and compaction caches
- no hard dependency on remote embeddings, vector services, or cloud memory repair

The goal is not to imitate a generic roleplay memory feature set. The goal is to prove that this codebase can preserve continuity, control prompt budget, and stay operational on a constrained local runtime.

## Acceptance Layers

### 1. Memory Correctness

The acceptance harness must validate these behaviors in one scorecard:

- stable fact correction: conflicting user corrections tombstone stale structured memory and activate the replacement
- structured semantic recall: relevant runtime state, open threads, and semantic atoms survive aggressive budget trimming
- fallback recall: when structured recall is absent, summary plus legacy memories still answer explicit recall requests
- episodic compaction recall: older plot facts remain reachable through summary compaction instead of expanding raw transcript replay
- continuity rebuild: runtime state, summaries, atoms, and open threads can be rebuilt from canonical turns only
- branch isolation: rollback removes future branch pollution from rebuilt memory and summaries
- drift repair activation: role drift detection must trigger a repair prompt on the following turn

### 2. Context Budget Control

The acceptance harness must prove that prompt control works for 4k-class models instead of assuming long context:

- aggressive retrieval pack stays inside its own target token budget
- prompt assembly stays inside `usableInputTokens` for a constrained `ModelContextProfile`
- injected memory token median stays at or below `900`
- injected memory token max is recorded for regression tracking

### 3. Host-Side Memory Performance

Unit-test microbenchmarks are used as a smoke signal for memory pipeline regressions. They are not a substitute for device benchmarks, but they are useful for catching accidental algorithmic blowups in CI and local development.

Measured operations:

- `CompileRoleplayMemoryContextUseCase`
- `ExtractMemoriesUseCase`
- `SummarizeSessionUseCase`
- `RebuildRoleplayContinuityUseCase`

Current host-side smoke gate:

- p95 for each operation should remain `<= 250 ms`

This gate is intentionally loose because machine variance exists. The hard performance gates for user experience stay on real-device benchmarks.

### 4. Engineering Performance

Real-device engineering acceptance must still run on the pinned benchmark phone:

- debug app build/install succeeds
- cold app launch succeeds on device via `adb shell am start -W`
- `dumpsys meminfo selfgemma.talk` is captured
- warm startup macrobenchmark runs
- core flow benchmark runs
- stress benchmark runs

These runs use the existing pinned-device workflow defined in [frontend-performance-standard.md](./frontend-performance-standard.md).

## Pass Criteria

A memory upgrade is accepted only when all of the following are true:

- semantic memory precision `>= 0.95`
- fallback recall precision `>= 0.95`
- episodic compaction recall rate `>= 0.90`
- open-thread recall rate `>= 0.90`
- continuity pass rate `= 1.0`
- branch pollution rate `= 0.0`
- drift repair activation rate `= 1.0`
- prompt budget compliance rate `= 1.0`
- injected memory token median `<= 900`
- host microbench p95 stays within the smoke gate
- device cold launch succeeds and warm startup/core/stress suites complete without benchmark harness failure

## Command Surface

Preferred end-to-end run from `Android/src`:

```powershell
.\scripts\run-roleplay-memory-acceptance.ps1
```

This wrapper is responsible for:

- running the focused roleplay memory unit-test suite
- extracting the machine-readable memory scorecards from JUnit XML
- installing and launching the debug app on the pinned device
- capturing `meminfo`
- capturing shell cold-launch timing via `adb shell am start -W`
- invoking warm startup, core, and stress frontend benchmarks
- generating a markdown report under `docs/reports/`

On the current pinned-device workflow, cold-start acceptance uses the shell launch timing above, while macrobenchmark startup coverage is kept on the warm-start path. This avoids a known cold macrobenchmark instability on the benchmark phone without dropping startup acceptance entirely.

Focused raw unit-test command:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "selfgemma.talk.domain.roleplay.usecase.RoleplayMemoryAcceptanceReportTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.CompileRoleplayMemoryContextUseCaseTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.ExtractMemoriesUseCaseTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.SummarizeSessionUseCaseTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.RoleplayContinuityUseCaseTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.SendRoleplayMessageUseCaseTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.ContextBudgetPlannerTest" `
  --tests "selfgemma.talk.domain.roleplay.usecase.ContextOverflowRecoveryTest" `
  --console=plain
```

## Artifact Locations

- memory scorecards: `Android/src/app/build/test-results/testDebugUnitTest/TEST-selfgemma.talk.domain.roleplay.usecase.RoleplayMemoryAcceptanceReportTest.xml`
- frontend benchmark traces/logs: `Android/src/macrobenchmark/build/outputs/connected_android_test_additional_output/`
- generated report: `docs/reports/roleplay-memory-acceptance-*.md`

## Maintenance Notes

- If the memory architecture changes, update the acceptance harness in the same patch. A new retrieval layer without new acceptance coverage is incomplete work.
- Keep the harness offline-safe. Do not add internet, remote embedding, or cloud-vector prerequisites to the acceptance path.
- When context budgeting rules change, rerun the acceptance report and inspect the injected memory token series first. Token drift is usually the earliest sign of a regression on 4k-class models.
