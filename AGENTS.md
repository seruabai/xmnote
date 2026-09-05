# AGENTS.md — 给 AI 协作者的项目上下文

> 本文件由开发者为 AI（Cursor / Claude Code / Copilot / opencode 等）维护，会在接手项目时自动作为上下文加载。**请 AI 先读本文件再动手**，它会说明项目是什么、当前状态、接下来要做什么，以及必须遵守的约定。

---

## 1. 项目是什么

**纯记 PureNote** —— 一款小米笔记风格的**本地优先** Android 笔记应用。

- 仓库：`seruabai/xmnote`（GitHub 仓库名，与内部名 PureNote 不同）
- 包名 / 应用 id：`com.purenote.local`
- 当前版本：**1.2.5**（versionCode 8）
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

- [ ] **全新 UI 重构**（高优先）：用户对当前界面不满意，认为“太丑”且“没有过渡动画”。计划完全重新设计视觉风格 + 补齐动效。方向尚未最终敲定（备选：Material 3 规范 motion 风 / 暗色现代质感 / 潮流个性风），**动手前先与用户确认风格**。
- [ ] 补齐导航转场：将 `AppRoot.kt` 的 `when` 硬切换改为 `AnimatedContent` 等（页面进出需有滑动/淡入淡出）。
- [ ] 主页动效：FAB 显隐、底部导航选中态、卡片按压/进出动画、搜索栏展开收起、列表/宫格切换过渡。
- [ ] 未来可考虑：发布正式版、开源许可证、文档增强。

> ⚠️ 每次重构可能沿用或推翻现有小米便签配色和 `noteContainerColor` 纸色逻辑——**若推翻，需同步改 Theme.kt 与所有引用它的界面**，并保证深浅两套主题一致。

---

## 5. 编码约定（请 AI 遵守）

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
