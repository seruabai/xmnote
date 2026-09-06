# 改动记录

> 记录每次实质性改动，供所有 AI 接续时了解最新状态。
> **规则**：完成一次改动（或一批关联改动）后在顶部追加一行：日期 / 工具 / 改动摘要 / 涉及文件。

| 日期 | 工具 | 改动摘要 | 涉及文件 |
|------|------|----------|----------|
| 2026-09-06 | opencode | **v1.2.11**：滑动删除键改等高对齐；废纸篓删清空键时间改日期时间；前台通知去时间戳 | ui/TodoSwipe.kt, ui/TrashScreen.kt, ui/Common.kt, notify/QuickCaptureService.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.10**：待办编辑换底部弹窗（单条回车转清单+行行回车加行）；左滑换部分露出圆形红删除键；废纸篓卡片删按钮只留标题时间+左侧勾选；设置最近删除置顶；虚化半径加大去纹路；TodoScreen拆5文件 | ui/TodoPane.kt, ui/TodoCard.kt, ui/TodoEditSheet.kt, ui/TodoSwipe.kt, ui/TodoRemindDialog.kt, ui/TrashScreen.kt, ui/SettingsScreen.kt, ui/AppRoot.kt, NoteViewModel.kt, notify/QuickCaptureService.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.9**：待办左滑删直接删除换停留露出删除按钮；待办表新增废纸篓字段待办删除进废纸篓；废纸篓新增待办分区恢复/彻底删除/多选；行内回车新增子行自动聚焦 | ui/TodoScreen.kt, ui/TrashScreen.kt, data/Db.kt, data/NoteRepository.kt, data/Models.kt, NoteViewModel.kt, PureNoteApp.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.8**：侧栏把手改白色75%不透明度；虚化背景去纹路（backdrop固定透明+降噪半径）；待办行内编辑光标定位末尾+回车添加子待办行；左滑直接删除（未做成划出按钮，见v1.2.9），禁用右滑完成；废纸篓支持长按多选+全选/恢复/彻底删除批量操作；设置页新增「最近删除」入口 | notify/QuickCaptureService.kt, ui/TodoScreen.kt, ui/TrashScreen.kt, ui/SettingsScreen.kt, app/build.gradle.kts |
| 2026-09-05 | opencode | **v1.2.7**：笔记页新增「新建分类」入口；侧栏把手改白色半透明并缩矮、背景改为纯模糊；把手拖拽跟手拉出面板；待办点击文字行内直接编辑（底栏：提醒时间+完成）；提醒选择器改用 Material3 日期/时间控件并将「到期日/时刻」改「日期/时间」 | app/build.gradle.kts, ui/HomeScreen.kt, ui/TodoScreen.kt, notify/QuickCaptureService.kt |
| 2026-09-05 | opencode | **v1.2.6**：全新 UI 骨架——奶油暖黄主题 + 屏幕转场动画；versionCode 9 | app/build.gradle.kts, ui/AppRoot.kt, ui/Common.kt, ui/theme/Theme.kt |
| 2026-09-05 | opencode | 变更版本策略：每次代码改动自动升 patch 版本并打 tag（规范化） | AGENTS.md, .agents/skills/pre-work-check/SKILL.md |
| 2026-09-05 | opencode | 提交全新 UI 骨架：奶油暖黄主题 + 屏幕转场动画（4b2f06a） | app/src/main/java/com/purenote/local/ui/AppRoot.kt, Common.kt, theme/Theme.kt |
| 2026-09-05 | opencode | 初始化多AI协作规范 | AGENTS.md, .agents/skills/pre-work-check/SKILL.md, wiki/* |
