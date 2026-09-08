# 任务进度

> 本文件是所有 AI（Codex / opencode / DeepSeek Harness）共享的任务状态追踪。
> **规则**：每次开始/结束任务都要更新此文件。状态用「进行中 / 完成 / 阻塞 / 等待确认」。
> 涉及文件尽量写具体路径，方便判断是否有冲突。

| 任务ID | 描述 | 工具 | 状态 | 涉及文件 | 最近更新 |
|--------|------|------|------|----------|----------|
| T-000 | 初始化多AI协作规范 | opencode | 完成 | AGENTS.md, .agents/skills/, wiki/* | 2026-09-05 |
| T-001 | 7项UI/交互改进：主页新建分类入口、侧栏把手样式与拖拽跟手、纯模糊背景、待办行内编辑、提醒选择器Material3化 | opencode | 完成 | ui/HomeScreen.kt, ui/TodoScreen.kt, notify/QuickCaptureService.kt, app/build.gradle.kts | 2026-09-05 |
| T-002 | 5项UI/交互改进：把手白色75%不透明度、虚化去纹路、待办光标定位+回车子待办、左滑删除改滑出按钮、废纸篓长按多选+设置页最近删除入口 | opencode | 完成 | notify/QuickCaptureService.kt, ui/TodoScreen.kt, ui/TrashScreen.kt, ui/SettingsScreen.kt | 2026-09-06 |
| T-003 | 左滑真划出删除按钮；待办删除进废纸篓 | opencode | 完成 | ui/TodoScreen.kt, data/Db.kt, data/NoteRepository.kt, NoteViewModel.kt, ui/TrashScreen.kt, notify/QuickCaptureService.kt | 2026-09-06 |
| T-004 | 设置最近删除置顶；废纸篓卡片紧凑去按钮+左侧勾选；待办滑动露圆形红删除键；待办底部弹窗编辑器替代行内/全屏编辑；TodoScreen拆文件 | opencode | 完成 | ui/SettingsScreen.kt, ui/TrashScreen.kt, ui/TodoSwipe.kt, ui/TodoPane.kt, ui/TodoCard.kt, ui/TodoEditSheet.kt, ui/AppRoot.kt, ui/HomeScreen.kt, NoteViewModel.kt | 2026-09-06 |
| T-005 | 删除键对齐修复；废纸篓删清空键+时间改日期时间；前台通知降级静默 | opencode | 完成 | ui/TodoSwipe.kt, ui/TrashScreen.kt, ui/Common.kt, notify/QuickCaptureService.kt | 2026-09-06 |

## 当前进行中的任务（占用检查）

- T-012 | 精简开工/发版规则，修复 Linux APK 构建并补发 v1.2.19 附件 | Codex | 进行中 | AGENTS.md, .agents/skills/pre-work-check/SKILL.md, .github/workflows/release.yml, wiki/TASK_PROGRESS.md, wiki/DECISIONS.md, wiki/CHANGELOG.md | 2026-09-08 |

> 开始新任务前先看这里：若有其他 AI 正在处理与你重叠的文件，先停下询问用户。

- T-006-A | A批：待办行末光标+侧栏原地编辑+长按多选排序；B批：单条静默通知+虚化去纹路 (v1.2.12已发) | opencode | 完成 | ui/TodoEditSheet.kt, ui/TodoCard.kt, ui/TodoPane.kt, ui/HomeScreen.kt, NoteViewModel.kt, data/Db.kt, data/NoteRepository.kt, notify/QuickCaptureService.kt, notify/ReminderReceiver.kt | 2026-09-06 |
- T-006-C | C批：笔记富编辑器（日期YMD/置顶移时间栏/H1-H3/工具栏跟键盘/录音/行内勾选/图片插光标/三点菜单） | opencode | 待开始 | ui/EditorScreen.kt, ui/NoteCard.kt, data/Models.kt, data/Db.kt | 2026-09-06 |
- T-007 | 待办弹窗跟随输入法同步升降（弹窗与键盘渐入渐出同动）(v1.2.13已发) | opencode | 完成 | ui/TodoEditSheet.kt, app/src/main/AndroidManifest.xml | 2026-09-06 |
- T-008 | 侧栏均匀暗化去噪点+把手加灰+侧栏卡片原地编辑+长按多选对标图2 (v1.2.14已发) | opencode | 完成 | notify/QuickCaptureService.kt, ui/TodoPane.kt, ui/TodoCard.kt | 2026-09-06 |
- T-009 | 自查返工：hideKeyboard空转+暗罩太薄+选中米黄太浅+手柄太淡 (v1.2.15已发) | opencode | 完成 | notify/QuickCaptureService.kt, ui/TodoCard.kt | 2026-09-06 |
- T-010 | Android16模拟器全量实测+修复8处bug（弹窗闪退/侧栏新建待办/速记持久化/输入回滚/FAB卡死/拖拽闪回/通知id/时间文案）(v1.2.16已发) | ZCode | 完成 | ui/TodoEditSheet.kt, ui/TodoPane.kt, ui/TodoSwipe.kt, ui/TodoCard.kt, notify/QuickCaptureService.kt, notify/ReminderReceiver.kt, notify/ReminderScheduler.kt, MainActivity.kt, ui/SettingsScreen.kt, app/build.gradle.kts | 2026-09-07 |
- T-011 | 多选UI对标小米实机（三条杠+浅灰）；把手深灰50%；侧栏点空白保存并收起。**待办：T-006-C 笔记富编辑器批次仍未开工**（工具栏随键盘/录音/行内勾选/图片插光标/H1-H3/时间栏YMD/三点菜单精简） | ZCode | 完成 | ui/TodoCard.kt, notify/QuickCaptureService.kt, app/build.gradle.kts | 2026-09-07 |
