# 纯记 PureNote — AI 协作规则

## 0. 开工：按需读取，范围明确

- 新任务读取本文件，检查 git status 和分支；同一任务中未变化的规则不反复读取。只读咨询不登记任务、不构建。
- 修改前查看 wiki/TASK_PROGRESS.md 中未完成且相关的条目。保留多 AI 协作检查：真实重叠占用需协调，已完成或待开始记录不是锁；不覆盖他人未提交修改。
- 独立任务开始时确认远端基线；有本地修改时不自动 pull/重置，可 fetch 后比较。独立目录也要检查远端更新。
- 首轮定位入口和符号，按需读代码片段及直接依赖。不默认全仓扫描或反复输出整文件；不读取构建产物、APK、.gradle/、完整日志。
- wiki/DECISIONS.md 按主题检索；需要接续历史时先看 CHANGELOG 最近 5 条，必要时扩展。CHANGE_ARCHIVE 仅发布、追溯或用户要求时读取；同步功能才读 SYNC_DESIGN。
- 需求含糊（如“优化一下”）时每轮最多问 3 个影响体验的问题，给具体选项和推荐。让用户选择行为及验收标准，技术实现由 AI 判断；明确小任务直接执行。
- 动手前简述目标、预计文件及验收方式。文件数量不设确认门槛；不借小修改启动全项目重构或路线图。默认单智能体，需要扩大范围时说明原因。
- 开始/结束各更新一次任务状态，完成一批相关改动写一条简短 CHANGELOG；仅改变既定决策才更新 DECISIONS。不逐补丁重写历史。
- pre-work-check Skill 仅补充多工具交接，不重复执行已经完成的检查。

## 1. 项目事实

- 仓库 seruabai/xmnote，产品名“纯记 PureNote”，包名 com.purenote.local。
- Android 本地优先笔记/待办，当前无网络权限。Kotlin、Compose + Material 3，单 Activity、单模块，手写 SQLite，无 Room/Hilt/Retrofit。
- 版本以 app/build.gradle.kts 为唯一事实源，不在规则文件重复维护版本号。
- 功能包括文本/清单、图片、分类、搜索、置顶/多选、废纸篓、提醒、悬浮速记、分享导入。
- 已定风格为奶油暖黄与奶油纸，支持深浅主题；未经用户要求不推翻配色。

## 2. 按功能定位

下列路径相对 app/src/main/java/com/purenote/local/：

| 任务 | 优先入口 |
| --- | --- |
| 导航、分享、外部打开 | MainActivity.kt、ui/AppRoot.kt（已有 AnimatedContent 转场） |
| 首页、笔记卡片、编辑 | ui/HomeScreen.kt、ui/NoteCard.kt、ui/EditorScreen.kt |
| 待办列表、卡片、编辑、滑动、提醒选择 | ui/TodoPane.kt、ui/TodoCard.kt、ui/TodoEditSheet.kt、ui/TodoSwipe.kt、ui/TodoRemindDialog.kt |
| 悬浮把手、面板、手势、速记保存 | notify/QuickCaptureService.kt（先定位相关函数） |
| 状态/业务、存储、提醒 | NoteViewModel.kt、data/NoteRepository.kt、data/Db.kt、notify/ReminderScheduler.kt |
| 主题、设置、分类、废纸篓 | ui/theme/Theme.kt、ui/SettingsScreen.kt、ui/FoldersScreen.kt、ui/TrashScreen.kt |

## 3. 修改边界

- 沿用现有分层，UI 不直接操作 SQLite；保留数据，数据库变化必须有兼容迁移。
- 大文件不等于每次必须重构。按需求改相关函数，仅在职责明确且本次需要时提取组件。
- 不擅自增加框架、云能力、功能或改写既有交互；未来计划不代表当前授权。
- 简短中文注释只解释必要原因，不在回复中重复粘贴已写入的完整代码。
- 同一错误连续两次修复仍失败时先总结新证据和阻碍，再决定下一步，不盲目重试。

## 4. 按风险验证

- 文档/规则：检查引用、冲突和 git diff --check，不构建 Android。
- CI 工作流：检查语法、触发条件、权限和产物路径，并验证一次实际运行。
- Kotlin/Compose/资源：验证相关编译；纯逻辑变更加对应单元测试。UI 行为需实机或模拟器验收，单元测试不能代替交互验证。
- 数据库、提醒、Service、Manifest 等高风险变化：增加相应回归检查；需要安装或发布时构建 APK。
- 基线构建只在基线未知、环境变化或需区分原有失败时运行；同一代码状态已通过的检查不重复跑。
- 日志保存在文件中，返回退出码和相关错误摘要；必要时读取错误附近上下文，不固定截断而漏掉原因。
- 如实报告通过/失败/未运行及原因；完成前检查 diff 与状态，只提交本任务文件。

## 5. 构建入口

JDK 17、Android SDK 36。Windows 用 gradlew.bat；Linux/macOS 用 bash ./gradlew，兼容旧标签缺少执行权限。

```text
gradlew.bat :app:compileDebugKotlin   # Kotlin 编译
gradlew.bat :app:testDebugUnitTest   # 单元测试，可用 --tests 筛选
gradlew.bat :app:lintDebug           # 按风险运行 Lint
gradlew.bat :app:assembleDebug       # APK，并归档到 ../APP/
```

## 6. 日常完成与交接

- 日常修改按范围验证并提交，不自动升版本、不打 tag、不创建 Release。仅要本地 APK 时可以构建，不自动发布。
- 推送按用户本次授权执行；一次相关迭代汇总一条记录，发布时才写详细版本归档。
- 新独立需求可以新任务开始，交接保留目标、文件、已完成、验证及未解决问题；同一故障无需强制换任务。
- 结束时简述行为变化、验收步骤、验证和剩余问题，不自行追加优化任务。

## 7. 发布：仅在用户明确要求时执行

用户说“发布”“发版”“发布这个版本”或 /release 才启动新版本发布。/release 是自然语言约定，不依赖内置命令。普通修改/修复/优化不触发。

1. 汇总发布范围，检查工作区和远端，排除无关未完成改动。
2. 在 app/build.gradle.kts 递增 versionCode 和 versionName 的 patch；指定大版本时遵从指定。先更新版本再构建，保证 APK 内版本正确。
3. 对最终代码运行单元测试和 APK 构建，按风险补 Lint/交互验证；失败不得宣称发布完成。
4. 更新 CHANGELOG、CHANGE_ARCHIVE，提交代码和版本，创建与 versionName 一致且未使用的 tag。
5. 推送发布提交与本次 tag，明确指定该 tag，不用 --tags 推送所有本地标签。标签触发 .github/workflows/release.yml。
6. 等 Actions 完成，核实 Release 的 PureNote-<tag>.apk 附件存在且大小非零，再给下载链接。只有 Source code 不算发布完成；当前工作流交付 debug APK，应如实说明。

### 已有版本缺附件或 CI 失败

- 先看失败步骤和错误，不删除、移动或重打已发布标签。
- 修复工作流后，在默认分支手动运行 Release APK，tag 填原标签（例如 v1.2.19）。必须检出该标签代码，不能把最新 main 冒充旧版本。
- 补附件不升版本；若必须改应用代码才能构建，应发新版本，不能改写旧标签。
- 已有附件不盲目覆盖，结束必须核实实际附件。修复后重跑一次，有新失败再按日志定位。
