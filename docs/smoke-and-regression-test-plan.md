# GemmaTavern 冒烟与回归测试方案

本文定义每次变更后的最低验证门槛，以及按风险扩展的回归测试矩阵。目标不是把所有测试都塞进一次提交前检查，而是先用短链路冒烟暴露编译、启动、核心会话和持久化边界的严重问题，再把时间投入到对应功能域的回归。

## 核心功能边界

GemmaTavern 是一个单 Android 客户端应用，前端、领域编排、本地推理和持久化都在端侧完成。测试应围绕用户实际闭环，而不是只围绕类或目录。

| 功能域 | 用户闭环 | 关键风险 |
| --- | --- | --- |
| 应用启动与导航 | 安装/覆盖安装后进入主界面，角色扮演各 Tab 与二级页可达 | 冷启动崩溃、路由参数失效、返回栈错误 |
| 模型管理 | 导入、本地模型配置、下载状态、模型初始化与切换 | 配置无法保存、模型状态卡死、初始化竞态 |
| 角色与人设 | 创建/编辑角色卡、用户档案、头像/媒体、SillyTavern 兼容导入导出 | 多语言文案缺失、媒体解析失败、兼容 JSON 破坏 |
| 会话列表 | 创建、归档、恢复、进入聊天详情、导出调试包 | Room 查询错误、恢复后目标 Tab 错误、发布包反序列化崩溃 |
| 聊天运行时 | 发送消息、流式输出、中断/合并、文本/语音/图片输入能力 | 推理生命周期死锁、输入 affordance 不一致、长上下文溢出 |
| 记忆系统 | 语义记忆、开放线程、摘要压缩、上下文预算、纠偏 | 旧记忆污染、重复提问、预算超限、上下文遗漏 |
| 工具调用与外部证据 | 设备时间、电量、网络、位置、日历、天气、地点、百科等工具按权限注册并注入 External Evidence | 宿主层越权触发、权限未授予却暴露工具、外部事实污染长期记忆 |
| 发布包 | minify 后稳定 JSON、keep rules、权限面、侧载升级 | debug 通过但 release 崩溃、签名不一致、权限面膨胀 |

## 每次变更后的冒烟门禁

默认命令从仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\Android\src\scripts\run-smoke-tests.ps1
```

没有设备时可以先跑主机冒烟：

```powershell
powershell -ExecutionPolicy Bypass -File .\Android\src\scripts\run-smoke-tests.ps1 -SkipDevice
```

冒烟门禁包含四段：

1. `:app:compileDebugKotlin`：最快暴露 Compose、资源、多语言字符串、Hilt/Kotlin 编译问题。
2. 定向 `:app:testDebugUnitTest`：覆盖路由、聊天 ViewModel、会话列表、角色扮演 turn、记忆上下文、记忆抽取、发布 JSON 映射、模型管理。
3. `:app:assembleDebug`：确认可安装 APK 生成。
4. 可用设备上的 `adb install -r`、`am start -W`、logcat 崩溃扫描、UI hierarchy dump：确认覆盖安装后能冷启动到真实界面。

冒烟失败处理规则：

- 编译失败优先修复资源、生成代码、DI、Kotlin 类型问题，不进入人工验证。
- 定向测试失败先看对应功能域，不用先跑全量测试浪费时间。
- `assembleDebug` 失败且日志含 Kotlin cache/file lock，先执行 `.\gradlew.bat --stop` 后串行重试一次。
- 设备安装或启动失败时，保留脚本生成的 `Android/src/build/smoke-tests/<timestamp>/` 日志，再判定是否为设备状态、签名不兼容或应用崩溃。

## 回归测试矩阵

| 变更类型 | 必跑回归 | 设备/人工补充 |
| --- | --- | --- |
| UI 文案、Compose 布局、导航 | `RoleplayRoutesTest`、相关 Screen/ViewModel 测试、`:app:compileDebugKotlin` | 覆盖安装，进入受影响页面；涉及文字必须检查 `values`、`values-en`、`values-zh-rCN`、`values-ja`、`values-ko` |
| 聊天输入、输出、中断、媒体 | `RoleplayChatViewModelTest`、`RoleplayChatScreenTest`、`MessageInputTextTest`、`RunRoleplayTurnUseCaseTest`、`SendRoleplayMessageUseCaseTest` | 真机发送一轮消息；检查文本、语音、图片入口在输出中状态一致 |
| 记忆、摘要、上下文预算 | `CompileRoleplayMemoryContextUseCaseTest`、`ExtractMemoriesUseCaseTest`、`SummarizeSessionUseCaseTest`、`ContextBudgetPlannerTest`、`RoleplayMemoryAcceptanceReportTest` | 运行 `run-roleplay-memory-acceptance.ps1`；检查报告里的召回、污染、预算指标 |
| 工具调用、外部证据、权限 | 对应 ToolTest、`RunRoleplayTurnUseCaseTest`、`PromptAssemblerTest`、`WriteRoleplayDebugBundleAndroidTest` | 真机验证权限未授予时工具不注册，授予后事实进入 External Evidence 而不是稳定摘要 |
| Room schema、仓储、迁移 | 受影响 repository/DAO 单元测试、`:app:installDebugAndroidTest`、相关 androidTest | 覆盖安装保留旧数据启动；不要依赖 `run-as sqlite3` |
| SillyTavern/角色卡/聊天记录兼容 | `StV2CardParserTest`、`StPngRoleCardCodecTest`、`StChatJsonlParserTest`、`RoleplayMappersTest`、互操作 UseCase 测试 | 用样例卡导入、创建会话、再导出调试包 |
| 模型管理与推理生命周期 | `ModelManagerViewModelTest`、`LlmChatModelHelperTest`、`LlmChatOverflowRecoveryTest` | 真实导入模型，修改配置，切换/重新初始化，确认无 60 秒卡死 |
| 发布包、ProGuard、持久化 JSON | `:app:testDebugUnitTest`、`:app:lintRelease`、`:app:assembleRelease`、`RoleplayMappersTest` | `adb install -r app-release.apk` 后进入聊天详情，检查 logcat 无 release-only crash |
| 性能或启动体验 | `run-frontend-perf.ps1 -Suite startup/core/stress` | 使用固定 benchmark 设备，确认 benchmark 指标不是空样本 |

## 分层节奏

| 层级 | 触发时机 | 命令 |
| --- | --- | --- |
| Smoke | 每次代码或资源变更后 | `run-smoke-tests.ps1` |
| Targeted Regression | 冒烟通过后，按变更类型选择 | 上方矩阵中的定向测试 |
| Full Host Regression | 合并前、发布前、跨域改动后 | `.\gradlew.bat :app:testDebugUnitTest` |
| Release Regression | 版本发布、minify/持久化/权限改动后 | `.\gradlew.bat :app:lintRelease; .\gradlew.bat :app:assembleRelease` |
| Device Regression | UI、数据库、导出、权限、发布包改动后 | `adb install -r`、`am start -W`、logcat、目标页面手动验证 |
| Benchmark/Acceptance | 记忆质量、启动、性能、长会话改动后 | `run-roleplay-memory-acceptance.ps1` 或 `run-frontend-perf.ps1` |

## 日志与证据留存

- 冒烟脚本日志写入 `Android/src/build/smoke-tests/<timestamp>/`，该目录是构建产物，不进入 Git。
- 回归失败时，最终结论必须引用具体日志：Gradle log、JUnit XML、logcat、UI hierarchy dump、debug export bundle 或 benchmark instrumentation log。
- 修复 bug 后，先用同一个命令复现失败，再用同一个命令确认通过；不要把“跑了更宽的测试”当成原失败已不可复现。
- 涉及启动、安装、环境配置、日志抓取、打包、上传的易错流程，应把可复用经验沉淀到 `DEVELOPMENT.md`、`RELEASING.md` 或本文件，而不是只留在一次性聊天记录里。

## CI 与本地职责

GitHub Actions 当前覆盖 Android push/PR 的 `testDebugUnitTest`、`lintRelease`、`assembleRelease`。本地冒烟仍然必要，因为它额外覆盖 Windows/Gradle cache 行为、真机覆盖安装、冷启动、logcat 和 UI hierarchy。CI 负责防止主线破坏，本地冒烟负责在变更后尽早暴露会浪费人工验证时间的严重问题。
