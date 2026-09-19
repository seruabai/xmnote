# 版本存档

> 本文件按版本存档每次代码改动的详细信息，供开发者和所有 AI 回顾"改了什么、从什么换成什么"。
> **规则**：每次代码改动发布新版本时，在**顶部**插入一个新版本块。改动描述用精简格式：`[组件]删[旧]，换[新]` / `[组件]新增[方案]` / `[组件]修复[问题]`。
> 与 `CHANGELOG.md`（简表）不同，这里是**每版一个详细存档块**。

---

## v1.2.26（versionCode 29）

- 日期：2026-09-20
- 摘要：**仓库文档与验收工具整理 —— app/ 零改动，APK 行为与 v1.2.25 完全一致**（用户明确要求照发这一版）
- 涉及文件：`AGENTS.md`、`tools/verify/scale_verify.py`、`tools/verify/feedback_verify.py`、`app/build.gradle.kts`、`wiki/*`

### 规则（AGENTS.md 新增 §4.1）
- [设备验证]删[各套件/子代理自行其是的取证方式]，换[**一律 adb 直连模拟器**：`adb -s <serial> shell uiautomator dump` 抓界面、`input tap|swipe|keyevent` 交互、`exec-out screencap -p` 截图取证（像素判据用 PIL 直接量，不靠肉眼）、`run-as com.purenote.local sqlite3 <db> "SQL"` 查库（SQL 必须整体作为字符串传给设备端 shell，用参数列表会被再切分、静默返回空）]
- [常备模拟器]写明：`emulator-5554` = AVD `MIUI14_Study`（Android 13）、`emulator-5556` = AVD `PureNote_API_36`（Android 16）；冷启动 `emulator.exe -avd <AVD> -no-boot-anim -no-audio -gpu swiftshader_indirect -memory 4096 -cores 4`（约 25 秒进系统）；起不来时清 `*.lock` → 只杀该 AVD 的 emulator/qemu → 重启 adb server。**跑全量回归前先冷启动；绝不让两台模拟器并行跑重活**（会把应用饿到 ANR，整轮作废）

### 验收工具
- [scale_verify F1]删[直接 `PRAGMA integrity_check`]，换[**把库复制到应用私有目录再查副本**（`cp <db> files/ic_probe.db`）]：应用在跑时直接查会因占用返回空串，不是坏库
- [feedback_verify F4]删[`open_app` 后立刻 dump 判两列]，换[**轮询 4 次**（每次 1.2s）]：`LazyVerticalGrid` 逐项组合，右列"还没画出来"会被误判成"没有右列"（实测 5556 上右列卡片 x≈598 是存在的）。判据本身不放宽
- 说明：编辑器墨迹取点（`tools/verify/editor_tap.py`）、规模矩阵配对判据、`run_all` 假绿防线等加固已随 v1.2.25 发布，本版只是补齐文档与最后两处套件判据

---

## v1.2.25（versionCode 28）

- 日期：2026-09-19
- 摘要：**排版收口第二批（分类页 / 废纸篓 / 脑图 / 待办编辑弹窗）+ 三处用户点名修复 + 笔记列表多选态四条 + 标签切换微动效**
- 涉及文件：ui/FoldersScreen.kt、ui/TrashScreen.kt、ui/MindEditor.kt、ui/TodoEditSheet.kt、ui/NoteCard.kt、ui/HomeScreen.kt、ui/Motion.kt、notify/QuickCaptureService.kt、tools/verify/*

### 分类页 / 废纸篓
- [页头]删[TopAppBar + 17sp 左对齐标题]，换[设置页式：top 8 + 48dp 返回键 + bottom 24 = 80dp，返回键 offset(x=-12dp) 让字形落 16.4dp，标题 23sp SemiBold 居中]（首个卡片 y 112.8 → 128.8dp）
- [分类行]删[内边距 12/10dp、行高 67.8dp、头像 38dp、名称 x 78.5dp、分隔线 start 62dp]，换[16/12dp、72.4dp、36dp、80.4dp、64dp（绝对 80dp，与文字列线齐）]
- [分类行删除键]删[width(40dp)（48dp 最小触控外溢到 x1=387.8dp，并把重命名挤成 44.2×48dp）]，换[size(48dp)（两键各 48×48dp，收在行内边距里）]
- [废纸篓分区标题]20.2dp → 32.0dp（＝设置页分区标题列线）
- [废纸篓列表]删[contentPadding 四周 16dp、项间距 10dp、卡片圆角 14dp]，换[仅 bottom 24dp、12dp（＝首页）、16dp（＝首页 NoteCard）]
- [废纸篓底部批量条]删[horizontal 24dp + weight(0.15f) + 40dp 按钮]，换[16dp + spacedBy(12dp) + navigationBarsPadding，按钮 48dp]
- [多选页头]删[全选 40dp TextButton、22sp Bold 标题]，换[heightIn(min=44dp)（实测 57.9×48dp）、17sp SemiBold]，两态页头行高一致（进出多选 back 框都停在 4.6/57.1dp）
- [空状态]删[圆 80dp / 图标 30dp / 间距 14dp / 文案 15sp、副文案 11sp+outlineVariant]，换[92dp / 32dp / 16dp / 17sp、13sp+outline（最暗像素 #EEE5D2 → #D9CDB4）]

### 脑图（MindEditor）
- [节点]删[内边距 X 10dp 测量 / 9dp 绘制（不一致）、Y 7dp、层级间距 34dp、兄弟间距 10dp、圆角 9dp]，换[X 12dp（同一常量）、Y 8dp、32dp、12dp、MaterialTheme.shapes.small(10dp)]
- [节点字号]删[14sp 硬编码（布局却按 18sp 测量，框内约四成空档）]，换[NoteTypeScale.editorBodySp（16/18/21sp 三档）+ 行高 1.4×]
- [节点操作条]删[间距 16dp、触控＝24dp 图标自身（实测最窄 40.0×48dp）]，换[间距 8dp、五格 weight(1f)、实测 69.7×48dp]
- [大纲行]删[行高 42.3dp、缩进起始 6dp + 每级 18dp、右内边距 10dp、字号 15sp]，换[heightIn(min=48dp)、起始 0（＝内容列 16dp）+ 每级 16dp、16dp、editorBodySp + 行高 1.4×]
- [大纲折叠键]删[18dp 图标 + clickable（Compose 自动扩触控框会向左探出屏幕，行左缘变 1.1dp）]，换[显式 44dp 槽位 + 图标 18dp 居中]（用户 2026-09-19 定：改）

### 待办编辑弹窗（TodoEditSheet）
- [弹层]删[内边距 22/22/20/22、圆角 28dp]，换[四周 16dp（＝编辑器样式面板）、圆角 26dp（＝首页多选栏，用户定）]
- [字号]删[标题 16sp/22sp、单条行 16sp、清单行 15sp 硬编码]，换[titleMedium / bodyLarge / bodyMedium]；[间距]删[行内 vertical 10dp、标题↔行 6dp、行↔底栏 14dp]，换[12dp、8dp、16dp]
- [完成键]删[文字 + padding(8,10)]，换[heightIn(44dp)（实测可点 57.9×48dp）]
- [提醒胶囊]删[高约 26dp、图标 14dp、内缩 11+5dp、12sp、清除键 IconButton 26dp]，换[高 44dp、图标 16dp、内缩 16+8dp、labelLarge(13sp)、清除键 Box 44×44dp]

### 三处用户点名修复
- [侧栏拉手]删[background = rounded(0x80888888, 12f)]，换[不绘制]（浅色界面上的灰方块）。取证：改前右缘 x=1064/1070/1076 在 y=1000/1100 是 #AFADA8，改后 #E5E1D8/#FFFAF0，拉手灰像素计数 0。22dp 触摸条保留
- [待办弹窗圆角]28dp → 26dp
- [大纲折叠键]18dp → 显式 44dp 槽位（同上）

### 笔记列表多选态（用户四条）
- [进/出多选布局]删[多选态整块换掉页头（搜索框/分类胶囊/大字全没）→ 首卡上跳 457px]，换[页头两状态同构：动作行 height(32dp) 钉死、多选态只换行内图标、标题行保留「笔记」+ 右侧「已选 N 项」]（实测页头大字 y 291→291，前 3 张卡片偏差 0px）
- [勾选框]删[卡片右上角圆形对勾、未选卡无勾选框]，换[卡片右下角 48dp 勾选框（内层自绘 MiCheckbox 18dp，右/下各内缩 16dp；清空语义后合成单一无障碍节点 desc=选择笔记 + Role.Checkbox）]
- [置顶图标]从行尾挪到勾选框左侧（x1=x0=398，中心 y 差 1px）；行尾恒定留 22dp 勾选位（只在多选态留位会让左下角那格可用宽度变小，年忽隐忽现）
- [左下角那一格]删[固定显示笔记日期]，换[NoteStampCell：有提醒 → 提醒日期时间（全天 月日、定时 月日 时:分），无提醒 → 原笔记日期；用 TextMeasurer 量真实可用宽度，降级顺序 带年+图标 → 带年(撤图标) → 省年+图标 → 省年(撤图标)，永不换行/压字距]（窄卡 9月19日 14:37、宽卡 2026年9月19日 14:37）

### 微动效
- [底部标签切换]删[硬切 if (tab == …)]，换[整块内容淡入 + 上浮 10dp（Animatable + Motion.TAB=260ms）]。不用 AnimatedContent：该块内容在 ColumnScope 里用了 weight(1f)，换 scope 编译不过。取证：animator_duration_scale=10 下连拍，内容对比度 122 → 83 → 213（淡出 → 淡入 → 落定）
- [Motion.kt]新增 const val TAB = 260

### 测试侧（不改变用户可见行为）
- 编辑器三个套件（block_edit / block_toolbar / scale）改用墨迹定位取点：uiautomator 报的正文块坐标比实际绘制高约 46px，照它点等于点在空白处；新增 tools/verify/editor_tap.py（墨迹行带定位 + 收键盘 + a11y focused 校验）
- 规模矩阵 42/42（原 8 项失败全是取点/配对问题：A4 按类名找勾选框会漏、D 的竖直 70px 窗口会配到上一行、C 的列表标记槽 x 与行带被字形空隙拆成两条）
- run_all.py：收进 note_selection_verify / theme_verify；新增假绿防线（汇总行 0/…、无汇总行、退非 0 却无 FAIL 行一律计失败）；checkbox_align_verify.py 因不自带夹具跑出 0/0 假绿而撤下
- todo_feedback_verify.py：长按进多选加重试（环境噪声会让发版门误判红）

---

## v1.2.24（versionCode 27）

- 日期：2026-09-18
- 摘要：**五屏排版重排**（待办 / 笔记列表 / 编辑器 / 设置 / 侧栏，对齐 16dp 边距 + 4dp 网格 + 44dp 触控）+ 侧栏两处"闪"修复 + 编辑器行距与勾选框中线 + 动效与保存提示收口
- 说明：本版覆盖 `spec/rebuild-a-h` 上自 v1.2.23 起的全部改动。

### 一、侧栏：两处"闪"
- [侧栏]修复[后台来通知时编辑一直闪]：系统窗口（通知横幅/权限弹窗）抢焦点会把输入法收走，而我们重挂窗口后再调 `showSoftInput` 会被系统忽略（logcat: `Ignoring showSoftInput() as view=... is not served`）→ 键盘一收就回不来。改为在面板根视图的 `onWindowFocusChanged` 里、**同一个窗口拿回焦点**的那一刻把输入法接回去（只在原地编辑态、焦点仍在输入行时）。
- [侧栏]修复[回车闪一下 + 收键盘]：回车原本走整窗重挂（输入法目标被换掉）。改为**就地往现有视图树插行**（动态补标题行/内容行，保留行引用），拿不到挂载点才退回重绘。
- [验收]强提醒通知（importance=4、FLAG_INSISTENT）弹出期间 0.6s 采样 **0 次状态变化**；回车后面板窗口 id 不变、输入行 3→4、`mInputShown` 仍为 true。

### 二、编辑器：行距与勾选框
- [编辑器]行高比改 **1.33**（对齐小米笔记 noteeditor 反编译实测 1.31–1.33），并去掉块级 6dp/2dp 留白：段内换行与回车新建的行落在同一条节奏上。
- [编辑器]行盒贴字形（`includeFontPadding=false` + `LineHeightStyle(Center, Trim.Both)`），行盒中心 ≈ 墨水中心。
- [编辑器]勾选框**改用项目自绘的 MiCheckbox**：Material 的 Checkbox 自带 48dp 最小触控尺寸会把行顶高、正文被拉伸贴顶，勾选框就算到文字下面去了（'BBB' 行最明显）。自绘版无最小尺寸、自带 7% 光学修正，行高由文字决定，中线由布局保证。
- [侧栏]清单条目间距归零（行高即间距）、勾选框贴第一行、移除键改 24dp 自绘点按区（原来 IconButton 的 48dp 最小尺寸把每行顶到 48dp）。

### 三、排版重排（五屏）
- [全局]统一 **16dp 外边距**：待办页（列表 14→16dp）、笔记列表、编辑器（顶栏 9→7dp 让图标落 16dp 线）、设置页（返回箭头字形 28.2→16.4dp）、侧栏面板（18→16dp）。
- [全局]间距落 **4dp 网格**：卡片内边距 19/23→20/20、胶囊 42→44dp、设置页 21/25/30/17/15dp → 20/24/32/16/16dp、编辑器 19/14/10/11/9/13dp → 20/16/12/12/8/12dp、侧栏 21/18/13/15dp → 20/20/12/16dp。
- [全局]触控目标 ≥44dp：侧栏两个"+" 38→44dp；审计工具 `tools/verify/layout_audit.py` 全屏扫描（剩余唯一 <44dp 的是侧栏勾选框 33.9×56dp，面积与水米笔记 1em 勾选框一致，保留并留档）。
- [待办]长按进多选**不再整体上移**：页头动作行钉死 32dp、两种状态共用同一 `TodoCardBody` 与同一套边距（实测此前「待办」−10px、首卡 −23px、第四张 −134px 的累计位移，现全部 ±2px 内）。

### 四、动效与提示
- [编辑器]去掉日常「待保存/保存中」悬浮提示（秒存不需要）；**失败/冲突仍然如实提示**（规范 §8）；退出前的 `flushAndAwait` 继续保证先落库再退。
- [动效]新增动效令牌 `Motion.EDITOR_OUT = 420ms`，编辑器退场放慢（原 320ms）——保存已 await，退场太急会让人以为"没存就跑了"。
- [动效]清单编辑器条目加 `animateItem()`：回车新建勾选栏/删除条目走位移+淡入。

### 五、验收与遗留
- 单测全绿、`lintDebug` 通过、`assembleDebug` 通过（APK 内版本已核）。
- 设备端：`sidebar_verify` **16/16**、`todo_selection_verify` 6/6、`theme_verify` 5/5、`layout_audit` 五屏全过；其余套件见 `run_all.py` 汇总。
- **更正**：上一版说"AOSP 13 一定拒绝撤回前台服务通知"结论过强——本版实测 S3「隐藏保活通知」**PASS**（撤回调用成功后系统没有再挂回来），与机型/时序有关。
- 遗留：`checkbox_align_verify.py` 的"界面树窗口"与"截图"分两次取，窗口可能错位导致数值虚高（本轮改用同一裁切框的前后对比确认），下一步把工具改成同帧取样再纳入回归。

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