# AGENTS.md — 给 AI 协作者的项目上下文

> **⚠️ 强制规则：读取本文件的任何 AI，在本次会话中一旦打算修改本仓库的任何文件，必须先完成下面的「第 0 节 · 开工前强制核对」，不得跳过。这是自动执行的约定，不需要用户提醒。**

---

## 0. 开工前强制核对（每次想改代码前自动执行，不等用户提示）

本仓库由**多款 AI 共同维护**（Codex / opencode / DeepSeek Harness）。为避免互相冲突、重复劳动、改动丢失，**只要你想修改仓库文件，就必须按以下步骤操作，无需用户要求，也不要等待用户说"开始"**：

### 步骤 1 — 读取共享上下文（缺一不可）
依次读取：
- 本文件 `AGENTS.md`（你正在读）
- `.agents/skills/pre-work-check/SKILL.md`（完整的核对细则）
- `wiki/TASK_PROGRESS.md`（进行中任务 + 占用状态）
- `wiki/DECISIONS.md`（已定决策，避免推翻）
- `wiki/CHANGELOG.md`（最近改动）

> `wiki/` 下文件若不存在，说明是首次运行：先照 `.agents/skills/pre-work-check/SKILL.md` 的模板创建它们。

### 步骤 2 — 占用检查
- 若 `wiki/TASK_PROGRESS.md` 中已有**其他 AI** 正在处理与你目标重叠的文件：**立即停下**，向用户说明冲突，等用户决定，不得擅自开始。
- 无冲突则把本次任务登记进 `wiki/TASK_PROGRESS.md`（状态=`进行中`，责任人=你的工具名，涉及文件，开始时间）。

### 步骤 3 — 范围确认
- 只修改分配给你的文件；不要改动无关文件。
- 超过 3 个文件，或涉及架构/接口变更：先列改动清单，等用户确认再动手。

### 步骤 4 — 前置校验
- 先 `git -C . pull`（或确认基于最新代码），避免基于过期代码。
- 确认既有基线能通过（本 Android 项目：`./gradlew assembleDebug`；涉及逻辑改 `./gradlew test`）。

### 步骤 5 — 改动记录（强制）
- 完成改动时更新 `wiki/CHANGELOG.md`（追加一行：日期+工具+摘要+文件）。
- 涉及关键决策时写入 `wiki/DECISIONS.md`。
- 完成后把 `wiki/TASK_PROGRESS.md` 中对应任务标记 `完成` 并释放占用。

### 步骤 6 — 完成验证
- 运行 `AGENTS.md` 列出的全部验证命令并**证明**有效，不要仅口头声称。
- 提交时把 `wiki/` 改动一并提交，保证状态文件随代码同步到 GitHub。

> 这些文件全部随仓库同步到 GitHub（`seruabai/xmnote`）。**一切以仓库内已提交的最新状态为准，不要依赖会话缓存记忆。**

---

## 1. 项目是什么

**纯记 PureNote** —— 一款小米笔记风格的**本地优先** Android 笔记应用。

- 仓库：`seruabai/xmnote`（GitHub 仓库名，与内部名 PureNote 不同）
- 包名 / 应用 id：`com.purenote.local`
- 当前版本：**1.2.6**（versionCode 9）
- 核心卖点：完全本地、无网络权限、数据不出设备

> ⚠️ 命名注意：GitHub 仓库名是 `xmnote`，但 `settings.gradle.kts` 中 `rootProject.name` 是 `PureNote`，应用 displayName 为“纯记”。文档与代码中这两种写法都存在，**不要用“xmnote”当产品名**。

---

## 2. 之前做过什么（开发历史/现状）

- 用 **Jetpack Compose + Material 3** 从零实现了完整笔记 App，单 Activity、单模块架构。
- 数据层：**纯 SQLite（手写 SQL，无 Room）**，`data/Db.kt`、`data/NoteRepository.kt`、`data/Models.kt`。
- 无 Hilt / Retrofit / Ktor / Room 等框架依赖，追求最小依赖。
- 已实现功能：文本/清单笔记、便签纸色、文件夹分类、置顶、多选批量操作、图片附件（拍照/相册）、未来提醒（AlarmManager + 开机补偿）、快捷速记前台服务、分享文本/图片导入、搜索、浅/深/跟随系统主题、废纸篓 30 天清理。
- 界面刻意**模仿小米便签风格**：灰底 `#F7F7F7`、白卡片、黄色强调色 `#FFB800`（浅色）/ `#FFD350`（深色）。
- 目前已有单元测试：清单编解码、预览摘要、标题拆分、待办完成/分组、字号缩放等（`app/src/test/`）。
- Debug APK 构建后会自动按版本名复制到仓库上级 `APP/` 目录。

---

## 3. 架构速览

依赖方向：`ui/` → `NoteViewModel` → `NoteRepository` → `Db`（SQLite）

```
MainActivity（单 Activity，处理 external intent）
  └─ PureNoteTheme { AppRoot(vm) }        # AppRoot 用 when(screen) 切换各 Screen
       ├─ HomeScreen   笔记/待办主页
       ├─ EditorScreen 编辑器（文本/清单/图片/纸色）
       ├─ TodoScreen   待办
       ├─ FoldersScreen 分类管理
       ├─ SettingsScreen 设置
       └─ TrashScreen  废纸篓
NoteViewModel  ←  全局状态（notes/folders/tab/filter/themeMode...）
NoteRepository ←  SQLite 读写
```

关键文件：
- `ui/AppRoot.kt` —— 顶级导航，`when (screen)` 直接切换（**当前无转场动画**）
- `ui/theme/Theme.kt` —— 小米便签配色主题
- `ui/HomeScreen.kt` —— 主页（瀑布流/列表、搜索、分类、底部双标签、多选栏）
- `ui/NoteCard.kt` —— 笔记卡片（文本/清单两种正文）
- `ui/EditorScreen.kt` —— 编辑器

---

## 4. 后续计划（ROADMAP）

按优先级排序：

- [x] **UI 配色定稿**：**奶油暖黄 + 奶油纸**。用户明确偏好：暖心、安心、有纸感安全感（“像真笔记本存放灵感”）；**不要**莫兰迪（太灰）、**不要**强冷蓝、**不要**太活泼刺眼的高饱和黄。风格 = 柔和暖黄强调色 + 温暖奶油纸背景。
- [ ] **全新 UI 重构**（高优先）：用户对当前界面不满意，认为“太丑”且“没有过渡动画”。配色已定为奶油暖黄+奶油纸，补齐动效按 M3 motion 规范（转场 150–300ms）。
- [ ] 补齐导航转场：将 `AppRoot.kt` 的 `when` 硬切换改为 `AnimatedContent` 等（页面进出需有滑动/淡入淡出）。
- [ ] 主页动效：FAB 显隐、底部导航选中态、卡片按压/进出动画、搜索栏展开收起、列表/宫格切换过渡。
- [ ] 未来可考虑：发布正式版、开源许可证、文档增强。

> ⚠️ 每次重构可能沿用或推翻现有小米便签配色和 `noteContainerColor` 纸色逻辑——**若推翻，需同步改 Theme.kt 与所有引用它的界面**，并保证深浅两套主题一致。

---

## 5. 编码约定（请 AI 遵守）

- **动手前先执行 `pre-work-check` skill 与读取 `wiki/`**（见第 0 节），并遵守其核对与记录要求。
- **不要添加无必要的注释**；如需注释，用中文，简短有信息量。
- 尊重现有代码风格与命名：类大写驼峰、函数/变量小写驼峰、常量大写。
- 遵循现有分层：UI 层不直接碰 SQLite，一律走 `NoteViewModel` / `NoteRepository`。
- Compose 采用 Material 3；新增动画依赖如非必要不引入额外库，优先用 `androidx.compose.animation` 自带 API。
- 构建：`./gradlew assembleDebug`（需 JDK 17、compileSdk 36）。改动后请确保能编译（必要时跑 `app/src/test` 单元测试）。
- 不要修改 `.gitignore` 未跟踪的本地配置文件（如 `local.properties`）。

---

## 6. 常见命令

```bash
./gradlew assembleDebug     # 构建 Debug APK（自动复制到 ../APP/）
./gradlew test              # 运行单元测试
```

---

## 7. 版本发布流程（每次代码改动都必须升一个小版本并打 tag）

**开发者要求：每一个代码改动都升级一个小版本并打 tag，防止版本混淆。** 因此当你完成**任何代码改动**（功能、重构、修复、甚至很小的调整）时，**自动执行下面的完整闭环，不需要等用户说「发版」**。

> 例外：仅修改文档/规范类文件（`AGENTS.md`、`wiki/`、`.agents/`、`README.md` 等）时**不升版本、不打 tag**，正常提交即可。只有动了 `app/` 下代码才升版本。

### 7.1 版本号规则（小版本 = patch 位递增）
- 版本号在 `app/build.gradle.kts` 的 `defaultConfig`：`versionName`（如 `1.2.5`）与 `versionCode`（整数，必须递增）。
- **每次代码改动**：`versionCode` +1，`versionName` 的 **patch 位** +1（如 `1.2.5` → `1.2.6` → `1.2.7`）。
- 仅当用户明确要求「大版本」时才升主/次位（如 `2.0.0`），否则一律走 patch 位。
- **`versionCode` 每次都必须比上一次大**（Android 硬性要求），`versionName` 对外展示。

### 7.2 每次代码改动的完整步骤（按序执行，缺一不可）
1. **确认代码完整**：改动实现完毕、`./gradlew assembleDebug` 与 `./gradlew test` 通过。
2. **更新 `app/build.gradle.kts`**：`versionCode` +1、`versionName` patch +1。
3. **更新 `AGENTS.md` 第 1 节**的「当前版本」为新的 `versionName`。
4. **更新 `wiki/CHANGELOG.md`**：顶部追加一行，标注新版本号与主要改动。
5. **`git add` + `git commit`**：把代码改动和版本号放**同一个提交**，提交信息带版本号，如 `feat: 奶油暖黄主题 (v1.2.6)`。
6. **打 tag**：`git tag v<versionName>`（与 `versionName` 完全一致），指向刚提交的版本。
7. **推送到 GitHub**：`git push origin main --tags`（代码和 tag 一起推上去）。

### 7.3 禁止事项
- **不要**只提交代码而漏掉升版本号或打 tag——每个代码改动都必须是完整的版本闭环。
- **不要**重复使用已存在的版本号/tag；每个新代码改动都必须用一个新的 `vX.Y.Z`。
- **不要** 提交编译不过的代码当"发布"。

> 当前版本基线：**1.2.6**（versionCode 9），tag `v1.2.6`。下次代码改动 → `1.2.7`（versionCode 10）。
