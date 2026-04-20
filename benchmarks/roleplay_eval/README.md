# Roleplay Eval Workspace

This workspace holds preparation assets for GemmaTavern roleplay benchmark work.

- `resources/external-resources.json`: tracked manifest of upstream benchmark repos to prepare locally
- `resources/external-resources.lock.json`: tracked lock file generated after local preparation
- `scripts/fetch-external-resources.ps1`: shallow-clones or refreshes upstream resources into the ignored `external/` cache
- `scripts/run-device-roleplay-eval.ps1`: host-side entrypoint that installs the benchmark build, pushes a scenario manifest, starts the on-device runner, polls status, and pulls artifacts
- `scripts/batch-run-device-roleplay-eval.ps1`: batch entrypoint that runs multiple manifests from the public-suite catalog, then aggregates report metrics
- `scripts/build-public-roleplay-suites.py`: deterministic generator for tracked public benchmark manifests
- `scripts/analyze-roleplay-eval-reports.py`: post-run analyzer that computes local QA/reference metrics and writes comparison outputs
- `scripts/judge-roleplay-eval.py`: optional host-side semantic judge that scores pulled run artifacts through a third-party LLM
- `scenarios/roleplay-eval.schema.json`: JSON schema for end-to-end roleplay eval manifests
- `scenarios/smoke/identity_memory_smoke.json`: first runnable smoke case for identity recall, summary, and memory extraction
- `scenarios/public/catalog.json`: tracked catalog of generated public benchmark mini suites
- `external/`: ignored local cache for upstream repos
- `cache/`: ignored space for downloaded datasets or converted artifacts
- `reports/`: ignored scoring outputs and run reports

Current preparation policy:

- benchmark code repos are cloned into `external/`
- large benchmark datasets are cached under `cache/huggingface/`
- very large judge or reward models are not fully downloaded during workspace prep; the script fetches metadata snapshots instead so we can lock URLs, commits, file lists, and storage requirements before wiring scorers

Current execution flow:

1. Host script generates a run-specific manifest copy under ignored `cache/generated-manifests/`
2. Host script installs `:app:installBenchmark`, pushes the manifest into `/sdcard/Android/media/selfgemma.talk/roleplay_eval/input/`, and launches the benchmark activity
3. The benchmark activity resolves a downloaded on-device model, initializes it if needed, and executes the real `SendRoleplayMessageUseCase`
4. The runner exports `run-status.json`, `run-summary.json`, `run-error.json`, and per-case artifacts under `/sdcard/Android/media/selfgemma.talk/roleplay_eval/runs/<runId>/`
5. Host script pulls the run directory into ignored `reports/<runId>/` and exits non-zero if the run failed or any assertions failed
6. If `config/judges.local.json` exists, the batch runner can run an optional host-side judge pass and write `judge-summary.json` plus `judge-comparison.json`

Example:

```powershell
Set-Location .
powershell -ExecutionPolicy Bypass -File .\benchmarks\roleplay_eval\scripts\run-device-roleplay-eval.ps1 `
  -ManifestPath .\benchmarks\roleplay_eval\scenarios\smoke\identity_memory_smoke.json `
  -DeviceSerial <adb-serial> `
  -ModelName <downloaded-model-name>
```

Observability contract:

- `run-status.json` is the live poll target and includes `state`, `phase`, `completedCases`, `totalCases`, `currentCaseId`, and `resolvedModelName`
- `run-summary.json` is the final machine-readable report with device/app/model metadata and all case assertion results
- per-case folders export raw `messages.json`, `role-memories.json`, `session-memories.json`, `summary.json`, and `events.json`, so failures are inspectable instead of judge-only black boxes

Public mini suites currently tracked:

- `characterbench_memory_consistency_public_mini.json`
- `characterbench_fact_accuracy_public_mini.json`
- `longmemeval_s_public_mini.json`
- `locomo_qa_public_mini.json`

Regenerate them:

```powershell
Set-Location .
python .\benchmarks\roleplay_eval\scripts\build-public-roleplay-suites.py --max-cases 8
```

Batch-run all public mini suites:

```powershell
Set-Location .
powershell -ExecutionPolicy Bypass -File .\benchmarks\roleplay_eval\scripts\batch-run-device-roleplay-eval.ps1 `
  -DeviceSerial <adb-serial> `
  -ModelName <downloaded-model-name> `
  -ContinueOnFailure
```

Run only the optional judge layer on already-pulled reports:

```powershell
Set-Location .
python .\benchmarks\roleplay_eval\scripts\judge-roleplay-eval.py `
  --reports-root .\benchmarks\roleplay_eval\reports `
  --config .\benchmarks\roleplay_eval\config\judges.local.json
```

Optional judge config:

- tracked template:
  - `benchmarks/roleplay_eval/config/judges.example.json`
- ignored local file:
  - `benchmarks/roleplay_eval/config/judges.local.json`

Current intended local provider:

- `DeepSeek`
  - model: `deepseek-reasoner`
  - purpose: semantic overlay for correctness, groundedness, persona retention, and naturalness
  - not the primary pass/fail signal

Current public-resource adaptation status:

- `CharacterBench`:
  - memory consistency and fact accuracy raw data are adapted into local, deterministic suites
  - judge-model-only dimensions still need `CharacterJudge` or a lighter local surrogate
- `LongMemEval`:
  - adapted as deterministic QA suites with local `qa_em_f1` scoring
- `LoCoMo`:
  - adapted as deterministic QA suites with local `qa_em_f1` scoring
- `CharacterEval`:
  - resource is prepared locally, but the public release does not directly provide target answers for our end-to-end runner
  - to recover paper-style comparability we still need a local `CharacterRM` or equivalent adaptation path
- `RoleBench`:
  - resource is locally available and suitable as future persona/reference suites
  - not yet wired into tracked manifests because the first priority was deterministic memory/QA coverage
