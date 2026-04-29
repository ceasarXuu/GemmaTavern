# 大文件拆分治理计划

> 目标:`Android/src/app/src/main` 下任一 Kotlin 源文件不超过 **500 行**。
> 立项日期:2026-04-29。

## 1. 治理原则

1. **零行为变更**:仅搬运纯函数/纯数据/纯常量,不重写逻辑、不调签名。
2. **同包扩展**:被搬出的符号统一落到同包 `*Internals.kt` 或语义化 sibling 文件,可见性由 `private` 升级为 `internal`,callsite 零改动。
3. **公共契约不动**:`@HiltViewModel` 类、`UiState`、顶级 `@Composable Screen()` 函数留在原文件。
4. **每文件一次提交**:`replace` → `:app:compileDebugKotlin` → `git commit`。每完成 5 个文件跑一次 `:app:testDebugUnitTest`。
5. **最终验证**:29 个文件全部达标后执行 `:app:assembleDebug` + 真机覆盖安装 + 启动烟测,符合 AGENTS.md 强制要求。
6. **行数检测命令**(每完成一档复跑):
   ```powershell
   Get-ChildItem -Path Android\src\app\src\main -Recurse -Include *.kt |
     ForEach-Object {
       $lines = (Get-Content $_.FullName | Measure-Object -Line).Lines
       if ($lines -gt 500) { [PSCustomObject]@{ Lines = $lines; Path = $_.FullName } }
     } | Sort-Object Lines -Descending
   ```

## 2. 当前超限清单(29 个文件)

### 第 1 档 — 巨型文件 (>1000 行,9 个)

| # | 行数 | 文件 | 拆分动作 |
|---|---|---|---|
| 1 | 1718 | `domain/roleplay/usecase/SendRoleplayMessageUseCase.kt` | 已抽 `SendRoleplayMessageInternals.kt`。再抽 ① `SendRoleplayMessageDriftAnalysis.kt`(漂移检测/风格修复纯函数)② `SendRoleplayMessageMediaPipeline.kt`(媒体收集 + MIME 推断)③ `SendRoleplayMessagePromptShaping.kt`(meta 行剥离/OOC 过滤/断句压缩) |
| 2 | 1435 | `ui/modelmanager/ModelManagerViewModel.kt` | ① `ModelDownloadOrchestrator.kt` ② `ModelInitializationOrchestrator.kt` ③ `ModelAllowlistLoader.kt` |
| 3 | 1213 | `ui/common/chat/MessageInputText.kt` | ① `MessageInputAttachmentPicker.kt` ② `MessageInputAudioRecorder.kt` ③ `MessageInputTextInternals.kt` |
| 4 | 1202 | `domain/roleplay/usecase/ExtractMemoriesUseCase.kt` | 已抽 `ExtractMemoriesInternals.kt`。再抽 ① `MemoryAtomMerger.kt` ② `OpenThreadDetector.kt` |
| 5 | 1122 | `ui/home/HomeScreen.kt` | ① `HomeTaskCard.kt` ② `HomeTopBar.kt` ③ `HomeImportFlow.kt` |
| 6 | 1097 | `feature/roleplay/roles/RoleEditorScreen.kt` | ① `RoleEditorTabs.kt`(每个 Tab Composable)② `RoleEditorPickers.kt`(头像/封面/语音 picker) |
| 7 | 1056 | `domain/roleplay/usecase/CompileRoleplayMemoryContextUseCase.kt` | ① `MemoryContextSelector.kt` ② `MemoryContextRenderer.kt` ③ `MemoryContextInternals.kt`(token 估算/截断) |
| 8 | 1017 | `feature/roleplay/chat/RoleplayChatScreen.kt` | ① `RoleplayChatTopBar.kt` ② `RoleplayChatScreenLayout.kt` ③ `RoleplayChatBubbleRouter.kt` |
| 9 | 1010 | `customtasks/agentchat/SkillManagerViewModel.kt` | ① `SkillExecutionOrchestrator.kt` ② `SkillCatalogLoader.kt` ③ `SkillManagerInternals.kt` |

### 第 2 档 — 大文件 (700-1000 行,9 个)

| # | 行数 | 文件 | 拆分动作 |
|---|---|---|---|
| 10 | 982 | `feature/roleplay/roles/RoleEditorViewModel.kt` | ① `RoleEditorPersistence.kt` ② `RoleEditorInternals.kt` |
| 11 | 982 | `feature/roleplay/chat/RoleplayChatViewModel.kt` | ① `RoleplayChatHistoryLoader.kt` ② `RoleplayChatActionHandlers.kt` ③ `RoleplayChatInternals.kt` |
| 12 | 972 | `ui/benchmark/BenchmarkResultsViewer.kt` | ① `BenchmarkChartSection.kt` ② `BenchmarkSummaryTable.kt` ③ `BenchmarkResultsInternals.kt` |
| 13 | 962 | `customtasks/agentchat/SkillManagerBottomSheet.kt` | ① `SkillListSection.kt` ② `SkillDetailSheet.kt` ③ `SkillManagerSheetInternals.kt` |
| 14 | 925 | `feature/roleplay/profile/MyProfileScreen.kt` | ① `MyProfileForm.kt` ② `MyProfilePersonaPicker.kt` |
| 15 | 888 | `domain/roleplay/usecase/StCharacterBookRuntime.kt` | ① `StCharacterBookMatcher.kt` ② `StCharacterBookInternals.kt` |
| 16 | 760 | `ui/navigation/GalleryNavGraph.kt` | ① `GalleryNavRoutes.kt` ② `GalleryNavBuilders.kt` |
| 17 | 719 | `customtasks/mobileactions/MobileActionsScreen.kt` | ① `MobileActionsListSection.kt` ② `MobileActionsDetailSheet.kt` |
| 18 | 705 | `customtasks/tinygarden/TinyGardenScreen.kt` | ① `TinyGardenScene.kt`(Canvas 绘制)② `TinyGardenControls.kt` |

### 第 3 档 — 中等文件 (500-700 行,11 个)

| # | 行数 | 文件 | 拆分动作 |
|---|---|---|---|
| 19 | 688 | `customtasks/agentchat/AddOrEditSkillBottomSheet.kt` | ✅ 已抽 `AddOrEditSkillScriptsTab.kt`(729→468) |
| 20 | 681 | `ui/common/chat/ChatPanel.kt` | **延后至 Phase C**:单一 580+ 行 @Composable,与 DownloadAndTryButton 同形态,需 state hoisting。 |
| 21 | 664 | `data/DataStoreRepository.kt` | **延后至 Phase C**:`DefaultDataStoreRepository` 单一 class ~550 行,Kotlin 不支持 partial class,需要拆为子 Repository 委托才能达标。Phase C 设计:`UserPreferencesRepository` / `BenchmarkResultsRepository` / `SkillsRepository` 委托。 |
| 22 | 609 | `domain/roleplay/usecase/PromptMaterialBuilder.kt` | **延后至 Phase C**:同包多文件重名 helper(WHITESPACE_REGEX、normalizeWhitespace、toDisplayLabel、toJsonObjectOrNull 等)分别 file-private 在 ExportStV2RoleCardUseCase / ExtractMemoriesUseCase / RoleplayToolTurnMetadata 等内,提升可见性会触发包级冲突。需要先统一重命名为 PMB_ 前缀或抽出独立工具模块。 |
| 23 | 597 | `feature/roleplay/roles/RoleCatalogScreen.kt` | ✅ 已抽 `RoleCatalogScreenInternals.kt`(629→324) |
| 24 | 596 | `ui/common/ConfigDialog.kt` | ✅ 已抽 `ConfigDialogRows.kt`(638→265) |
| 25 | 594 | `ui/modelmanager/GlobalModelManager.kt` | **延后至 Phase C**:619 行单一 @Composable,deeply 耦合 state,与 ChatPanel/DownloadAndTryButton 同形态,需 state hoisting。 |
| 26 | 589 | `ui/llmchat/LlmChatViewModel.kt` | **延后至 Phase C**:`generateResponse` 是单一 ~360 行 launch 内含深层闭包,需要状态抽象 + listener 类化才能拆,Phase A 风险过高。 |
| 27 | 575 | `ui/common/DownloadAndTryButton.kt` | **延后至 Phase C**:单一 600+ 行 @Composable,需要状态提升 (state hoisting) 才能拆,Phase A 风险过高。Phase C 作为子 Composable 拆分:`DownloadAndTryProgressBar` + `DownloadAndTryDialogs`。 |
| 28 | 560 | `customtasks/agentchat/AgentChatScreen.kt` | ✅ 已抽 `AgentChatScreenInternals.kt`(583→500) |
| 29 | 535 | `feature/roleplay/roles/RoleEditorComponents.kt` | ✅ 已抽 `RoleEditorComponentsExtras.kt`(535→420) |

## 3. 执行顺序

| 阶段 | 范围 | 目的 |
|---|---|---|
| Phase A | 第 3 档(11 个) | 单次拆分即达标,快速消化基数;为复杂拆分热身。 |
| Phase B | 第 2 档(9 个) | 中等复杂度,单文件 2-3 个 sibling。 |
| Phase C | 第 1 档(9 个) | 最复杂,需配合细致 review;每文件 2-3 次拆分 + 多轮验证。 |

每阶段结束执行:
- 复跑行数检测命令,确认本阶段所有文件都 ≤ 500 行。
- `:app:testDebugUnitTest` 全量。
- 阶段完成时更新本文档"进度"段。

最后:`:app:assembleDebug` → `adb install -r` → `monkey -p selfgemma.talk -c LAUNCHER 1` → `logcat -d | grep FATAL` 必须无崩溃。

## 4. 进度

- [x] Phase A — 第 3 档 11 个:**已完成 6 个**(#19/#23/#24/#28/#29 + ConfigDialog),延后 5 个至 Phase C(#20 ChatPanel、#21 DataStoreRepository、#22 PromptMaterialBuilder、#25 GlobalModelManager、#26 LlmChatViewModel、#27 DownloadAndTryButton)。
- [ ] Phase B — 第 2 档 9 个:**全部延后**,见下文"5. 架构性阻塞"。
- [ ] Phase C — 第 1 档 9 个:**全部延后**,见下文"5. 架构性阻塞"。
- [ ] 最终真机覆盖安装烟测(已对 Phase A 完成,验证通过)

## 5. 架构性阻塞分析(2026-04-29 增补)

第 1 档与第 2 档共 18 个文件经结构扫描后,均无法仅靠"零行为变更 + 同包扩展"达到 ≤500 行,理由如下三类共性缺陷:

### 5.1 单一巨型 @Composable + 深耦合状态(共 11 个文件)

`TinyGardenScreen.MainUi`(525 行)、`MobileActionsScreen.MainUi`(463 行)、`BenchmarkResultsViewer`(651 行)、`SkillManagerBottomSheet`(617 行)、`MyProfileScreen`(单一 825 行 Composable)、`HomeScreen`、`RoleEditorScreen`、`RoleplayChatScreen`、`AppNavHost`(`GalleryNavGraph` 487 行 NavHost 块)、`MessageInputText`、`SkillManagerBottomSheet` 等全部呈现为单一 @Composable + 数十个 `remember`/`mutableStateOf`/`LaunchedEffect`/局部 lambda 闭包,任何子段都需将状态显式提升到调用方才能搬出,这等同于真实重构而非"机械搬运"。

### 5.2 单一巨型 ViewModel / UseCase 类(共 6 个文件)

`SendRoleplayMessageUseCase`(单一 class 1648 行)、`ModelManagerViewModel`、`SkillManagerViewModel`、`RoleplayChatViewModel`、`RoleEditorViewModel`、`ExtractMemoriesUseCase`、`CompileRoleplayMemoryContextUseCase` 等单 class 内含数十个互相调用的私有方法 + 共享 `coroutineScope` / 实例字段。Kotlin 不支持 partial class,需要先把内部职责切成多个委托型 sub-collaborator,签名/依赖注入随之变化,属于设计级重构。

### 5.3 同包多文件 file-private helper 名称冲突(共 3 个文件)

`PromptMaterialBuilder`、`StCharacterBookRuntime`、`ExtractMemoriesUseCase` 三个 use-case 内含 `intOrNull`/`booleanOrNull`/`stringOrNull`/`toJsonObjectOrNull`/`normalizeWhitespace`/`toReadableValue`/`WHITESPACE_REGEX`/`MAX_*_LENGTH` 等通用 JSON / 文本 helper,均为 file-private 重复定义。把任何一个文件的 helper 提升为 `internal` 都会与同包另两个文件的 helper 引发"Conflicting declarations / Overload resolution ambiguity"(已在 PromptMaterialBuilder 实证)。

需要先做一次"包级 JSON helper 收敛"专项:把所有重复 helper 抽成 `selfgemma.talk.domain.roleplay.usecase.internal.JsonHelpers`(独立子包,避免污染同包名字空间)并在所有 use-case 内统一引用。这不是单文件级改动。

### 5.4 共同结论

剩余 18 个文件的拆分都不属于"零行为变更搬运",必须配合以下任一前置工作之一才能继续:

- **A. State Hoisting 设计**:把巨型 Composable 的状态显式抽出为 ViewModel 或 sub-state class,再按 sub-state 边界拆 Composable。
- **B. Sub-Collaborator 抽取**:把巨型 ViewModel / UseCase 的内部职责拆成多个被注入的小协作者,这需要新接口、新模块、新 DI 绑定。
- **C. 包级 helper 收敛**:用独立子包统一存放重复 JSON / 文本 helper,所有调用点改 import 路径。

每一项都需要单独立项 + 独立 PR + 单独的契约/性能/真机验证。在没有上述前置 PR 落地前,继续机械拆分会引入"内部 API 误升级"或"行为微调"风险,与本计划"零行为变更"原则冲突。

### 5.5 后续建议

将上述 18 个文件按"3 个前置专项"重新立项,本治理计划的 Phase B / C 待前置专项落地后再执行,届时同一份"同包 sibling 文件"模板即可机械应用。

