# Problem P-001: model import and online model list are unavailable
- Status: fixed
- Created: 2026-04-24 21:36
- Updated: 2026-04-24 21:56
- Objective: Identify and fix why the release app cannot show online models, imported local models disappear from the list, and chat reports missing models.
- Symptoms:
  - User reports the model import page does not show online models.
  - User reports local model import shows success but the imported model is absent from the model list.
  - User reports chat says a model is missing.
- Expected behavior:
  - Online models render on the model import page when available.
  - A successful local import appears in the model list.
  - Chat can resolve an installed or imported model instead of reporting missing model.
- Actual behavior:
  - Model sources appear empty or unresolved after import.
- Impact:
  - The app cannot be used for chat because no model can be selected or resolved.
- Reproduction:
  - Open the installed 0.1.1 release app, visit model import/model management, import a local model, then open chat.
- Environment:
  - Windows PowerShell, D:\GemmaTavern, release tag 0.1.1 after startup-crash fix, installed package selfgemma.talk versionName 0.1.1.
- Known facts:
  - The 0.1.1 release app requests model_allowlists/0_1_1.json.
  - The repository did not contain model_allowlists/0_1_1.json before the fix.
  - Release logcat shows Gson/R8 failure while parsing a cached allowlist from disk.
  - When allowlist loading fails, the old code leaves the model manager state without published tasks, so imported models cannot be attached to chat tasks.
  - After fixing allowlist parsing, the imported model is read from DataStore and marked SUCCEEDED.
  - Release model initialization then aborts in LiteRT LM JNI because SamplerConfig.getTopK() is obfuscated.
  - Keeping LiteRT LM Java/Kotlin API names lets the imported model initialize successfully in release.
- Ruled out:
  - none
- Fix criteria:
  - Online model definitions are available in release.
  - A successful local import is persisted and visible after returning to the model list.
  - Chat resolves the available model instead of reporting missing model.
- Current conclusion: The release package had missing versioned model allowlist metadata and release R8 rules were too narrow for Gson and LiteRT LM JNI.
- Related hypotheses:
  - H-001
  - H-002
  - H-003
  - H-004
- Resolution basis:
  - H-002 + H-003 + H-004 + E-005
- Close reason:
  - not closed

## Hypothesis H-001: release model catalog or imported-model persistence is unavailable
- Status: unverified
- Parent: P-001
- Claim: The model list and chat both depend on a shared catalog/persistence path that is empty or unreadable in the installed release build.
- Layer: root-cause
- Factor relation: unknown
- Depends on:
  - none
- Rationale:
  - Online models, imported local models, and chat model resolution are distinct UI surfaces but likely share the same model repository and DataStore-backed imported model state.
- Falsifiable predictions:
  - If true: code inspection or logs will show the catalog/imported model source is empty, failing to parse, or filtered out in release.
  - If false: each symptom will have separate code paths and independent failures.
- Verification plan:
  - Inspect model catalog loading, imported model persistence, and chat model resolution code, then capture logs around the failing UI flow if needed.
- Related evidence:
  - none
- Conclusion: Unverified until code and device logs are inspected.
- Next step: Inspect model loading and persistence code.
- Blocker:
  - none

## Hypothesis H-002: 0.1.1 online model allowlist is missing
- Status: confirmed
- Parent: P-001
- Claim: The release app requests a version-specific online allowlist for 0.1.1, but that JSON file is missing from the repository.
- Layer: root-cause
- Factor relation: part_of
- Depends on:
  - H-001
- Rationale:
  - ModelManagerViewModel builds the allowlist URL from BuildConfig.VERSION_NAME.
- Falsifiable predictions:
  - If true: the release logs will show a request for model_allowlists/0_1_1.json, and the repo will not contain that file.
  - If false: the file will exist or the release app will request a different URL.
- Verification plan:
  - Compare release logcat URL with the repository model_allowlists directory.
- Related evidence:
  - E-001
  - E-002
- Conclusion: Confirmed by release logs and repository contents.
- Next step: Add model_allowlists/0_1_1.json.
- Blocker:
  - none

## Hypothesis H-003: release R8 breaks Gson parsing for model allowlist classes
- Status: confirmed
- Parent: P-001
- Claim: Release minification removes or obfuscates generic type/field metadata needed by Gson to parse model allowlist JSON.
- Layer: root-cause
- Factor relation: part_of
- Depends on:
  - H-001
- Rationale:
  - The release log names the Gson R8 abstract-class failure during disk allowlist parsing.
- Falsifiable predictions:
  - If true: logcat will show a Gson R8 troubleshooting error while reading model allowlist JSON.
  - If false: allowlist parsing will not fail with an R8/Gson metadata error.
- Verification plan:
  - Add Gson keep rules for allowlist data classes and generic signatures, then rebuild release and verify parsing succeeds.
- Related evidence:
  - E-003
- Conclusion: Confirmed by release logcat.
- Next step: Add release keep rules and validate with rebuilt release APK.
- Blocker:
  - none

## Evidence E-001: release allowlist URL
- Related hypotheses:
  - H-002
- Direction: supports
- Type: log
- Source: adb logcat for AGModelManagerViewModel
- Raw content:
  ```text
  Loading model allowlist from internet. Url: https://raw.githubusercontent.com/ceasarXuu/GemmaTavern/refs/heads/main/model_allowlists/0_1_1.json
  Failed to load model allowlist from internet. Trying to load it from disk
  ```
- Interpretation: The installed 0.1.1 release app requests a 0_1_1 allowlist and falls back after the online request fails.
- Time: 2026-04-24 21:37

## Evidence E-002: repository allowlist versions
- Related hypotheses:
  - H-002
- Direction: supports
- Type: code-location
- Source: model_allowlists directory listing
- Raw content:
  ```text
  1_0_4.json ... 1_0_11.json
  no 0_1_1.json
  ```
- Interpretation: The requested 0.1.1 allowlist file was absent before the fix.
- Time: 2026-04-24 21:38

## Evidence E-003: release Gson R8 parse failure
- Related hypotheses:
  - H-003
- Direction: supports
- Type: log
- Source: adb logcat for AGModelManagerViewModel
- Raw content:
  ```text
  failed to read model allowlist from disk
  Abstract classes can't be instantiated! Adjust the R8 configuration or register an InstanceCreator or a TypeAdapter for this type.
  See https://github.com/google/gson/blob/main/Troubleshooting.md#r8-abstract-class
  ```
- Interpretation: Even when cached JSON exists, the minified release cannot parse it with Gson without additional keep rules.
- Time: 2026-04-24 21:38

## Hypothesis H-004: release R8 breaks LiteRT LM JNI method lookups
- Status: confirmed
- Parent: P-001
- Claim: Release minification renames LiteRT LM Java/Kotlin API methods that native code calls through JNI, causing imported model initialization to abort.
- Layer: root-cause
- Factor relation: part_of
- Depends on:
  - H-003
- Rationale:
  - After model list parsing succeeds, the next failure is a native abort looking for SamplerConfig.getTopK().
- Falsifiable predictions:
  - If true: release logcat will show a JNI abort for a missing LiteRT LM Java method, and mapping will show LiteRT LM classes/methods obfuscated.
  - If false: initialization will fail for model file or runtime reasons without missing Java method names.
- Verification plan:
  - Keep com.google.ai.edge.litertlm classes and methods in release, rebuild, install, and verify startup/model preload no longer aborts at JNI method lookup.
- Related evidence:
  - E-004
- Conclusion: Confirmed by release logcat showing missing getTopK JNI method.
- Next step: Add LiteRT LM keep rules and validate with release APK.
- Blocker:
  - none

## Evidence E-004: LiteRT LM JNI method lookup abort
- Related hypotheses:
  - H-004
- Direction: supports
- Type: log
- Source: adb logcat after fixed allowlist parsing
- Raw content:
  ```text
  Preloading last used LLM model during startup animation: gemma-4-E4B-it.litertlm
  Initializing model 'gemma-4-E4B-it.litertlm'...
  Pending exception java.lang.NoSuchMethodError: no non-static method "Lcom/google/ai/edge/litertlm/SamplerConfig;.getTopK()I"
  JNI DETECTED ERROR IN APPLICATION: mid == null
  from long com.google.ai.edge.litertlm.LiteRtLmJni.nativeCreateConversation(...)
  ```
- Interpretation: Model resolution now reaches runtime initialization, but release obfuscation breaks native LiteRT LM method lookup.
- Time: 2026-04-24 21:48

## Evidence E-005: release model list and imported model validation
- Related hypotheses:
  - H-002
  - H-003
  - H-004
- Direction: supports
- Type: fix-validation
- Source: rebuilt release APK, adb install, launch, and logcat
- Raw content:
  ```text
  adb install -r Android\src\app\build\outputs\apk\release\app-release.apk
  Success
  Allowlist: ModelAllowlist(models=[...])
  stored imported model:
  file_name: "gemma-4-E4B-it.litertlm"
  model download status: {... gemma-4-E4B-it.litertlm=ModelDownloadStatus(status=SUCCEEDED, ...)}
  Preloading last used LLM model during startup animation: gemma-4-E4B-it.litertlm
  Initializing model 'gemma-4-E4B-it.litertlm'...
  Model 'gemma-4-E4B-it.litertlm' initialized successfully
  ```
- Interpretation: The release app now parses model metadata, restores the imported local model into the model list state, and initializes the imported model instead of reporting it missing or aborting in JNI.
- Time: 2026-04-24 21:56
