# 任务进度

> 本文件是所有 AI（Codex / opencode / DeepSeek Harness）共享的任务状态追踪。
> **规则**：每次开始/结束任务都要更新此文件。状态用「进行中 / 完成 / 阻塞 / 等待确认」。
> 涉及文件尽量写具体路径，方便判断是否有冲突。

| 任务ID | 描述 | 工具 | 状态 | 涉及文件 | 最近更新 |
|--------|------|------|------|----------|----------|
| T-000 | 初始化多AI协作规范 | opencode | 完成 | AGENTS.md, .agents/skills/, wiki/* | 2026-09-05 |
| T-001 | 7项UI/交互改进：主页新建分类入口、侧栏把手样式与拖拽跟手、纯模糊背景、待办行内编辑、提醒选择器Material3化 | opencode | 完成 | ui/HomeScreen.kt, ui/TodoScreen.kt, notify/QuickCaptureService.kt, app/build.gradle.kts | 2026-09-05 |
| T-002 | 5项UI/交互改进：把手白色75%不透明度、虚化去纹路、待办光标定位+回车子待办、左滑删除改滑出按钮、废纸篓长按多选+设置页最近删除入口 | opencode | 完成 | notify/QuickCaptureService.kt, ui/TodoScreen.kt, ui/TrashScreen.kt, ui/SettingsScreen.kt | 2026-09-06 |

## 当前进行中的任务（占用检查）

> 开始新任务前先看这里：若有其他 AI 正在处理与你重叠的文件，先停下询问用户。

（无进行中任务）
