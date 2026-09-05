# 纯记 PureNote

> 一款小米笔记风格的**本地优先** Android 笔记应用。所有数据仅保存在你的设备上，不上传云端，无需登录。

纯记（PureNote）是一个用 Jetpack Compose 从零手写的轻量笔记 App，界面致敬小米便签的极简质感，主打快速记录、清单管理和本地隐私。

---

## ✨ 功能特性

- **两种笔记类型**
  - 📝 文本笔记：大标题 + 流式正文，自动以首行为标题
  - ☑️ 清单笔记：可勾选任务，卡片显示进度条和剩余条目
- **便签纸色**：6 种纸色，浅色 / 深色主题各一套，还可放大 / 缩小全局字号
- **文件夹分类**：将笔记归入分类，支持一键移动、批量移动
- **置顶与多选**：长按进入多选，支持全选 / 置顶 / 分类 / 删除批量操作
- **图片附件**：拍照或从相册插入图片，编辑器中可预览 / 删除
- **提醒**：为笔记或待办设置未来时间提醒，开机后自动补偿重排
- **快捷速记**：悬浮侧边把手（前台服务）快速新建本地笔记
- **分享导入**：从其它 App 分享文本 / 图片进来，自动新建笔记
- **搜索**：顶部搜索框 + 分类过滤，实时筛选
- **全局主题**：浅色 / 深色 / 跟随系统，随时切换
- **废纸篓**：删除的笔记先进废纸篓，超过 30 天自动清理
- **完全本地**：不申请网络权限，数据安全不外泄

---

## 🛠 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity，ViewModel + StateFlow / Compose State，手动分层 |
| 数据层 | SQLite（本地 SQL，Room-free），使用 Classification 归类的 Note 模型 |
| 提醒 | AlarmManager + 广播接收器 + 前台服务 |
| 构建 | Gradle (Kotlin DSL) + AGP + Compose Compiler |
| 最低版本 | Android 7.0（minSdk 24），目标 SDK 36 |

> 无 Room / Hilt / Retrofit / Ktor 等框架依赖，核心全部手写，追求最小依赖与可控性。

---

## 📦 安装

从 **GitHub Releases** 页面下载最新 APK 安装到 Android 设备即可（需在系统设置中允许安装未知来源应用的提醒）。

> 也支持构建 Debug 包（见下文）。

---

## 🔧 构建

环境要求：**JDK 17**、Android SDK（compileSdk 36）。

```bash
# 首次同步 / 构建 Debug APK
./gradlew assembleDebug
```

构建完成后，Debug APK 会按版本名自动复制到仓库上级的 `APP/` 目录（例如 `纯记+1.2.5.apk`）。

---

## 📁 项目结构

```
xmnote/
├── app/
│   ├── src/main/
│   │   ├── java/com/purenote/local/
│   │   │   ├── MainActivity.kt          # 单 Activity，处理分享/快捷速记 intent
│   │   │   ├── PureNoteApp.kt           # Application：初始化仓库、清理废纸篓、补偿提醒
│   │   │   ├── NoteViewModel.kt         # 全局 ViewModel，承载状态与业务逻辑
│   │   │   ├── core/                    # 图片存储、预览摘要、清单编解码、日期工具
│   │   │   ├── data/                    # SQLite 数据库、仓库、数据模型
│   │   │   ├── notify/                  # 提醒调度、广播接收器、快捷速记服务
│   │   │   └── ui/                      # Compose 界面（Home/Editor/Todo/Settings…）+ 主题
│   │   ├── res/                         # 资源、图标、主题、XML
│   │   └── AndroidManifest.xml          # 权限与组件声明
│   └── build.gradle.kts
├── gradle/
├── settings.gradle.kts                  # 模块、依赖仓库、rootProject=“PureNote”
├── build.gradle.kts
└── .gitignore
```

---

## 🧭 相关文档

- [AGENTS.md](./AGENTS.md) —— 面向 AI 协作者的项目上下文：开发历史、架构说明、规划与编码约定。

---

## 📄 License

本项目当前**未指定开源许可证**（保留所有权利）。
