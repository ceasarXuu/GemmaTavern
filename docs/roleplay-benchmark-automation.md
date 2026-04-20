# Roleplay Benchmark Automation

## Goal

Benchmark the full GemmaTavern roleplay product loop on Android, not an isolated model API. The benchmark path must exercise:

- real model selection and initialization
- real prompt assembly and context-budget handling
- real session summary and memory extraction
- real repository persistence and event emission
- replayable assertions plus inspectable raw artifacts

For the acceptance gates that build on this automation substrate, see
`docs/roleplay-memory-acceptance-standard.md`.

## Implemented flow

### 1. Host-side orchestration

Entry script: `benchmarks/roleplay_eval/scripts/run-device-roleplay-eval.ps1`

Responsibilities:

- optionally run `:app:installBenchmark --no-daemon`
- resolve a single adb target device
- create a run-specific manifest copy in ignored `cache/generated-manifests/`
- optionally override `modelName` from CLI
- push the manifest into `/sdcard/Android/media/selfgemma.talk/roleplay_eval/input/<runId>.json`
- start `selfgemma.talk.performance.roleplayeval.RoleplayEvalActivity`
- poll `run-status.json` until `COMPLETED` or `FAILED`
- pull `/sdcard/Android/media/selfgemma.talk/roleplay_eval/runs/<runId>/` into ignored `benchmarks/roleplay_eval/reports/<runId>/`
- exit non-zero if:
  - the device run fails
  - any case assertions fail

### 2. On-device benchmark runner

Key files:

- `Android/src/app/src/benchmark/java/selfgemma/talk/performance/roleplayeval/RoleplayEvalActivity.kt`
- `Android/src/app/src/benchmark/java/selfgemma/talk/performance/roleplayeval/RoleplayEvalRunner.kt`
- `Android/src/app/src/benchmark/java/selfgemma/talk/performance/roleplayeval/RoleplayEvalStorage.kt`

Responsibilities:

- load and copy the input manifest
- resolve the benchmark model by priority:
  1. manifest `modelName`
  2. DataStore last-used LLM
  3. first downloaded LLM on device
- fall back to imported-model catalog if allowlist loading fails
- initialize the selected model if it is not already warm
- create a real role + real session in Room-backed repositories
- seed fixture turns if requested
- execute every test turn through the real `SendRoleplayMessageUseCase`
- export raw artifacts after each case
- update `run-status.json` across phases and case progress

### 3. Manifest contract

Schema:

- `benchmarks/roleplay_eval/scenarios/roleplay-eval.schema.json`

Current sample:

- `benchmarks/roleplay_eval/scenarios/smoke/identity_memory_smoke.json`

The manifest is intentionally product-level, not model-level. Each case describes:

- role card content
- optional user persona
- optional seeded conversation turns
- user turns to send
- turn-level assertions
- case-level assertions on summaries, memories, and session events

## Artifact contract

Device root:

- `/sdcard/Android/media/selfgemma.talk/roleplay_eval/`

Per-run outputs:

- `run-status.json`
  - live poll target
  - fields: `state`, `phase`, `completedCases`, `totalCases`, `currentCaseId`, `resolvedModelName`, `errorMessage`
- `run-summary.json`
  - final machine-readable run report
  - includes app version, package, device model, SDK level, resolved model metadata, and all case results
- `run-error.json`
  - structured failure dump with throwable class and stack trace
- `cases/<caseId>/case-result.json`
  - final verdict and flattened assertions for one case
- `cases/<caseId>/messages.json`
- `cases/<caseId>/role-memories.json`
- `cases/<caseId>/session-memories.json`
- `cases/<caseId>/summary.json`
- `cases/<caseId>/events.json`

This is the anti-black-box layer: every failed assertion can be traced back to raw messages, emitted events, and persisted memories.

## Public benchmark mapping plan

The current scaffold is the execution substrate. Public benchmarks should be adapted into this manifest contract instead of wiring each dataset directly to adb scripts.

### Implemented public mini suites

Tracked outputs:

- `benchmarks/roleplay_eval/scenarios/public/characterbench_memory_consistency_public_mini.json`
- `benchmarks/roleplay_eval/scenarios/public/characterbench_fact_accuracy_public_mini.json`
- `benchmarks/roleplay_eval/scenarios/public/longmemeval_s_public_mini.json`
- `benchmarks/roleplay_eval/scenarios/public/locomo_qa_public_mini.json`
- `benchmarks/roleplay_eval/scenarios/public/catalog.json`

Generation script:

- `benchmarks/roleplay_eval/scripts/build-public-roleplay-suites.py`

The first public batch intentionally prioritizes deterministic local scoring:

- `CharacterBench` memory consistency:
  - Chinese persona + dialogue continuity cases
  - bootstraps summary/memory artifacts from seeded history
  - local metric: `reference_token_f1`
- `CharacterBench` fact accuracy:
  - Chinese factual QA about character setting
  - local metric: `qa_em_f1`
- `LongMemEval`:
  - English long-memory QA
  - bootstraps summary/memory artifacts from seeded history before the final probe question
  - local metric: `qa_em_f1`
- `LoCoMo`:
  - English very long-term conversation QA
  - local metric: `qa_em_f1`

### Batch execution and analysis

Batch runner:

- `benchmarks/roleplay_eval/scripts/batch-run-device-roleplay-eval.ps1`

Post-run analyzer:

- `benchmarks/roleplay_eval/scripts/analyze-roleplay-eval-reports.py`

Outputs under `benchmarks/roleplay_eval/reports/`:

- per-run:
  - `analysis-summary.json`
  - `analysis-summary.md`
  - `judge-summary.json` when a local judge config is present
  - `judge-summary.md`
- cross-run:
  - `comparison.json`
  - `comparison.md`
  - `judge-comparison.json`
  - `judge-comparison.md`
  - `batch-summary.json` after batch execution
- formal local HTML report:
  - `benchmarks/roleplay_eval/reports/roleplay-benchmark-report.html`
  - generated by `benchmarks/roleplay_eval/scripts/render-roleplay-benchmark-report.py`
  - ignored by Git because it is a generated artifact
  - supports in-page English / Chinese switching with browser-language default plus local preference memory

This gives one-command execution plus machine-readable comparison across multiple runs/models on the same device workflow.

The batch runner now regenerates the local static HTML report automatically after `comparison.json` and `batch-summary.json` are refreshed, so the generated report stays aligned with the newest pulled run artifacts without reintroducing generated files into public docs.

### Optional LLM judge overlay

The benchmark can run an additional host-side semantic judge pass after deterministic analysis.

Current intended provider:

- `DeepSeek`
  - model: `deepseek-reasoner`
  - endpoint style: OpenAI-compatible chat completions
  - output mode: `response_format={"type":"json_object"}`

Tracked example config:

- `benchmarks/roleplay_eval/config/judges.example.json`

Ignored local config:

- `benchmarks/roleplay_eval/config/judges.local.json`

The local config may either:

- reference an environment variable through `apiKeyEnv`
- or contain `apiKey` directly, since the file is ignored by git

The current batch runner auto-detects `judges.local.json` unless `-SkipJudge` is specified. When the config exists:

1. deterministic analysis runs first
2. `judge-roleplay-eval.py` incrementally judges analyzed run folders
3. `judge-summary.json` is written per run
4. `judge-comparison.json` is refreshed
5. the static HTML report includes an `LLM Judge Overlay` section

This judge layer is auxiliary:

- deterministic metrics remain the primary regression signal
- the judge is used to add semantic readings for correctness, groundedness, persona retention, and naturalness
- benchmark pass/fail must not depend only on the judge

Recommended adapter order:

1. `CharacterEval`
   - resource is prepared locally
   - still needs CharacterRM or an equivalent local judge path because the public release is not a simple reference-answer QA set
2. `RoleBench`
   - good next source for persona/reference suites with lightweight local similarity metrics
3. `CharacterBench` remaining judge-only dimensions
   - wire once a smaller local surrogate for CharacterJudge is selected
4. `InCharacter`
   - evaluate once its scoring assumptions are mapped to local execution constraints

## What is already reusable

- public benchmark repos/datasets are prepared under `benchmarks/roleplay_eval/external/` and `cache/`
- heavy judge models are only locked as metadata for now, avoiding late-stage surprises about size/gating
- host/device protocol is stable enough that future converters only need to emit manifests and optional scorers
- the current public mini suites already provide a baseline matrix for:
  - persona factual accuracy
  - persona memory continuity
  - long-horizon QA recall
  - very long conversation QA recall

## Current verification status

- `:app:compileBenchmarkKotlin --no-daemon` passes after integrating the new benchmark source set
- PowerShell script parses successfully
- manifest schema JSON and sample manifest both parse successfully
- public-suite generator script runs successfully and emits four tracked mini suites
- report analyzer runs successfully against an empty reports root and writes comparison outputs
- no adb device was connected during this change set, so a real-device smoke run has not yet been executed in this session
