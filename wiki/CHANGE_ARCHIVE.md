# 版本存档

> 本文件按版本存档每次代码改动的详细信息，供开发者和所有 AI 回顾"改了什么、从什么换成什么"。
> **规则**：每次代码改动发布新版本时，在**顶部**插入一个新版本块。改动描述用精简格式：`[组件]删[旧]，换[新]` / `[组件]新增[方案]` / `[组件]修复[问题]`。
> 与 `CHANGELOG.md`（简表）不同，这里是**每版一个详细存档块**。

---

## v1.2.23（versionCode 26）

- 日期：2026-09-17
- 摘要：侧栏「点完成时界面抽动」修复 + **主题整体换成小米笔记的米黄纸**（浅/深色 + 启动窗口 + 侧栏悬浮层）+ 验收脚本修正
- 说明：本版覆盖 `spec/rebuild-a-h` 上自 v1.2.22 起的全部改动：三批用户反馈（笔记 4 条 / 待办 2 条 / 侧栏 4 条）+ 本次的抽动修复与换肤。

### 一、侧栏「点完成」抽动
- [侧栏]修复[点「完成」时界面抽动一下]：根因是**两次位移叠加** —— 收起键盘让窗口重排、把焦点行自动滚进可视区；列表又在保存完成后异步重建，而滚动恢复写在 `scroll.post{}` 里，于是先画出一帧"滚动在 0"的内容、下一帧才跳回原偏移。
- [侧栏]新增[`pinnedScrollY`]：点「完成」那一刻钉住滚动偏移；换取装时用 `OnGlobalLayoutListener` 在**第一次布局里**（早于绘制）恢复，并取消 350ms 防抖中的那次保存（重绘不再往后推 ~700ms）。
- [验收]新增[逐帧位移分析]：screenrecord 60fps + 逐帧像素位移，修复前 2 次位移（+31px / −70px）→ 修复后 **0 次**；`sidebar_verify.py` 增加 S4（点完成后不抽动、面板不连带收起）。

### 二、主题：换成小米笔记的米黄纸
- [主题]删[苹果 HIG 中性灰白（#F2F2F7 底 + 纯黑深色）]，换[小米笔记米黄纸]：色值全部取自 MIUI 笔记 2.3.2.7 反编译 `res/values/colors.xml` —— `paper_yellow #FFFAF0` 全屏底色、`paper_white #FFFFFF` 卡片、`miuix_color_yellow_light_primary_default #FFB21D` 强调色（FAB / 选中态 / 侧栏「完成」键）、`#FFF0D3` 与 `#FFF5EA` 浅黄容器、`#9D6802` 暖棕次要文字、`#D94F38` 错误色。
- [主题]深色同步换成暖黑 `#17140E` + 同色系黄（`#FFBF0F`），不再是纯黑 + 系统蓝。
- [启动]XML 主题（含 `values-v31` 的 `windowSplashScreenBackground`）从 `#FFF2F2F7` / `#000000` 换成 `#FFFAF0` / `#17140E`，冷启动与闪屏不再闪灰。
- [侧栏]悬浮层（笔记卡/待办卡/编辑卡、提醒胶囊、占位与已完成文字）统一到同一套色值，不再各自硬编码 iOS 白灰。
- 未动：笔记自身的"纸色盘"（白纸/雾蓝/薄荷…）是 per-note 功能，仍按笔记存储的色号渲染。
- [验收]新增 `tools/verify/theme_verify.py`：抓屏取像素核色 —— 首页/待办/设置底色 (255,250,240)、卡片 (255,255,255)、深色 (23,20,14)。

### 三、随版本带上的三批反馈（v1.2.22 之后）
- [编辑器]保存状态从正文流挪到右下角悬浮（此前出现/消失会把整篇顶一下）；清单回车改为**新开一条勾选栏**；清单笔记补焦点上报（五键工具栏在 API<35 上可用）；首页宫格换 `LazyVerticalGrid` 单页滚动。
- [待办]左滑露删除键时勾选栏直接收起；多选态的退出键挪到「待办」大字上方、右上角齿轮换成全选（退出恢复）。
- [全局]勾选栏与文字中线对齐：按尺寸比例做光学下移（7%），像素测量验收 框高偏差 +1px / +0.5px。
- [侧栏]回车不再收起输入法（多行 + `IME_FLAG_NO_ENTER_ACTION`，留有 `ENTER_KEEPS_KEYBOARD` 回滚开关）；点开单条待办 = 标题占位「待办清单」+ 原文进内容行 + 光标落第三行；新增「隐藏保活通知」开关。

### 四、验收与已知问题
- 单测 **298 全绿**、`lintDebug` 通过、`assembleDebug` 通过。
- 设备端（Android 13 模拟器）：`sidebar_verify.py` **15/16**、`theme_verify.py` **5/5**；其余套件回归 0 失败。
- **已知问题**：Android 13（AOSP）会在服务仍处于前台态时把前台服务通知重新挂回，所以「隐藏保活通知」在 AOSP 13 上不生效（`nm.cancel` 调用成功但 `NotificationRecord id=42` 仍在）；需要允许撤回前台通知的机型（部分 MIUI/HyperOS）才会生效，待真机确认。

---

## v1.2.22（versionCode 25）

- 日期：2026-09-17
- 摘要：正文编辑器整体换成**块模型**（body_format_version = 3，编辑器 −660 行、标记桥删除）+ **思维笔记（脑图）**首批可用 + 三处真 bug 修复 + 取消文本块拖拽
- 说明：本版一次性交付 `spec/rebuild-a-h` 分支上自 v1.2.21 起的全部改动；编辑器内核与脑图都是结构性变更，故分块详述。

### 一、正文编辑器：标记文本 → 块模型
- [数据库]v9→**v10**：正文逐行升成 v3 块文档（单行失败即跳过、保持旧版本号，读取端按行内版本号 + 格式嗅探兜底）；写入路径**显式**写 `body_format_version`（此前依赖 DDL 默认值，是漏标版本的根源）。
- [数据层]新增唯一格式闸口 `core/NoteBody`：按版本解码 + 双向格式嗅探 + v1 私有标记先过 `migrateBodyV1toV2`；`NoteRepository` 与 `BackupCodec` 统一走它。
- [格式]新增 `core/RichDoc`（扁平块 + 行内片段）与 `core/RichDocCodec`：换行拆块、段首并块、块级排序、行内样式区间开关全部是纯函数（36 项单测）。
- [编辑器]删[单文本框 + 标记覆盖层]，换[块列表渲染]：H1-3 / 待办勾选框 / 有序无序列表 / 引用 / 首尾缩进 / 图片块（降采样加载）/ 录音块 / 链接块。
- [编辑器]删[工具栏改写标记文本一行]，换[直接改光标所在块]：H1-3、•、1.、❝、首缩、尾缩、勾选逐项落到块属性（ITEM 用 `number` 区分 • 与 1.）。
- [编辑器]删[`TextNoteBody` / `MarkupOverlay` / `transformNoteText` / 两个 intercept / `NoteMarkupVisualTransformation` / `insertEmbedMarkup` / 全局偏移三件套]（编辑器 −660 行）：正文以块文档贯穿 编辑器→ViewModel→SaveCommand→Repository。
- [编辑器]新增[图片/录音按块插入]：`core/BlockEdit` 负责插入位置（空行就地占位）与落点（其后必有可输入块），拍照/相册/录音/手写四处入口统一。
- [编辑器]新增[块拖拽排序]（长按抬起 + 插入指示线 + 边缘自动滚动），随后**按用户要求取消文本块拖拽**：长按留给选字，只保留图片/录音/链接块可拖。
- [编辑器]字数改为只数用户写的字（行首标记不再计入）。

### 二、思维笔记（脑图）首批
- [数据]新增 `NoteKind.MIND`（落库 kind=2）与 `Note.mind`；`MindCodec` 容错编解码，坏数据退化成单根节点而不是崩。
- [脑图]新增 `ui/MindEditor`：导图视图（整齐树布局 + 三次贝塞尔连线，节点是真实 Composable，读屏与设备验收都看得见）+ 大纲视图 + 节点操作（加子级/加同级/改文字/折叠展开/删除）+ 画布长按拖动换父（含环检测）。
- [首页]新增「新建脑图」入口；脑图没有独立标题，根节点文字即标题；卡片显示大纲投影。
- [备份]修[脑图正文被送进块文档闸口]：按 Markdown 解一遍再编码会把整棵树压成一行，现按 kind 原样写回。

### 三、真 bug 修复（皆有设备证据）
- [系统适配]修[Android 13 上样式工具栏不显示]：`adjustResize` 下窗口被压缩、`WindowInsets.isImeVisible` 恒为 false，样式键完全点不到；改为并上「正文/标题获得焦点」信号。
- [无障碍]修[自绘勾选框没有勾选框语义]：`MiCheckbox`（清单编辑器与待办卡片共用）从 `clickable` 改 `toggleable(role = Role.Checkbox)`，读屏与自动化都能识别。
- [数据]修[脑图保存一次就被降级成文本笔记]：`saveExisting` 仍按 0/1 写 kind。
- [浮层]取消文本块拖拽后，长按正文不再带出系统 `Select all / Autofill` 浮层。

### 四、验收
- 单测 **298 全绿**；`lintDebug` 通过；`assembleDebug` 通过（APK 内版本 1.2.22）。
- 设备端（adb 直连，断言只来自 `run-as sqlite3` / `uiautomator dump` / `dumpsys` / `logcat`，**不用截图**）：Android 13 与 Android 16 各跑 5 个套件，**合计 83 项检查 0 失败**——工具栏 10、块编辑 6、拖拽口径 6、脑图 19、规模矩阵 42（块渲染矩阵/文本不可拖/样式整篇 diff/清单勾选/脑图几何/崩溃与 integrity_check）。
- 一键入口 `tools/verify/run_all.py <serial>`：跑完 5 个套件并按机型输出报告（换机型重复验收用）。
- 真机（小米 12S / Android 15 / HyperOS）验收待用户进行；本版 APK 与设备端验证过的构建同源，仅版本号不同。

---

## v1.2.21（versionCode 24）

- 日期：2026-09-16
- 摘要：云能力首版（WebDAV 完整备份包）+ 规范 A0–G 数据可靠性重构一并交付
- 说明：v1.2.20 只改了编辑器工具栏，未写版本存档块；本次发布的 APK 相对 v1.2.20 同时包含 A0–G 与 H 的全部改动，因此放在同一个存档块里说明。

### 规范 A0–G（数据可靠性，2026-09-15 完成，随本版首次发布）
- [数据库]修**砖化级缺陷**：超长序号（`13800138000. `）令首页卡片渲染抛异常、应用进不去；同一行数据在 `onUpgrade` 内触发事务回滚导致 `user_version` 恒为 7、每次开库都崩。
- [编辑器]修「插图吞行」：`insertImageLineAtCursor` 非空行分支未拼回尾部，光标行之后内容被永久删除（500ms 防抖会把它落库）。
- [图片]修 `indexOf(]`) 用于 `![](` 语法会把图片标记主动拆碎；移除图片改为只改引用，物理删除移交受保护入口。
- [数据库]v8→**v9 加法迁移**：`revision` / `body_format_version` + 9 张新表（历史快照、操作幂等、附件引用、提醒期望、跨库映射）。
- [数据层]统一事务入口 `DatabaseExecutor`；`replaceSubs` 三步无事务改为整体提交；保存结果 `SaveResult` 显式返回，界面不再忽略失败。
- [编辑]新增编辑状态机 + `SaveCoordinator`（每会话最多一个写入在途，旧回执不冒充新内容已保存）；附件改为不可变发布（临时写入→校验→发布）。
- [备份]整库导出加 `manifest.json` 逐项 SHA-256；导入前全量校验，坏包/截断/改字节/缺附件一律拒绝；SAF 外部副本写后读回核对。
- [存储]活动存储指针 + 按代恢复：恢复过程当前库始终可读，指针切换前不被替换；旧代次写入被隔离（`StoreChanged`）。
- [备份]WorkManager 周期自动备份 + 保留策略 + 异常大规模删改时暂停轮换并固定恢复点；只显示实际成功时间。

### H 阶段：云能力（本版新增）
- [权限]新增 `INTERNET` / `ACCESS_NETWORK_STATE`：只在用户开启云同步并点「立即同步」时联网；未开启时应用不发起任何网络请求（已用服务器日志核对：设置页停留期间零请求）。
- [云同步]新增 `sync/` 包：`RemoteTransport` 抽象（只有文件动作，无厂商字段）+ `WebDavTransport`（手写 PROPFIND/MKCOL/GET/PUT/DELETE，207 Multi-Status 自研解析，无 XML 依赖）。
- [云同步]传输单位是**完整备份包**（阶段 E 的 zip），不是逐条记录；首版**只上传**，不做双向同步、不回读覆盖本地、不自动删除远端旧包。
- [云同步]上传后**读回校验**：大小 + 把远端整包读回来跑清单校验 + 比对 `backupId`，全部通过才记录"同步成功"。
- [凭据]账号与应用密码用 **AndroidKeyStore + AES-GCM** 加密保存，不写明文、不进日志；Keystore 不可用时保存失败并如实提示，不降级为明文。
- [设置页]新增「云同步」栏：服务器地址 / 远端目录 / 账号 / 应用密码 + 测试连接 + 立即同步 + 上次成功时间；不做进度百分比（WebDAV 的 PUT 没有可靠回执进度）。
- [安全]`network_security_config.xml`：明文 HTTP 只对模拟器宿主机（10.0.2.2/10.0.2.3）与回环开放，真实云盘一律 HTTPS。
- [依赖]新增 OkHttp 4.12.0（含 MockWebServer 做协议级单测）；`BackupIo` 新增 `inspect()` 供云同步上传前后校验同一份包。

### 验证
- JVM 单测 198 全绿；设备套件 66 通过 0 失败（另 2 项 WebDAV 用例在未提供本地服务器时按 `assumeTrue` 跳过）；Lint 无 error。
- 端到端：`tools/verify/cloud_sync_webdav.ps1` 起真实 wsgidav 服务器，设备侧完成上传并在服务器目录核对到落盘包与 SHA-256。

### 涉及文件
- `app/build.gradle.kts`、`gradle/libs.versions.toml`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/network_security_config.xml`
- `app/src/main/java/com/purenote/local/sync/*`、`ui/CloudSyncSection.kt`、`ui/SettingsScreen.kt`、`NoteViewModel.kt`、`data/NoteRepository.kt`、`backup/*`、`data/*`、`feature/*`、`platform/backup/*`
- `app/src/test/java/com/purenote/local/sync/*`、`app/src/androidTest/java/com/purenote/local/sync/CloudSyncWebDavTest.kt`、`tools/verify/cloud_sync_webdav.ps1`
- `wiki/SPEC_REBUILD_MAPPING.md`、`wiki/SPEC_REBUILD_REPORT.md`、`wiki/SYNC_DESIGN.md`、`wiki/DECISIONS.md`、`wiki/TASK_PROGRESS.md`、`wiki/CHANGELOG.md`、`AGENTS.md`、`README.md`

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