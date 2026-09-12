# 改动记录

> 记录每次实质性改动，供所有 AI 接续时了解最新状态。
> **规则**：完成一次改动（或一批关联改动）后在顶部追加一行：日期 / 工具 / 改动摘要 / 涉及文件。

| 日期 | 工具 | 改动摘要 | 涉及文件 |
|------|------|----------|----------|
| 2026-09-13 | ZCode | [结构整理]日期格式化收敛到 core/DateFormats（三处重复→一处，SimpleDateFormat 改按线程各持一份修正线程安全隐患）；修 build.gradle.kts 被编码损坏的注释 | core/DateFormats.kt, core/TodoDates.kt, ui/Common.kt, ui/EditorScreen.kt, notify/QuickCaptureService.kt, app/build.gradle.kts, test/.../DateFormatsTest.kt |
| 2026-09-13 | ZCode | [性能]笔记列表瀑布流分列改 remember 缓存（原来每次重组都按 body.length 重算全部分列）；卡片分类名查找改预建 map；搜索加 250ms 防抖；DB v7 加 (trashed,updated_at)/(trashed,folder_id)/(trashed,parent_id) 复合索引。实测：2 万条笔记下列表查询<1ms、启动耗时与空库相当，DB 非瓶颈 | ui/HomeScreen.kt, NoteViewModel.kt, data/Db.kt |
| 2026-09-08 | Codex | [协作]改按需读取和显式发版；[CI]修复 gradlew 执行权限错误，增加原标签补附件及产物验证（应用版本不变） | AGENTS.md, .agents/skills/pre-work-check/SKILL.md, .github/workflows/release.yml, wiki/* |
| 2026-09-07 | ZCode | **v1.2.19**：[待办弹窗]删M3 ModalBottomSheet独立滑入（弹窗先出被键盘盖住再跳），换自绘Dialog弹层imePadding实时贴键盘顶沿；[多选]全选图标换FactCheck对齐实机 | ui/TodoEditSheet.kt, ui/TodoPane.kt, app/build.gradle.kts |
| 2026-09-07 | ZCode | **v1.2.18**：[同步地基]新增uuid全局ID：DB v5（notes/todos加列+存量回填+索引），Model暴露uuid；新增wiki/SYNC_DESIGN.md同步方案设计 | data/Db.kt, data/Models.kt, data/NoteRepository.kt, wiki/SYNC_DESIGN.md, app/build.gradle.kts |
| 2026-09-07 | ZCode | **v1.2.17**：[多选]删双横线+米黄，换三条杠+浅灰选中底（对标小米实机）；[侧栏]把手删浅灰75%，换深灰50%；[侧栏]删点空白仅退回列表，换保存草稿并收起整个侧栏 | ui/TodoCard.kt, notify/QuickCaptureService.kt, app/build.gradle.kts |
| 2026-09-07 | ZCode | **v1.2.16**：[测试修复]Android16实测修8处bug（弹窗闪退/侧栏新建待办无编辑卡/速记开关不持久/输入回滚/多选FAB卡死/拖拽闪回/通知id错/相对时间不刷新） | ui/TodoEditSheet.kt, ui/TodoPane.kt, ui/TodoSwipe.kt, ui/TodoCard.kt, notify/QuickCaptureService.kt, notify/ReminderReceiver.kt, notify/ReminderScheduler.kt, MainActivity.kt, ui/SettingsScreen.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.15**：[自查返工]修复收面板键盘空转；暗罩加深到50%+无虚化补偿70%；选中米黄加深；手柄加深 | notify/QuickCaptureService.kt, ui/TodoCard.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.14**：[侧栏]删逐帧毛玻璃虚化换全屏均匀暗罩；把手换浅灰；待办编辑换卡片原地展开+空白退回列表；[待办多选]换图2样式双横线+米黄选中 | notify/QuickCaptureService.kt, ui/TodoCard.kt, ui/TodoPane.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.13**：[待办弹窗]修复弹窗键盘三段跳动改同动升降 | ui/TodoEditSheet.kt, app/src/main/AndroidManifest.xml, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.12**：待办弹窗修复点行光标落行末；侧栏编辑修复新页面改行末聚焦+完成退出；待办新增长按多选+拖柄排序；通知压单条静默+提醒id错开42；虚化半径加大去纹路 | ui/TodoEditSheet.kt, ui/TodoCard.kt, ui/TodoPane.kt, ui/HomeScreen.kt, NoteViewModel.kt, data/Db.kt, data/NoteRepository.kt, notify/QuickCaptureService.kt, notify/ReminderReceiver.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.11**：滑动删除键改等高对齐；废纸篓删清空键时间改日期时间；前台通知去时间戳 | ui/TodoSwipe.kt, ui/TrashScreen.kt, ui/Common.kt, notify/QuickCaptureService.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.10**：待办编辑换底部弹窗（单条回车转清单+行行回车加行）；左滑换部分露出圆形红删除键；废纸篓卡片删按钮只留标题时间+左侧勾选；设置最近删除置顶；虚化半径加大去纹路；TodoScreen拆5文件 | ui/TodoPane.kt, ui/TodoCard.kt, ui/TodoEditSheet.kt, ui/TodoSwipe.kt, ui/TodoRemindDialog.kt, ui/TrashScreen.kt, ui/SettingsScreen.kt, ui/AppRoot.kt, NoteViewModel.kt, notify/QuickCaptureService.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.9**：待办左滑删直接删除换停留露出删除按钮；待办表新增废纸篓字段待办删除进废纸篓；废纸篓新增待办分区恢复/彻底删除/多选；行内回车新增子行自动聚焦 | ui/TodoScreen.kt, ui/TrashScreen.kt, data/Db.kt, data/NoteRepository.kt, data/Models.kt, NoteViewModel.kt, PureNoteApp.kt, app/build.gradle.kts |
| 2026-09-06 | opencode | **v1.2.8**：侧栏把手改白色75%不透明度；虚化背景去纹路（backdrop固定透明+降噪半径）；待办行内编辑光标定位末尾+回车添加子待办行；左滑直接删除（未做成划出按钮，见v1.2.9），禁用右滑完成；废纸篓支持长按多选+全选/恢复/彻底删除批量操作；设置页新增「最近删除」入口 | notify/QuickCaptureService.kt, ui/TodoScreen.kt, ui/TrashScreen.kt, ui/SettingsScreen.kt, app/build.gradle.kts |
| 2026-09-05 | opencode | **v1.2.7**：笔记页新增「新建分类」入口；侧栏把手改白色半透明并缩矮、背景改为纯模糊；把手拖拽跟手拉出面板；待办点击文字行内直接编辑（底栏：提醒时间+完成）；提醒选择器改用 Material3 日期/时间控件并将「到期日/时刻」改「日期/时间」 | app/build.gradle.kts, ui/HomeScreen.kt, ui/TodoScreen.kt, notify/QuickCaptureService.kt |
| 2026-09-05 | opencode | **v1.2.6**：全新 UI 骨架——奶油暖黄主题 + 屏幕转场动画；versionCode 9 | app/build.gradle.kts, ui/AppRoot.kt, ui/Common.kt, ui/theme/Theme.kt |
| 2026-09-05 | opencode | 变更版本策略：每次代码改动自动升 patch 版本并打 tag（规范化） | AGENTS.md, .agents/skills/pre-work-check/SKILL.md |
| 2026-09-05 | opencode | 提交全新 UI 骨架：奶油暖黄主题 + 屏幕转场动画（4b2f06a） | app/src/main/java/com/purenote/local/ui/AppRoot.kt, Common.kt, theme/Theme.kt |
| 2026-09-05 | opencode | 初始化多AI协作规范 | AGENTS.md, .agents/skills/pre-work-check/SKILL.md, wiki/* |
