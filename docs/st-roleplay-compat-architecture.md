# ST 角色卡与聊天记录兼容架构设计

## 1. 目标

本设计用于指导 `selfgemma.talk` 角色系统向 SillyTavern（后文简称 ST）兼容演进，目标如下：

1. 完全兼容 ST 角色卡导入/导出。
2. 完全兼容 ST 聊天记录 `jsonl` 导入/导出。
3. 保留本项目的增强运行时能力，例如记忆系统、模型策略、agent 主动能力。
4. 避免把兼容逻辑、运行时逻辑、界面逻辑堆叠在单个文件中。

兼容的重点是“数据格式无损”和“编辑能力可达”，不是复制 ST 的前端实现。

## 2. 非目标

以下内容不作为第一阶段目标：

1. 完整复刻 ST 的 prompt 注入顺序。
2. 完整复刻 ST 的 regex script 执行系统。
3. 完整复刻 ST world info / lorebook 运行机制。
4. 完整复刻 ST 的 swipe UI 交互与所有前端细节。

这些能力第一阶段只要求：

1. 可导入。
2. 可查看和编辑。
3. 可原样导出。
4. 在本项目内做合理语义兼容。

## 3. 总体原则

### 3.1 双层模型

角色拆分为三层：

1. `CharacterCardCore`
   - ST 兼容内容层。
   - 负责导入导出。
2. `CharacterRuntimeProfile`
   - 本项目运行时增强层。
   - 负责模型、记忆、agent、安全策略。
3. `CharacterInteropState`
   - 兼容来源、原始字段保留、迁移说明、兼容警告。

### 3.2 Canonical Schema

所有外部格式先归一化为内部 canonical schema，再提供给 UI、导入导出和运行时使用。

### 3.3 Raw Preservation

不能识别的字段不丢弃，必须保留原始 JSON，尤其是：

1. `data.extensions.*`
2. `data.character_book.*`
3. `chat_metadata.*`
4. message `extra.*`
5. 未来 spec 扩展字段

### 3.4 Runtime Overlay

运行时增强能力不能反向污染 ST 核心字段。本项目特有配置通过 overlay 叠加，不篡改角色卡含义。

## 4. 领域模型设计

### 4.1 CharacterCardCore

用于承载 ST 卡的可交换内容：

```kotlin
data class CharacterCardCore(
  val spec: CardSpecVersion,
  val name: String,
  val description: String = "",
  val personality: String = "",
  val scenario: String = "",
  val firstMessage: String = "",
  val messageExample: String = "",
  val creatorNotes: String = "",
  val systemPrompt: String = "",
  val postHistoryInstructions: String = "",
  val alternateGreetings: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val creator: String = "",
  val characterVersion: String = "",
  val characterBook: CharacterBook? = null,
  val extensionsJson: String = "{}"
)
```

说明：

1. `extensionsJson` 第一阶段保持原始 JSON，避免过早强类型化。
2. `spec` 记录导入来源和默认导出目标参考。

### 4.2 CharacterBook

```kotlin
data class CharacterBook(
  val name: String? = null,
  val description: String? = null,
  val scanDepth: Int? = null,
  val tokenBudget: Int? = null,
  val recursiveScanning: Boolean? = null,
  val extensionsJson: String = "{}",
  val entries: List<CharacterBookEntry> = emptyList()
)
```

```kotlin
data class CharacterBookEntry(
  val id: Int,
  val keys: List<String>,
  val secondaryKeys: List<String> = emptyList(),
  val comment: String = "",
  val content: String = "",
  val constant: Boolean = false,
  val selective: Boolean = false,
  val insertionOrder: Int = 0,
  val enabled: Boolean = true,
  val position: String = "before_char",
  val extensionsJson: String = "{}"
)
```

### 4.3 CharacterRuntimeProfile

```kotlin
data class CharacterRuntimeProfile(
  val summary: String = "",
  val preferredModelId: String? = null,
  val modelParams: RuntimeModelParams = RuntimeModelParams(),
  val memoryPolicy: MemoryPolicy = MemoryPolicy(),
  val agentPolicy: AgentPolicy = AgentPolicy(),
  val safetyPolicy: RuntimeSafetyPolicy = RuntimeSafetyPolicy(),
  val promptPolicy: PromptPolicy = PromptPolicy(),
  val uiHints: CharacterUiHints = CharacterUiHints()
)
```

该模型仅本项目使用，不要求导出到 ST 核心字段。

### 4.4 CharacterInteropState

```kotlin
data class CharacterInteropState(
  val sourceFormat: SourceFormat,
  val sourceSpec: String? = null,
  val sourceSpecVersion: String? = null,
  val importedAt: Long? = null,
  val exportTargetDefault: ExportTarget = ExportTarget.ST_V2,
  val rawCardJson: String? = null,
  val rawUnknownTopLevelJson: String? = null,
  val rawUnknownDataJson: String? = null,
  val rawUnknownExtensionsJson: String? = null,
  val compatibilityWarnings: List<String> = emptyList(),
  val migrationNotes: List<String> = emptyList()
)
```

## 5. 聊天消息兼容模型

当前项目消息模型偏运行时，不足以完整表达 ST message。建议引入兼容视图。

```kotlin
data class StChatHeader(
  val chatMetadataJson: String = "{}"
)
```

```kotlin
data class StInteropMessage(
  val name: String,
  val isUser: Boolean,
  val isSystem: Boolean,
  val sendDate: String? = null,
  val message: String = "",
  val extraJson: String = "{}",
  val swipesJson: String? = null,
  val swipeId: Int? = null,
  val swipeInfoJson: String? = null,
  val forceAvatar: String? = null,
  val originalAvatar: String? = null,
  val genStarted: String? = null,
  val genFinished: String? = null
)
```

建议角色聊天记录在持久层保留双视图：

1. `EngineMessage`
   - 给当前聊天引擎使用。
2. `StInteropMessage`
   - 给 ST 导入导出使用。

这样能避免：

1. 为兼容 ST 把当前运行时消息模型污染成大杂烩。
2. 为维持当前运行时简洁而丢失 ST 字段。

## 6. 数据库存储设计

### 6.1 角色表

建议把当前扁平 `RoleEntity` 迁移为“少量基础列 + 三段 JSON”：

基础列：

1. `id`
2. `name`
3. `avatarUri`
4. `coverUri`
5. `builtIn`
6. `archived`
7. `createdAt`
8. `updatedAt`

核心 JSON：

1. `cardCoreJson`
2. `runtimeProfileJson`
3. `interopStateJson`

辅助索引列：

1. `creator`
2. `characterVersion`
3. `tags`
4. `sourceFormat`
5. `sourceSpec`

理由：

1. `character_book` 和 `extensions` 是嵌套结构，不适合继续硬拆平。
2. ST spec 后续变更时，JSON 兼容更稳。
3. 本项目运行时增强字段未来还会增长。

### 6.2 消息表

消息表保留现有运行时列，同时增加兼容列：

1. `displayName`
2. `interopJson`
3. `variantJson`
4. `sendDate`
5. `genStartedAt`
6. `genFinishedAt`

第一阶段不建议把 ST message 的所有字段拆成大量独立列，优先用 `interopJson` 保真。

### 6.3 会话表

新增：

1. `interopChatMetadataJson`
2. `originChatFileName`
3. `importSource`
4. `exportFormatDefault`

## 7. 包与文件拆分设计

兼容实现不能继续堆在现有 `RoleEditorViewModel`、`PromptAssembler` 或某个大 util 文件中。建议新增如下包结构。

### 7.1 domain 层

路径建议：

- `domain/roleplay/interop/model`
- `domain/roleplay/interop/usecase`

建议文件拆分：

1. `CardCoreModels.kt`
2. `CardInteropModels.kt`
3. `ChatInteropModels.kt`
4. `CardCompatibilityReport.kt`
5. `NormalizeImportedCardUseCase.kt`
6. `NormalizeImportedChatUseCase.kt`
7. `ValidateStCardUseCase.kt`
8. `ExportStCardUseCase.kt`
9. `ExportStChatUseCase.kt`

### 7.2 data 层

路径建议：

- `data/roleplay/interop/parser`
- `data/roleplay/interop/serializer`
- `data/roleplay/interop/mapper`
- `data/roleplay/interop/repository`

建议文件拆分：

1. `StCardJsonParser.kt`
2. `StCardPngParser.kt`
3. `StCardSerializer.kt`
4. `StChatJsonlParser.kt`
5. `StChatJsonlSerializer.kt`
6. `CardCoreJsonMapper.kt`
7. `ChatInteropMapper.kt`
8. `RoleMigrationMapper.kt`

### 7.3 feature 层

路径建议：

- `feature/roleplay/roles/editor/model`
- `feature/roleplay/roles/editor/section`
- `feature/roleplay/roles/importexport`

建议文件拆分：

1. `RoleEditorUiState.kt`
2. `RoleEditorFieldState.kt`
3. `RoleEditorViewModel.kt`
4. `RoleEditorScreen.kt`
5. `RoleBasicSection.kt`
6. `RoleStCardSection.kt`
7. `RoleKnowledgeSection.kt`
8. `RoleRuntimeSection.kt`
9. `RoleInteropSection.kt`
10. `RoleImportExportViewModel.kt`

### 7.4 prompt / runtime 层

当前 `PromptAssembler.kt` 会越来越重，建议提前拆分：

1. `PromptContextAssembler.kt`
2. `PromptInstructionAssembler.kt`
3. `PromptHistoryAssembler.kt`
4. `PromptMemoryAssembler.kt`
5. `PromptCardLoreAssembler.kt`

`PromptAssembler.kt` 只保留 orchestration。

## 8. 角色字段映射规则

### 8.1 当前项目旧字段 -> 新 canonical

1. `name` -> `core.name`
2. `openingLine` -> `core.firstMessage`
3. `systemPrompt` -> `core.systemPrompt`
4. `tags` -> `core.tags`
5. `exampleDialogues` -> `core.messageExample`
6. `summary` -> 优先迁入 `core.description`
7. `personaDescription` -> 优先迁入 `core.personality`
8. `worldSettings` -> 优先迁入 `core.scenario`
9. `safetyPolicy` -> `runtime.safetyPolicy`
10. `defaultModelId/defaultTemperature/defaultTopP/defaultTopK/enableThinking` -> `runtime.modelParams`
11. `summaryTurnThreshold/memoryEnabled/memoryMaxItems` -> `runtime.memoryPolicy`

### 8.2 推断生成策略

迁移时允许进行有限推断，但必须记录到 `migrationNotes`：

1. `summary -> description`
2. `personaDescription -> personality`
3. `worldSettings -> scenario`
4. `exampleDialogues.joinToString("\n\n") -> messageExample`

## 9. 导入设计

### 9.1 角色卡导入

导入管线拆分为独立步骤：

1. `DetectCardFileTypeUseCase`
2. `ReadRawCardPayloadUseCase`
3. `ValidateStCardUseCase`
4. `NormalizeImportedCardUseCase`
5. `PersistImportedCardUseCase`

第一阶段支持：

1. `png`
2. `json`
3. `v1`
4. `v2`
5. `v3`

### 9.2 聊天记录导入

导入管线：

1. `ReadJsonlChatUseCase`
2. `ValidateStChatUseCase`
3. `NormalizeImportedChatUseCase`
4. `PersistImportedChatUseCase`

第一阶段目标仅支持 ST 原生 `jsonl`，不在本阶段顺手兼容其他平台聊天格式。

## 10. 导出设计

### 10.1 角色卡导出模式

1. `ST 标准导出`
   - 只导出 ST 核心内容。
2. `ST 扩展导出`
   - 在 `data.extensions.selfgemma.*` 中带出本项目可共享增强配置。

默认导出模式为 `ST 标准导出`。

### 10.2 角色卡导出格式

第一阶段目标：

1. `json` 导出为 ST `v2`
2. `png` 导出为带 ST metadata 的角色卡

### 10.3 聊天导出格式

第一阶段目标：

1. 导出 ST `jsonl`
2. 第一行保留 `chat_metadata`
3. 后续每条消息按 ST message 结构输出

## 11. 编辑页设计

编辑页拆分为五个 section，而不是继续堆单页表单。

### 11.1 基础

1. 名称
2. 头像
3. 封面
4. 标签
5. 作者
6. 版本

### 11.2 ST 角色卡

1. Description
2. Personality
3. Scenario
4. First Message
5. Alternate Greetings
6. Message Example
7. Creator Notes
8. System Prompt
9. Post-History Instructions

### 11.3 知识设定

1. Character Book 概览
2. Entry 列表
3. Entry 编辑
4. 原始 extensions JSON

### 11.4 运行时能力

1. 默认模型
2. Temperature / TopP / TopK / Thinking
3. Memory Policy
4. Agent Policy
5. Safety Policy

### 11.5 兼容与导入导出

1. 导入来源
2. 当前 spec
3. 兼容告警
4. 原始卡 JSON
5. 导出目标
6. 验证结果

## 12. Prompt 运行时映射

当前 `PromptAssembler` 不能继续直接读取旧 `RoleCard` 扁平字段，后续改为统一消费：

```kotlin
PromptInput(
  core = CharacterCardCore,
  runtime = CharacterRuntimeProfile,
  summary = SessionSummary?,
  memories = List<MemoryItem>,
  characterBookContext = List<CharacterBookEntry>,
  recentMessages = List<Message>
)
```

建议注入顺序：

1. `core.systemPrompt`
2. runtime safety / agent guardrails
3. `description`
4. `personality`
5. `scenario`
6. `character_book` 命中内容
7. `summary`
8. `memories`
9. recent messages
10. `postHistoryInstructions`

## 13. 日志与兼容诊断

每次导入导出必须留结构化日志，至少包含：

### 13.1 角色导入日志

1. 文件类型
2. spec / specVersion
3. 角色名
4. warning 数量
5. unknown 字段数量
6. 是否发生迁移推断

### 13.2 聊天导入日志

1. message 数量
2. swipe 数量
3. hidden message 数量
4. extra payload 数量

### 13.3 导出日志

1. 导出目标格式
2. 导出消息数
3. 导出 swipe 数
4. 是否包含 selfgemma 扩展

## 14. 分阶段实施顺序

### Phase 1

1. 建 canonical schema
2. 建 Room 新列
3. 建迁移 mapper
4. 新旧角色数据双读

### Phase 2

1. ST 角色卡 `json/png` 导入
2. ST `v2` 导出
3. PNG metadata 导出
4. 兼容诊断

### Phase 3

1. ST `jsonl` 导入
2. ST `jsonl` 导出
3. swipes / extra / chat_metadata 保留

### Phase 4

1. 编辑页分 section 重构
2. Character Book 编辑器
3. Alternate Greetings 列表编辑
4. Raw JSON viewer

### Phase 5

1. Character Book 检索接入 prompt
2. depth prompt 语义兼容
3. regex script 分阶段支持

## 15. 关键约束

1. 不要把 ST 兼容逻辑写进一个 `StCompatUtils.kt` 大杂烩文件。
2. 不要把导入解析、canonical 映射、持久化、UI 状态写进同一个 ViewModel。
3. 不要把所有 extensions 强制转成强类型；先保留 raw JSON。
4. 不要让本项目运行时策略污染 ST 核心内容层。
5. 不要为了省事在 `PromptAssembler.kt` 中继续累加条件分支。

本设计的重点不是一次性支持所有能力，而是先把边界划清，让后续每一步都能独立演进和回滚。
