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

- T-012 | 精简开工/发版规则及 CI 修复已完成；actionlint 与 diff 检查通过，已推送 main。用户优先节省 token，APK 补发由 Actions 34243422160 运行，暂不持续轮询；代码文件占用已释放。 | Codex | 完成 | AGENTS.md, .agents/skills/pre-work-check/SKILL.md, .github/workflows/release.yml, wiki/TASK_PROGRESS.md, wiki/DECISIONS.md, wiki/CHANGELOG.md | 2026-09-08 |

> 开始新任务前先看这里：若有其他 AI 正在处理与你重叠的文件，先停下询问用户。

- T-013 | 备份核心（导出/导入含附件）+ 云同步设计 v2 + GLM 任务书。**代码占用已释放**：`backup/` 6 个文件完成且验证（单测 27 + 真机端到端 2）。**下一步交 GLM 做 UI 接线 + WebDAV**，见 `wiki/GLM_TASK_BACKUP_SYNC.md`。 | ZCode | 核心完成/待 GLM 接线 | backup/*, data/NoteRepository.kt, data/Db.kt, wiki/SYNC_DESIGN.md, wiki/GLM_TASK_BACKUP_SYNC.md | 2026-09-13 |
- T-015 | 任务A备份UI接线：设置页"备份与恢复"栏，SAF导出/导入+预览确认，ViewModel BackupState。**已完成并验收**：编译+单测55+connected 2全绿；模拟器换机往返（导出→pm clear→导入，置顶/分类/废纸篓/图片全恢复）、重复导入幂等（跳过7不重复）、两种坏zip可读报错不崩溃、integrity_check ok。**代码占用已释放**。下一步：任务B WebDAV（见 GLM_TASK_BACKUP_SYNC.md） | ZCode | 完成 | NoteViewModel.kt, ui/SettingsScreen.kt, core/DateFormats.kt, wiki/TASK_PROGRESS.md, wiki/CHANGELOG.md | 2026-09-13 |
- T-016 | 三处UI修复：①侧边栏待办新增先只显示内容行、回车才出标题行 ②待办编辑中点空白改保存退回列表（不再收起整个面板到桌面）③笔记正文 BasicTextField 补 textStyle=bodyTextStyle（输入文字与"开始书写或"占位大小/位置对齐）。**已完成并验收**：编译+单测55全绿；模拟器 dump/sqlite 参数化验证——新增仅1个EditText（无标题行）、首按Enter出标题行+插入新行+焦点转移、sqlite确认保存（默认标题待办清单+子项）、编辑态点空白面板保持拉出且空草稿不落库、非编辑态点空白仍收起到把手 | ZCode | 完成 | notify/QuickCaptureService.kt, ui/EditorScreen.kt, wiki/TASK_PROGRESS.md, wiki/CHANGELOG.md | 2026-09-13 |
- T-014 | 结构整理与性能：日期格式化收敛、列表瀑布流缓存、搜索防抖、DB v7 复合索引、refresh 竞态修复、换机迁移含附件 | ZCode | 完成 | core/DateFormats.kt, ui/HomeScreen.kt, ui/Common.kt, NoteViewModel.kt, data/Db.kt, res/xml/data_rules.xml | 2026-09-13 |
- T-006-A | A批：待办行末光标+侧栏原地编辑+长按多选排序；B批：单条静默通知+虚化去纹路 (v1.2.12已发) | opencode | 完成 | ui/TodoEditSheet.kt, ui/TodoCard.kt, ui/TodoPane.kt, ui/HomeScreen.kt, NoteViewModel.kt, data/Db.kt, data/NoteRepository.kt, notify/QuickCaptureService.kt, notify/ReminderReceiver.kt | 2026-09-06 |
- T-006-C | C批：笔记富编辑器（日期YMD/置顶移时间栏/H1-H3/工具栏跟键盘/录音/行内勾选/图片插光标/三点菜单） | opencode | 待开始 | ui/EditorScreen.kt, ui/NoteCard.kt, data/Models.kt, data/Db.kt | 2026-09-06 |
- T-007 | 待办弹窗跟随输入法同步升降（弹窗与键盘渐入渐出同动）(v1.2.13已发) | opencode | 完成 | ui/TodoEditSheet.kt, app/src/main/AndroidManifest.xml | 2026-09-06 |
- T-008 | 侧栏均匀暗化去噪点+把手加灰+侧栏卡片原地编辑+长按多选对标图2 (v1.2.14已发) | opencode | 完成 | notify/QuickCaptureService.kt, ui/TodoPane.kt, ui/TodoCard.kt | 2026-09-06 |
- T-009 | 自查返工：hideKeyboard空转+暗罩太薄+选中米黄太浅+手柄太淡 (v1.2.15已发) | opencode | 完成 | notify/QuickCaptureService.kt, ui/TodoCard.kt | 2026-09-06 |
- T-010 | Android16模拟器全量实测+修复8处bug（弹窗闪退/侧栏新建待办/速记持久化/输入回滚/FAB卡死/拖拽闪回/通知id/时间文案）(v1.2.16已发) | ZCode | 完成 | ui/TodoEditSheet.kt, ui/TodoPane.kt, ui/TodoSwipe.kt, ui/TodoCard.kt, notify/QuickCaptureService.kt, notify/ReminderReceiver.kt, notify/ReminderScheduler.kt, MainActivity.kt, ui/SettingsScreen.kt, app/build.gradle.kts | 2026-09-07 |
- T-017 | 规范实施 A0–G：《PureNote 技术选型、实现规范与验收方案》分阶段落地（分支 `spec/rebuild-a-h`，12 个提交）。**已完成并验收**：单测 164、设备 48、崩溃注入 6 次强杀全绿；设备实测覆盖首页崩溃/升级锁库/图片被毁三处原始故障的修复、v9 迁移、升级接管既有库、自动备份落地。**遗留**：H（云能力）需用户决定是否推翻"不申请网络权限"的产品承诺；性能预算需真机复测（模拟器噪声 ±70%）。详见 wiki/SPEC_REBUILD_REPORT.md | DeepSeek Harness | 完成 | 见 CHANGELOG 同条目 | 2026-09-15 |
- T-018 | **规范阶段 H（云能力）**：INTERNET 权限 + `sync/` 包（RemoteTransport/WebDavTransport/Keystore 凭据/BackupSyncEngine）+ 设置页「云同步」栏 + 设备端到端（真实 wsgidav）。**代码占用已释放**。首版只上传完整备份包：不双向同步、不回读覆盖本地、不删远端旧包。未做：限流退避、S3 第二驱动、E2EE（见 SPEC_REBUILD_MAPPING 附录 B.3） | DeepSeek Harness | 完成 | sync/*, ui/CloudSyncSection.kt, ui/SettingsScreen.kt, NoteViewModel.kt, data/NoteRepository.kt, backup/BackupIo.kt, AndroidManifest.xml, res/xml/network_security_config.xml, wiki/SPEC_REBUILD_MAPPING.md | 2026-09-16 |
- T-011 | 多选UI对标小米实机（三条杠+浅灰）；把手深灰50%；侧栏点空白保存并收起。**待办：T-006-C 笔记富编辑器批次仍未开工**（工具栏随键盘/录音/行内勾选/图片插光标/H1-H3/时间栏YMD/三点菜单精简） | ZCode | 完成 | ui/TodoCard.kt, notify/QuickCaptureService.kt, app/build.gradle.kts | 2026-09-07 |
