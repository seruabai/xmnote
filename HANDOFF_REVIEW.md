# xmnote-apple 交接审查文档

> **用途**:交给 DeepSeek 等审查者,对 `E:\ox\xmnote-apple` 仓库的全部改动做代码审查。
> 本文档自包含,无需原始会话上下文。**基准提交**:`7ee922b`(即原版 `E:\ox\xmnote` 的 main,未做任何改动)。
> 生成日期:2026-09-13。

---

## 1. 背景与仓库布局

| 路径 | 说明 |
|---|---|
| `E:\ox\xmnote` | **原版仓库,v1.2.20 后(7ee922b),全程未动** |
| `E:\ox\xmnote-apple` | **副本,全部修改在这里**,6 个提交,本地 main 未推送远端 |
| `E:\ox\xmnote-apple\tools\verify\` | 三套参数化验收脚本 + 验收夹具(本次随文档入库) |

应用信息:Android + Jetpack Compose + Material 3,包名 `com.purenote.local`,应用名"纯记",minSdk 24 / targetSdk 36。UI 要求:苹果 iOS 风(用户 2026-09-13 定调),既有交互需求(勾选框独立控件等)不得违背。

## 2. 提交总览(按顺序,审查建议按此序看 diff)

| # | commit | 主题 | 规模 |
|---|---|---|---|
| 1 | `afa366d` | 苹果风主题改造(色板/字阶/圆角/闪屏) | Theme.kt 重写 + 7 文件 |
| 2 | `a90059b` | 首页统一 16dp 对齐栅格 | HomeScreen 为主 |
| 3 | `5d57ef7` | 编辑器深度改造(录音授权/内联图片/退格语义/样式面板/IME) | EditorScreen 大改 |
| 4 | `9beee87` | 待办编辑保持原位 | 排序 + DB 写集 |
| 5 | `65a6528` | **正文存储格式 Markdown 化(最重要)** | NoteText 重写 + DB v8 迁移 |
| 6 | `df44801` | 慢速高级动效体系 | Motion.kt 新增 + 全局接入 |

合计 24 文件,+1529/−453;新增测试 3 个文件 28 项。

**复现命令**(Git Bash,工作目录 `E:/ox/xmnote-apple`):

```bash
./gradlew.bat testDebugUnitTest --console=plain          # 88 项单元测试,应全绿
./gradlew.bat assembleDebug --console=plain              # 构建
adb install -r app/build/outputs/apk/debug/app-debug.apk # 安装(Android 16 模拟器)
python tools/verify/audit_home.py                        # 首页 7 项栅格断言(需先启动应用停在笔记页)
python tools/verify/device_verify.py                     # 编辑器 27 项端到端断言
python tools/verify/todo_order_verify.py                 # 待办原位 4 项断言
```

设备脚本依赖:API 36 模拟器(1080x2400,420dpi,1dp=2.625px)、`adb` 在 `C:/Android/sdk/platform-tools`、`tools/verify/verify_seed.sql` 已 push 到 `/data/local/tmp/vseed.sql`(脚本会自行 `run-as` 执行)。**注意:模拟器的 `animator_duration_scale` 必须为 1.0**——Compose 动画尊重该系统尺度,若被设为 0 所有动画瞬时完成。

---

## 3. 各批次修改内容与设计决策

### 批次一 `afa366d` 苹果风主题

- `ui/theme/Theme.kt` 全重写:iOS 系统色板(浅色背景 `#F2F2F7`+白卡+系统蓝 `#007AFF`;深色纯黑+`#1C1C1E`+`#0A84FF`),SF 风字阶(大标题 34 Bold/Body 17/Footnote 13),Shapes 8/10/14/18/24。`PureNoteTheme` 签名不变。
- `HomeScreen.kt`:大标题 39sp→34 Bold;底部导航去黑色胶囊改"选中蓝 tint+灰未选中";FAB 56dp 改 iOS 右下角新建钮;`MiSettingsButton` 齿轮 Canvas 颜色从硬编码 `#222222` 改 `colorScheme.onSurface`(**修复深色模式 bug**)。
- `Common.kt`:笔记纸色盘 6 色改极淡 iOS 调(白/gray6/米杏/雾蓝/薄荷/雾粉,深色配套);`noteContainerColor` API 不变。
- `NoteCard/TodoCard/SettingsScreen/TrashScreen/EditorScreen`:散落硬编码色(选中灰 `#F0F0F0`、手柄灰、章节标题 `#8993B0`、图片占位 `#B8860B`)全部改主题派生。
- 闪屏:`values[-night][-v31]/themes.xml` 4 变体,`windowBackground`/`windowSplashScreenBackground` 浅灰+夜间黑。**启动图标(launcher icon)未改,仍是旧奶油风。**

### 批次二 `a90059b` 16dp 对齐栅格

- 首页大标题 start 20→16dp、搜索框/分类条/单列与瀑布流卡片外缘统一 16dp、瀑布流列间隙 8→10dp、顶栏图标视觉右缘对齐 16dp 栅格、多选栏 22→16dp。
- **审查要点**:48dp 最小触控容器以图标视觉为中心**对称**扩展,所以"视觉右缘 = 屏宽 − padding";当时按"padding+扩展一半"推算错过一次,断言抓住后修正。`tools/verify/audit_home.py` 用 uiautomator bounds ÷2.625 逐项断言。

### 批次三 `5d57ef7` 编辑器深度改造

功能四项 + IME 稳定化,全部在 `EditorScreen.kt`(及 `NoteText.kt` 前身):

1. **录音修复**:根因是 `RECORD_AUDIO` 只在 Manifest 声明、运行时从未请求,`MediaRecorder.start()` 抛 SecurityException 被 `runCatching` 吞掉→"录音功能消失"。现加 `RequestPermission` 启动器,授权回调自动开录;工具栏可见性条件从 `imeVisible` 扩为 `imeVisible || recording`(否则授权回来找不到停止键)。
2. **IME 确定性收起**:点"图片/手写"先 `hideIme()`(focusManager.clearFocus + WindowInsetsController 隐藏);相机/相册结果回调里再 `settleImeAfterReturn()`(600/1200ms 各一次延迟收起);`MainActivity` 设 `SOFT_INPUT_STATE_ALWAYS_HIDDEN`(只抑制窗口聚焦自动弹起,显式请求不受影响)。
3. **勾选框退格语义**:光标在任务行内容起点按退格 = **整个前缀一次移除**(旧行为:先删空格变"小方框"再删字符),下一次退格才正常合并上一行;图片行是**原子块**:删其任一字符/相邻换行 → 整行删除,光标永远进不了 `[img:]`/`![]()` 标记内部。核心逻辑为纯函数 `backspaceIntercept(oldText, cursorAfter): Pair<String,Int>?`,UI 层只是胶水。
4. **图片内联缩略图 + 原子块渲染**:`VisualTransformation` 把图片行字符透明零宽化(`\u2060` 等长替换),行后追加 `IMAGE_BLOCK_EXTRA_LINES=3` 个真实换行撑出竖向空间;自定义 `OffsetMapping`(origToTrans IntArray + 反向二分,展开区钳到行尾)维护原文↔变换坐标;覆盖层 `MarkupOverlay` 在块区域放真实 `AsyncThumb`(点按大图预览,`MotionDialogEnter` 入场)与录音条。**为什么不直接渲染图片:BasicTextField 无法内联非文本块,此方案保住现有编辑管线。**
5. **样式面板**:工具栏第 5 键"标题(级别轮换)"改为"样式面板"——H1/H2/H3、`•` 无序、`1.` 有序、`❝` 引用、首行缩进、尾行缩进,右侧固定 X 关闭。有序列表回车继承自动 +1。
6. **持久化统一**:相机拍照从"进顶部附件条"改为与相册/手写一致插入正文流;`persist()` 的 images = 历史附件条 ∪ `NoteMarkup.imageNames(body)`(正文是图片唯一事实来源);顶部附件条过滤掉已在正文中的条目与 `aud_`。

**审查要点**:批次五把前缀语法换成了 Markdown,审查时以**批次五之后的最终语法**为准(下方 §5),不要按本批次当时的 `☐ ` 前缀语法提意见。

### 批次四 `9beee87` 待办编辑保持原位

用户要求:编辑待办后位置不变(旧行为:排序末级 `updatedAt DESC`,编辑即跳顶;且 `updateTodo` 每次把 `sort_index` 重置 0,拖拽排序也被破坏)。

- `TodoGroups.kt` 新增 `sortRootTodos(todos)`:未完成在前 → `sortIndex` → **`createdAt` 倒序**。新建仍在顶部、完成沉底、拖拽持久化的 sortIndex 照常生效。
- `Db.updateTodo` **删除 sortIndex 参数与写入**;`NoteRepository.updateTodo` 适配;`updateTodoSortIndex`(拖拽专用)不变。调用方:主界面编辑面板与速记侧栏 `QuickCaptureService` 都走 `repo.updateTodo`,自动受益。
- **明确不改**:笔记列表仍按 `updated_at` 排序(笔记编辑置顶是常规行为,用户未要求改)。

### 批次五 `65a6528` 正文 Markdown 化(核心)

**动机**(用户原话):私有标记格式导出不了、兼容性差。改后正文即标准 Markdown,导出/备份/跨应用可读。

**新语法**(存储层 `NoteText.kt` 的 `NoteMarkup`):

| 功能 | 旧格式(v1.2.20 及更早) | 新格式(Markdown) |
|---|---|---|
| 标题 H1/H2/H3 | PUA 字符 `\uE201/\uE202/\uE203` | `# ` / `## ` / `### `(行首,最多 3 级) |
| 勾选框 | `☐ ` / `☑ ` | `- [ ] ` / `- [x] `(标准任务语法) |
| 图片/录音/手写块 | `[img:文件名]` 整行 | `![](文件名)` 整行 |
| 无序/有序/引用/缩进 | `- ` / `N. ` / `> ` / `　　` | 不变 |

关键实现:

- **迁移**:`NoteMarkup.migrateBodyV1toV2(raw)` 纯函数(可叠加标题+任务:`\uE202☐ X`→`## - [ ] X`;幂等:对已是 Markdown 的输入原样返回;`[img:x]`→`![](x)`)。`Db.kt` `DB_VERSION 7→8`,`onUpgrade oldVersion<8` 时对 `kind=0` 的 notes 逐行搬运。**已在真机存量数据上验证:PUA 残留计数=0。**
- **解析**:`tagInfo(line)` 统一产出 `TagInfo(headLen, level, tag, tagLen, number)`;**优先级:任务 `- [ ] ` 先于无序 `- `**(两者都以 `- ` 开头);标题正则 `^(#{1,3}) `。
- **渲染**(`transformNoteText`,EditorScreen.kt):标题 `# ` 等标记字符用零宽连接符 `\u2060` 等长替换(视觉隐藏);任务 6 字符前缀渲染为等长占位 `"\u3000\u3000"+"\u2002"×4`(勾选方块画在前 2 字符 bbox,文本缩进 3em);无序 `- ` 等长显示为 `• `;图片行 `\u2060` 透明化 + 行后追加 3 个真实换行撑高(见下)。
- **OffsetMapping**:`origToTrans: IntArray`(原文偏移→变换偏移)+ 反向二分;图片块记录 `ImageBlock(origStart, origEnd, transStart, transEnd)`,反向映射把展开区钳到原文行尾(光标进不了标记内部);行内其余偏移 1:1。`IMAGE_BLOCK_EXTRA_LINES=3`(块高≈4 行 ≈112dp,缩略图 1.4:1 铺满块区)。
- **退格拦截** `backspaceIntercept`:三种情况(标签行整前缀移除/图片行整行删除/普通行为 null 交给默认),纯函数有单测。
- **预览/卡片/分享**:`stripHeadingMarkers` 剥 `#`;`previewText` 把任务行转 `☐ `/`☑ ` 字形、图片行转 `［图片］/［录音］`;分享出去的正文**即 Markdown 源码**(用户目标)。
- **未受影响**:`ChecklistCodec`(kind=CHECKLIST 的清单存 todos 表,不经 body;其 `SEP` 是**不可见的 0x1F 控制字符**,重写文件时务必保留——本次靠 git 历史恢复)、待办表结构、备份 JSON(存原文,迁移后新备份自然携带 Markdown)。

**审查要点**:
1. 迁移幂等性与叠加行(标题+任务、连续多图片行)——单测覆盖,请复核边界(如正文里恰好有字面 `# ` 开头的历史文本会被识别为标题,属 Markdown 语义,是否接受)。
2. `![](文件名)` 中文件名来自应用生成的 `img_/aud_/draw_` 前缀,无空格/括号,不与 Markdown 语法冲突;但**用户手输** `![](x)` 也会被当图片行(clamp 不可编辑,`onValueChange` 有拆行修复),是否接受。
3. 任务占位 6 字符宽度在不同字体下的对齐(现用 2 全角+4 半角空格,Roboto 实测 311×222px@420dpi)。

### 批次六 `df44801` 慢速高级动效体系

- `ui/Motion.kt`(新):动效令牌。转场 420ms `screenSpring`(阻尼 0.86/刚度 380,轻过冲)、弹层 `sheetSpring`(0.95/340)、微交互 `pressSpring`(MediumBouncy/StiffnessMedium)、时长 SCREEN_IN 420 / SCREEN_OUT 320 / SHEET 380 / EXPAND 300 / FAST 160 / FADE 240、缓动 EaseOut/Emphasized/EaseIn。
- `AppRoot.kt`:iOS 式视差转场——推入:新页整幅从右滑入(spring)+淡入,旧页左移 1/3+淡出;返回反向且**旧页在上层**(`zIndex` 按 `Screen.depth()`);替换旧 tween(300)。
- 按压缩放:FAB(0.9,`interactionSource`+`graphicsLayer`)、笔记卡片(0.97,`indication=null` 去水波)。
- 底栏图标选中 1.15 弹性弹出;勾选方块:填充弹性淡入 + 对勾从中心 `withTransform(scale)` 弹出(每 zone 一个 `Animatable`,`key(z.start)`);工具栏随键盘 `AnimatedVisibility` 滑入滑出;样式面板 `AnimatedContent` 淡切;手写板/大图预览走 `MotionDialogEnter`(0.92→1 缩放+淡入);待办编辑面板(TodoEditSheet)原已有自己的入场动画,未动。
- `MotionTokensTest` 6 项把"慢速高级"固化为断言(时长区间/阻尼区间/缓动端点)。

---

## 4. 验收证据(参数化,用户强制要求,禁止截图目测)

| 套件 | 项数 | 覆盖 |
|---|---|---|
| `app/src/test/...`(JVM 单元测试) | 全项目 88 项,其中本次新增 33(NoteMarkupAndTransformTest 22 / TodoGroupsSortTest 5 / MotionTokensTest 6) | Markdown 语法/迁移幂等/映射端点/退格拦截/排序语义/动效令牌 |
| `tools/verify/device_verify.py` | 28 | 编辑器端到端:夹具(Markdown 三行体)→缩略图节点尺寸与比例→大图预览(窗口计数+1)→样式面板 8 键→`- `写入→`# `写入→勾选退格两次(整前缀删/合并行)→录音(权限弹窗→停止键→`![](aud_…)` 入库)→**P4B 录音中退出不写入正文**→IME 三段断言(弹出/选择器收起/返回不回跳) |
| `tools/verify/todo_order_verify.py` | 4 | 夹具两条待办→编辑(模拟 updateTodo 写集:title+updated_at)→**位置坐标前后逐像素一致**→sort_index 未污染 |
| `tools/verify/audit_home.py` | 7 | 首页 16dp 栅格(大标题/搜索/分类条/卡片左右缘/顶栏图标/列间隙) |

最近一轮全绿记录:单测 88/88,设备 28/28 + 4/4 + 7/7(模拟器 emulator-5554,API 36,Debug APK 实装)。

## 5. 明确的"未修改范围"(避免误判为遗漏)

1. **原版仓库 `E:\ox\xmnote` 分毫未动**;副本提交未推送远端。
2. **QuickCaptureService 速记侧栏**(1555 行,独立窗口):未套新主题、未接 Markdown 渲染(它创建的正文是纯文本,兼容)。
3. **启动图标/品牌资产**:仍是旧奶油便签风。
4. **行内样式 B/I/U/S 与段落对齐**:未实现——需要把存储格式升级为带 span 的格式,当前 Markdown 行级语法不支持,已向用户说明暂缓。
5. **共享元素转场**(卡片→编辑器 hero 动画):未实现,Compose 1.7+ `SharedTransitionLayout` 可做,已列为候选。
6. **独立 .md 文件导出入口**:分享出去的文本已是 Markdown;专门的"导出 .md+图片文件夹"未做。
7. TodoEditSheet 内部布局、TrashScreen/FoldersScreen 深度对齐、桌面小组件:未审计。
8. 云同步功能(用户需求池里):未开始。

## 6. DeepSeek 审查重点清单(按风险排序)

1. **迁移正确性**(`NoteMarkup.migrateBodyV1toV2` + `Db.onUpgrade oldVersion<8`):叠加行、幂等性、大正文性能(onUpgrade 主线程逐条,量级为个人笔记数)、迁移后 `updated_at` 未动(故意,不影响排序)。
2. **OffsetMapping 边界**(EditorScreen `transformNoteText`):图片行展开 + 任务/标题标记跳变叠加时 `origToTrans` 单调性;反向钳制区间 `transStart..transEnd`;文末图片行(无尾换行)的闭合;IME composing 期间 filter 被以部分文本调用时的正确性(filter 每次全量重算,理论上安全,请复核)。
3. **backspaceIntercept**:`delIdx == contentStart-1` 判定在"标题+任务"组合行、IME 组合输入(拼音串联删除可能产生多字符差异,此时拦截被跳过走默认路径——是否有漏洞)、以及与 `clampSelection` 的配合。
4. **勾选方块对齐**:6 字符占位 `"\u3000\u3000\u2002\u2002\u2002\u2002"` 在不同字号(S/M/L 三档 NoteTypeScale)与字体下的 bbox——方块尺寸是行高驱动的,宽度敏感性低,但请留意 L 档。
5. **录音状态机**:权限弹窗期间 IME 关闭→工具栏靠 `recording` 保活;录音中退出编辑器的资源清理**已修**(EditorScreen 内 `DisposableEffect(Unit)` 的 onDispose 里 `recording=false; audioRecorder.cancel()`,cancel 会删除未入库的音频文件;设备断言 P4B 覆盖)。遗留已知怪癖:同一编辑器会话内**第二次**开录,模拟器音频后端偶发拒绝(MediaRecorder.start 失败被 runCatching 吞,界面表现为"点了没反应")——真机预计不复现,若要在意可给 startRecording 加失败 Toast,请评估。
6. **imageMenu 保活 bottomBar**:菜单打开期间 IME 已隐藏,toolbar 常驻——菜单关闭后若无 IME 则工具栏整条消失,确认交互可接受。
7. **待办排序**:`sortRootTodos` 与 TodoPane 拖拽 `pendingOrder` 的时序(松手→Flow 回流窗口);`Done` 分组仍按 `doneAt ?: updatedAt`(完成时间倒序,与"原位"要求不冲突,请确认语义)。
8. **动效性能**:420ms 转场 + 视差在低端机的帧率;`AnimatedContent` 嵌套 `zIndex` 的绘制顺序。
9. **硬编码颜色残留**:正文勾选置灰 `#9E9E9E`、对勾白色 `#FAFAFA`、图片占位透明——均在 Canvas/AnnotatedString 内(Compose 主题不可达处),深浅色下是否可读。

## 7. 已知权衡与遗留(已告知用户)

- 行内样式(粗体/斜体/下划线/删除线)与段落对齐:需 span 级存储,暂缓。
- 共享元素转场、独立 .md 导出入口、速记侧栏换肤、启动图标:候选池。
- 验收脚本里 P4(编辑面板冒烟)为诊断输出不计入断言(点行开面板的自动化交互不稳定,逻辑由单测覆盖)。
- 正文里字面 `# `/`- ` 开头的历史普通文本,迁移后会被按 Markdown 语义渲染——这是格式切换的固有语义变化。

## 8. 环境备忘(审查者复现用)

- 模拟器:API 36(Android 16),`PureNote_API_36`;SDK 在 `C:/Android/sdk`(非默认路径)。
- 构建:`./gradlew.bat assembleDebug`(Git Bash;不要 `cd /d`)。
- 造数据:`tools/verify/verify_seed.sql` push 到 `/data/local/tmp/vseed.sql` 后 `adb shell "run-as com.purenote.local sh -c 'sqlite3 databases/purenote.db < /data/local/tmp/vseed.sql'"`;测试图片 `test.png` 需 push 到 `files/images/`。
- 排查工具:`adb shell uiautomator dump` + bounds÷2.625;ANR trace 在 `/data/anr/`(需 `adb root`);logcat tag `ImeTracker`(IME 请求来源)。
- 测试图片与验收夹具正文均为 Markdown 格式(批次五之后),`☐`/`[img:]` 只出现在迁移测试里。
