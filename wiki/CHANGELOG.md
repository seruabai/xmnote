# 改动记录

> 记录每次实质性改动，供所有 AI 接续时了解最新状态。
> **规则**：完成一次改动（或一批关联改动）后在顶部追加一行：日期 / 工具 / 改动摘要 / 涉及文件。

| 日期 | 工具 | 改动摘要 | 涉及文件 |
|------|------|----------|----------|
| 2026-09-05 | opencode | **v1.2.7**：笔记页新增「新建分类」入口；侧栏把手改白色半透明并缩矮、背景改为纯模糊；把手拖拽跟手拉出面板；待办点击文字行内直接编辑（底栏：提醒时间+完成）；提醒选择器改用 Material3 日期/时间控件并将「到期日/时刻」改「日期/时间」 | app/build.gradle.kts, ui/HomeScreen.kt, ui/TodoScreen.kt, notify/QuickCaptureService.kt |
| 2026-09-05 | opencode | **v1.2.6**：全新 UI 骨架——奶油暖黄主题 + 屏幕转场动画；versionCode 9 | app/build.gradle.kts, ui/AppRoot.kt, ui/Common.kt, ui/theme/Theme.kt |
| 2026-09-05 | opencode | 变更版本策略：每次代码改动自动升 patch 版本并打 tag（规范化） | AGENTS.md, .agents/skills/pre-work-check/SKILL.md |
| 2026-09-05 | opencode | 提交全新 UI 骨架：奶油暖黄主题 + 屏幕转场动画（4b2f06a） | app/src/main/java/com/purenote/local/ui/AppRoot.kt, Common.kt, theme/Theme.kt |
| 2026-09-05 | opencode | 初始化多AI协作规范 | AGENTS.md, .agents/skills/pre-work-check/SKILL.md, wiki/* |
