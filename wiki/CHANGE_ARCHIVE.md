# 版本存档

> 本文件按版本存档每次代码改动的详细信息，供开发者和所有 AI 回顾"改了什么、从什么换成什么"。
> **规则**：每次代码改动发布新版本时，在**顶部**插入一个新版本块。改动描述用精简格式：`[组件]删[旧]，换[新]` / `[组件]新增[方案]` / `[组件]修复[问题]`。
> 与 `CHANGELOG.md`（简表）不同，这里是**每版一个详细存档块**。

---

## v1.2.19（versionCode 22）

- 日期：2026-09-07
- 摘要：待办编辑弹层根治「弹窗先出被键盘盖住再跳」

### 改动明细
- [待办弹窗]删 M3 ModalBottomSheet（自带独立滑入动画与键盘动画抢跑，慢输入法上弹窗先落定、被键盘盖住、insets 到达后再跳），换自绘 Dialog 底部弹层：无滑入动画，位置经 imePadding 实时等于键盘顶沿，升降完全跟随系统键盘插值；聚焦改 30 帧重试等窗口 attach；点罩/返回均走保存规则。
- [多选]顶栏全选图标 DoneAll 换 FactCheck，对齐小米实机样式。

### 涉及文件
- `ui/TodoEditSheet.kt`
- `ui/TodoPane.kt`
- `app/build.gradle.kts`

---

## v1.2.18（versionCode 21）

- 日期：2026-09-07
- 摘要：云同步地基——全局 uuid；新增同步方案设计文档

### 改动明细
- [数据库]DB v4→v5：notes/todos 加 `uuid TEXT NOT NULL DEFAULT ''` 列，存量数据 `hex(randomblob(16))` 一次性回填，加 uuid 索引；新建行走 `Db.newUuid()` 生成（32 位十六进制）。
- [模型]Note/Todo 暴露 `uuid` 字段（带默认值，不影响既有构造点）。
- [文档]新增 `wiki/SYNC_DESIGN.md`：协议调研结论、推荐路线（REST+游标+LWW+可选E2EE）、分阶段计划 P0-P5、Db 升版约定。

### 涉及文件
- `app/build.gradle.kts`
- `data/Db.kt`
- `data/Models.kt`
- `data/NoteRepository.kt`
- `wiki/SYNC_DESIGN.md`

---

## v1.2.17（versionCode 20）

- 日期：2026-09-07
- 摘要：按小米笔记实机截图修正多选 UI 与侧栏交互

### 改动明细
- [待办多选]删双横线手柄+米黄选中底，换三条杠浅灰手柄+浅灰选中底；右侧灰圈/黄底白勾保持（对标用户提供的小米笔记实机截图，推翻 v1.2.14/15 的米黄方案）。
- [侧栏把手]删浅灰 75% 不透明度，换深灰 50%（用户指定：再深一点+半透明）。
- [侧栏空白]删"点空白仅退出编辑退回列表"，换保存草稿后直接收起整个侧栏（用户标注 bug：编辑完成点空白应退出侧边栏）。

### 涉及文件
- `app/build.gradle.kts`
- `notify/QuickCaptureService.kt`
- `ui/TodoCard.kt`

---

## v1.2.16（versionCode 19）

- 日期：2026-09-07
- 摘要：Android 16 真机环境实测，修复 8 处使用途中 bug

### 改动明细
- [待办弹窗]修点既有清单卡片时弹窗"开了立刻闪退"（连续开合还会把未加载状态存库、触发文本回滚丢字）：未加载完成时 dismiss 直接关闭不落库；行文本回写改按 key 定位最新行 + lastEmitted 防回滚，消除旧快照覆盖。
- [速记侧栏]修"新建待办"点 + 后无编辑卡（v1.2.14 原地编辑重构时丢失渲染分支）：新草稿单独渲染在待办区顶部。
- [速记侧栏]修开关不持久化：重启手机/进程被杀后把手消失，需手动重开。换持久化偏好 + BOOT_COMPLETED 与应用前台双路自动恢复。
- [待办多选]修多选中切 Tab 后 FAB 永久消失：TodoPane 离开组合时补发退出多选回调。
- [待办拖拽]修松手后列表先闪回旧顺序再跳新序：pendingOrder 桥接异步落库窗口；拖拽项暂停 animateItem 位移动画防跳动。
- [待办左滑]修 reveal 状态变化时重建手势检测打断拖拽：pointerInput 固定 + rememberUpdatedState。
- [提醒通知]修点开通知未按正确 id 取消（强提醒模式下通知关不掉）：notifyId 统一由 Reminders 计算（4.1M/4.2M+id）。
- [时间文案]修"今天/明天"相对文案不随时间刷新（跨天后卡片仍显示旧文案）：时间行加每分钟跳动时钟源。

### 涉及文件
- `app/build.gradle.kts`
- `MainActivity.kt`
- `notify/QuickCaptureService.kt`
- `notify/ReminderReceiver.kt`
- `notify/ReminderScheduler.kt`
- `ui/SettingsScreen.kt`
- `ui/TodoCard.kt`
- `ui/TodoEditSheet.kt`
- `ui/TodoPane.kt`
- `ui/TodoSwipe.kt`

### 验证
- `./gradlew assembleDebug test` 通过；API 36 模拟器实测：弹窗开关循环稳定、侧栏新建待办可用、杀进程后把手自动恢复、多选切 Tab 后 FAB 恢复、左滑删除→废纸篓→待办分区正常、提醒通知端到端送达（精确闹钟未授权时按系统 10 分钟窗口降级）。

---

## v1.2.15（versionCode 18）

- 日期：2026-09-06
- 摘要：自查返工，修四处货不对版

### 改动明细
- 收面板删置空后收键盘（空转），换摘窗口前先收键盘，修滑走后输入法残留。
- 暗罩删 40% 黑，换虚化可用 50% 黑、无虚化补偿 70% 黑，保证两种系统状态都压住噪点/藏住形状。
- 选中卡删浅米黄（与 surfaceContainerHigh 近乎同色），换深两档暖米黄。
- 手柄删近白双横线，换可见暖灰双横线。

### 涉及文件
- `app/build.gradle.kts`
- `notify/QuickCaptureService.kt`
- `ui/TodoCard.kt`

---

## v1.2.14（versionCode 17）

- 日期：2026-09-06
- 摘要：侧栏均匀暗化去噪点；把手加灰；卡片原地编辑；多选对标图2

### 改动明细
- 背景删逐帧跨窗口毛玻璃虚化，换静态柔和虚化加全屏均匀暗罩（盖住 OEM 噪点纹路）。
- 把手删纯白色，换浅灰 75% 不透明度，白底可见。
- 侧栏编辑删新界面式底弹窗，换卡片列表内原地展开（标题加子行加提醒加完成）；空白处/完成后保存退回列表，面板不收起；只右滑/返回收起。
- 待办多选删三条杠汉堡手柄加灰选中，换双横线手柄加暖米黄选中卡。

### 涉及文件
- `app/build.gradle.kts`
- `notify/QuickCaptureService.kt`
- `ui/TodoCard.kt`
- `ui/TodoPane.kt`

---

## v1.2.13（versionCode 16）

- 日期：2026-09-06
- 摘要：待办弹窗跟随输入法同步升降

### 改动明细
- 清单删 `windowSoftInputMode` 写在 application（系统忽略），换写到 MainActivity，窗口随键盘 resize，ime 内边距逐帧送达。
- 弹窗聚焦删 90ms 延迟，换首帧即请求聚焦，键盘动画与弹窗进入动画并发，`imePadding` 贴住键盘顶部同动；收键盘时落回底部。

### 涉及文件
- `app/build.gradle.kts`
- `ui/TodoEditSheet.kt`
- `app/src/main/AndroidManifest.xml`

---

## v1.2.12（versionCode 15）

- 日期：2026-09-06
- 摘要：待办行末光标；侧栏原地编辑；长按多选排序；单条静默通知；虚化去纹路

### 改动明细
- 待办弹窗删每次聚焦首行行首，换点哪行聚焦哪行行末（TextFieldValue + 聚焦移到末尾，子行透传 focusSubId）。
- 侧栏编辑删新页面式底弹窗，换同层行末聚焦编辑（EditText 聚焦移到末尾）；底栏删自动保存换黄色完成键，点完成/空白直接保存退出侧栏；修复卡片内点击误关。
- 待办新增长按多选（已选择N项 + 全选 + 底部删除）与左侧拖柄上下排序（松手持久化 sortIndex，新增 reorderTodos）。
- 通知删多余存在感，换单条 MIN 静默（去时间戳/角标/锁屏可见）；提醒通知 id 错开 42 防顶掉前台通知；关侧栏通知彻底消失。
- 虚化删 64dp 小半径+暗化叠加，换 80dp 纯模糊（清 FLAG_DIM_BEHIND，dimAmount 归零，步进 4 减 banding）。

### 涉及文件
- `app/build.gradle.kts`
- `NoteViewModel.kt`
- `ui/TodoEditSheet.kt`
- `ui/TodoCard.kt`
- `ui/TodoPane.kt`
- `ui/HomeScreen.kt`
- `data/Db.kt`
- `data/NoteRepository.kt`
- `notify/QuickCaptureService.kt`
- `notify/ReminderReceiver.kt`

---

## v1.2.11（versionCode 14）

- 日期：2026-09-06
- 摘要：删除键对齐修复；废纸篓去清空键与时间格式；前台通知压最低存在感

### 改动明细
- 滑动删除背景层改 matchParentSize，跟卡片等高，红色删除键垂直居中对齐。
- 废纸篓删右上角清空键（清空走长按全选加批量删除）；时间改"日期加时间"格式，不用昨天/前天。
- 速记前台通知去时间戳（渠道已是 MIN 静默；系统强制要求删不掉）。

### 涉及文件
- `app/build.gradle.kts`
- `ui/TodoSwipe.kt`
- `ui/TrashScreen.kt`
- `ui/Common.kt`
- `notify/QuickCaptureService.kt`

---

## v1.2.10（versionCode 13）

- 日期：2026-09-06
- 摘要：待办底部弹窗编辑器；图五式滑动删除键；废纸篓卡片紧凑化；虚化半径加大

### 改动明细
- 待办新建/编辑统一换底部弹窗（标题 + 动态待办行 + 提醒 + 完成）；单条首行回车转清单，标题默认"待办清单"；任何一行回车下方插新行并聚焦；删行内编辑与全屏编辑页。
- 待办左滑换图五式：卡片左移一键宽露出圆形红删除键，卡片不整个划走；点键删除，点卡片回弹。
- 废纸篓卡片删单项恢复/彻底删除按钮，只留标题加时间并压紧；选择模式卡片左侧出勾选圈。
- 设置页「最近删除」移到最上方独立成行，去小标题和副标题。
- 侧栏背景虚化半径加大到 64dp，底下应用内容化开无可辨形状。
- TodoScreen.kt 拆为 TodoPane/TodoCard/TodoEditSheet/TodoSwipe/TodoRemindDialog。

### 涉及文件
- `app/build.gradle.kts`
- `ui/TodoPane.kt`
- `ui/TodoCard.kt`
- `ui/TodoEditSheet.kt`
- `ui/TodoSwipe.kt`
- `ui/TodoRemindDialog.kt`
- `ui/TrashScreen.kt`
- `ui/SettingsScreen.kt`
- `ui/AppRoot.kt`
- `ui/HomeScreen.kt`
- `NoteViewModel.kt`
- `notify/QuickCaptureService.kt`

---

## v1.2.9（versionCode 12）

- 日期：2026-09-06
- 摘要：左滑真划出删除按钮；待办删除进废纸篓

### 改动明细
- 待办左滑删直接删除，换停留露出红色删除按钮（点删除进废纸篓，点别处回弹）。
- 待办表新增 trashed/trashed_at 字段（DB v3 升 v4，含迁移），删除/清空已完成改为整树移入废纸篓。
- 废纸篓新增待办分区，支持恢复/彻底删除/长按多选批量操作；清空同时清待办；30 天自动清理覆盖待办。
- 待办行内编辑回车新增子行后自动聚焦新行；子项小×直接移除不进废纸篓。
- 主列表/速记侧栏/开机提醒重排统一排除已删待办；恢复待办重排未来提醒。

### 涉及文件
- `app/build.gradle.kts`
- `ui/TodoScreen.kt`
- `ui/TrashScreen.kt`
- `data/Db.kt`
- `data/NoteRepository.kt`
- `data/Models.kt`
- `NoteViewModel.kt`
- `PureNoteApp.kt`

---

## v1.2.8（versionCode 11）

- 日期：2026-09-06
- 摘要：把手更白、虚化去纹路、待办行内编辑补齐、废纸篓多选

### 改动明细
- 侧栏把手由白色 50% 不透明度改为 75%。
- 虚化背景去掉残留纹路：滑动过程中 backdrop 固定全透明，模糊半径降到 32dp。
- 待办行内编辑：标题改用 TextFieldValue，光标定位到末尾；回车保存标题并新增一个子待办行。
- 左滑删直接删除（当时未做成划出按钮，见 v1.2.9），右滑完成禁用，完成/撤销走复选框。
- 设置页新增「最近删除」入口，进废纸篓。
- 废纸篓支持长按多选，顶栏全选/取消，底栏批量恢复/彻底删除。

### 涉及文件
- `app/build.gradle.kts`
- `notify/QuickCaptureService.kt`
- `ui/TodoScreen.kt`
- `ui/TrashScreen.kt`
- `ui/SettingsScreen.kt`

---

## v1.2.7（versionCode 10）

- 日期：2026-09-05
- 摘要：分类与侧栏与待办交互增强

### 改动明细
- 笔记页新增「新建分类」入口。
- 侧栏把手改白色半透明并缩矮。
- 侧栏背景删半透明毛玻璃，换纯模糊背景。
- 把手拖拽跟手拉出面板。
- 待办点击文字行内直接编辑（底栏：提醒时间 + 完成）。
- 提醒选择器改用 Material3 日期/时间控件；「到期日/时刻」改名「日期/时间」。

### 涉及文件
- `app/build.gradle.kts`
- `ui/HomeScreen.kt`
- `ui/TodoScreen.kt`
- `notify/QuickCaptureService.kt`

---

## v1.2.6（versionCode 9）

- 日期：2026-09-05
- 摘要：全新 UI 骨架——奶油暖黄主题 + 屏幕转场动画

### 改动明细
- 主题删原小米便签灰底黄强调，换奶油暖黄 + 奶油纸配色（浅/深两套）。
- 便签纸色盘同步换为暖色系（奶油白/乳黄/杏黄/蜜枣/奶茶粉/暖陶土）。
- `AppRoot` 删 `when` 硬切换，换 `AnimatedContent` 前进/后退滑动转场（300ms，M3 motion）。

### 涉及文件
- `app/build.gradle.kts`
- `ui/AppRoot.kt`
- `ui/Common.kt`
- `ui/theme/Theme.kt`

---

## v1.2.5（versionCode 8）

- 日期：2026-09-05（此版本由人工维护，仅为起点归档）
- 摘要：发布基线，此前历史见 git log 与 `v1.2.5` tag。

### 涉及文件
- 见 git 历史。