# PureNote 规范实施对应表（重基准版）

> 来源规范：`PureNote_技术选型与实现规范.md`（GPT，2026-09-15，518 行）
> 本文日期：2026-09-15　分支：`spec/rebuild-a-h`
> **代码基线：`E:\ox\xmnote-apple`（versionName 1.2.20 / versionCode 23 / DB_VERSION = 8）**

## 0. 重基准声明（先读这段）

规范第 3 行声明适用 `F:\GPT\xmnote`。实测该副本状态：

| | 规范分析的副本 | 本实施的真实基线 |
|---|---|---|
| HEAD | `b264339` 2026-08-29「Release PureNote **1.2.5**」 | 1.2.20（+14 提交） |
| DB_VERSION | **3** | **8** |
| Kotlin 文件 | 32 个 / 7,003 行 | **54 个 / 11,502 行** |
| `backup/` 包 | 不存在 | 存在（6 文件） |
| `ui/Motion.kt`、`NoteTypeScale.kt` | 不存在 | 存在 |
| 正文格式 | PUA 私有标记 | **标准 Markdown**（v8 迁移） |

因此规范中**所有带版本号/schema 的指令都必须重算**。以下偏差是强制性的，不是偏好：

| # | 规范原文 | 照做的后果 | 本实施改为 |
|---|---|---|---|
| D1 | §5.2「从当前 DB_VERSION=**3** 出发，先设计 **v4** 加法迁移」 | 现有用户库在 v8，`if (oldVersion < 4)` **永不执行** → `revision` 等新列永不添加 → §7 所有 `WHERE revision = ?` 报 `no such column` → **全库不可用** | 从 **v8 → v9** 加法迁移；且**禁止**使用 `oldVersion < 4` 这类会被跳过的门槛 |
| D2 | §2「`data_rules.xml` 只 include database/sharedpref」 | 已不成立，且规范给的方向是反的 | **无需修改**。`device-transfer` 已含 `file/images/`；`cloud-backup` 排除图片是刻意设计（25MB 配额，注释已写明）。仅保留「换机恢复实测」这一验收项 |
| D3 | §11「新建 `BackupExporter`/`BackupImporter`，JSONL 格式」 | 与既有实现冲突、重复造轮子 | **演进现有** `BackupIo`/`BackupCodec`（已是 ZIP：`backup.json` + `attachments/`）：补 manifest、逐项 SHA-256、流式导出；`CURRENT_SCHEMA 1 → 2` 且必须能导入 v1 |
| D4 | §2 路径 `data/local/ImageStore.kt` | 路径不存在 | 实际为 `core/ImageStore.kt` |
| D5 | §5.2「保留现有文本和清单编码（PUA）」 | 正文已是 Markdown | `body_format_version` 初值取 **2**（1=PUA 旧格式，2=Markdown）；**备份导入路径必须复用 `NoteMarkup.migrateBodyV1toV2`**（见 A0-3） |
| D6 | §3 选型表记 Kotlin 2.3.20 / AGP 9.0.1 | 需以实际为准 | 以 `gradle/libs.versions.toml` 实读值为准，实施时复核 |

## 1. 规范未覆盖、但必须先做的阻断性缺陷（新增阶段 A0）

规范 §2 明确写「不代表完整代码审计，也不证明故障已经发生」。以下 4 条已在本仓库**代码级复现 + Android 16 模拟器实测**，规范完全未涉及，但它们使阶段 A 的退出条件无法验证（应用起不来就无从说明删除入口）。

| 编号 | 缺陷 | 位置 | 实测证据 | 修法 |
|---|---|---|---|---|
| **A0-1** | 正文任一行匹配 `^\d+\. ` 且数字 > Int.MAX → `NumberFormatException`；崩在**首页卡片渲染**，屏幕显示「纯记 keeps stopping」，无法进入任何界面 | `core/NoteText.kt:113,126` `tagInfo()` | 设备 crash 堆栈：`tagInfo(NoteText.kt:126)` ← `withoutHeading(:159)` ← `stripHeadingMarkers(:271)` ← `NoteCardKt.TextCardBody(NoteCard.kt:144)`；dropbox `data_app_crash` 3 条 | `toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0` |
| **A0-2** | 同一行数据在升级时命中 `onUpgrade` → 事务回滚 → `user_version` 恒为 7 → **每次开库重试并崩溃**，永久不可用 | `Db.kt:64-80` + `NoteText.kt:330` | 设备实测：`migrateBodyV1toV2(:322)` ← `NotesDb.onUpgrade(Db.kt:72)` ← `getReadableDatabase`；回读 `PRAGMA user_version` 仍为 7 | 迁移内每行解析必须容错（同 A0-1 后自然解决）；另加 §6.3 损坏/失败保全 |
| **A0-3** | `insertImageLineAtCursor` 非空行分支未拼回尾部 → **光标行之后所有内容被永久删除**；4 个入口（拍照/相册/录音/手写）均触发，500ms 防抖自动落库 | `core/NoteText.kt:300` | 实跑：`"第一行\n第二行\n第三行"` cursor=1 → `"第一行\n![](img_1.jpg)"` | 补 `+ text.substring(range.last + 1)` |
| **A0-4** | 图片行被工具栏样式键/删除等操作破坏后不可逆；且 `persist()` 的 `imagesUnion` 随之丢文件 → **图片永久丢失** | `ui/EditorScreen.kt:352-383`、`:795-800`、`:846-858` | 设备实测：`abc\n![](test.png)` → 点「勾选」→ `abc\n- [ ] ![](test.png)`，缩略图消失 | 光标钳制纳入 `headLen`；行样式操作前校验图片行；`indexOf(']')` 改 `lastIndexOf(')')` |

另有两项性能缺陷（`EditorScreen.kt:1283` 循环内 `raw.split` 造成 O(n²)，800 行 40.6ms/次按键，实测修复后 3.0ms；`MarkupOverlay` layout/transform 不同步 + 吞异常导致主线程 141% CPU 的 ANR）——记为 **A0-5**，与 A0 同批处理。

## 2. 规范条目 → 现有代码 → 拟修改文件 → 实现方法 → 故障测试

### 阶段 A 现状与保全

| 规范条目 | 现有代码（真实位置） | 拟修改文件 | 实现方法 | 故障测试 |
|---|---|---|---|---|
| §2 图片移除直接删文件 | `EditorScreen.kt:590-591`：`imageNames.remove(name)` + `ImageStore.deleteFile(context, name)`；`core/ImageStore.kt:43-45` 直接 `File.delete()` | `EditorScreen.kt`、`core/ImageStore.kt` | 移除仅改引用；物理删除移交独立的受保护清理入口（阶段 C 后启用） | 移除图片后正文提交失败 → 原图仍存在、旧正文仍可打开 |
| §2 启动时清理回收站 | `PureNoteApp.kt:23-24`：`runCatching { repository.purgeExpiredTrash(TRASH_TTL_MS) }` | `PureNoteApp.kt`、`NoteRepository.kt:128,289`、`Db.kt:199,357` | 阶段 A 关闭自动物理清理（改为标记过期）；恢复入口在 §12 完成后再开 | 故障测试副本不被自动删除 |
| §6.3 无保全型损坏处理器 | `Db.kt:10`：`SQLiteOpenHelper(context, name, null, DB_VERSION)` —— **未传 errorHandler**，落到 `DefaultDatabaseErrorHandler`，其 `onCorruption` **会删除数据库文件** | 新增 `data/local/PreserveDatabaseErrorHandler.kt`；改 `Db.kt:10` | `onCorruption` 只置故障状态、阻止后续写入、记录无正文诊断；禁止 `deleteDatabase`/`onCreate` | 对测试副本制造损坏 → 应用不删库、不建空替代，现场可保全 |
| §6.3 打开路径核实 | `NoteRepository.kt:17` 是**唯一**生产构造点（`private val db = NotesDb(appContext)`） | `data/local/DatabaseProvider.kt`（阶段 C） | 阶段 A 先记录该唯一入口与所有 `readableDatabase`/`writableDatabase` 调用点 | 路径缺失但有历史数据 → 进入恢复状态，不静默建空库 |

**A 阶段已完成的事实核对**：生产环境数据库构造点只有 1 处（`NoteRepository.kt:17`），DB 访问点见附录 A。

### 阶段 B 事务与回执

| 规范条目 | 现有代码 | 拟修改文件 | 实现方法 | 故障测试 |
|---|---|---|---|---|
| §6.1 统一事务入口 | 全仓库**仅 1 处**显式事务：`BackupCodec.kt:157-254`（导入）。其余均为裸单语句 | 新增 `data/local/DatabaseExecutor.kt` | `writeTransaction(expectedStore, block)`：门禁 → IO → `beginTransaction` → 非 suspend block → `setTransactionSuccessful` → `endTransaction` | 事务内抛错 → 全量回滚 |
| §2 replaceSubs 无事务 | `NoteRepository.kt:242-250`：`deleteSubsOf()` → 循环 `insertTodo()` → `syncParentDoneFromChildren()`，**三步无事务** | `NoteRepository.kt`、`Db.kt` | 父+子在**同一事务**提交 | 删除旧子任务后插入失败 → 原聚合、ID、完成状态完整保留 |
| §2 deleteTodoTree / deleteFolder | `Db.kt:315-316`（先删子后删父，两条语句）；`Db.kt:222-228`（先 `UPDATE notes` 再 `DELETE folders`） | `Db.kt` | 聚合操作只在统一事务入口执行 | 中断后不留孤儿、不半更新 |
| §2 保存结果被忽略 | `NoteViewModel.kt:387-390`：`repo.saveExisting(...)` 返回值**被丢弃**，随后无条件 `Reminders.*` + `refresh()` | `NoteViewModel.kt`、`NoteRepository.kt:71` | 引入 `SaveResult` sealed 类型；调用方必须消费 | 保存失败 → 界面显示待保存，不报成功 |

### 阶段 C 修订与历史（**门槛一律为 `oldVersion < 9`**）

| 规范条目 | 现有代码 | 拟修改文件 | 实现方法 | 故障测试 |
|---|---|---|---|---|
| §5.2 加法迁移 | `Db.kt:26-81` 已有 v2..v8 分支；`DB_VERSION = 8`（`Db.kt:384`） | `Db.kt` | **v9**：notes/todos/folders 加 `revision`；notes 加 `body_format_version`（存量置 **2**）；新增 `library_meta`/`note_versions`/`todo_versions`/`operations`/`attachments`/`note_attachment_refs`/`reminder_jobs`/`import_mappings` | v8 库升级后新列存在且值正确；v1..v8 各档样本逐级升级 |
| §7 条件更新（CAS） | `Db.kt:142,147,155,164,172,181,189` 均为 `update(..., "id = ?")`，无版本条件 | `Db.kt`、新增 `data/repository/LocalNoteRepository.kt` | `UPDATE notes SET ... revision = revision + 1 WHERE id = ? AND revision = ? AND trashed = 0`；校验影响行数 == 1 | 两会话同读 revision=7 → 只有一个写到 8，另一个冲突且本地输入保留 |
| §7 幂等 | 无 `operations` 表 | 迁移 + 仓库 | 同 `operationId` 且 `request_hash` 一致 → 返回原回执 | COMMIT 后回执前中断 → 重试不产生第二次修改 |
| §5.3 历史快照 | 无 | 迁移 + 仓库 | 每次成功提交写完整快照；同内容不造新版本 | 恢复历史生成新 revision，计数不回退 |

### 阶段 D 编辑与附件

| 规范条目 | 现有代码 | 拟修改文件 | 实现方法 | 故障测试 |
|---|---|---|---|---|
| §8 编辑状态机 | 无 `EditorViewModel`；状态在 `EditorScreen.kt` 内（`var body by remember`、`revision++`、`snapshotFlow{revision}.debounce(500).collect{persist()}`，`EditorScreen.kt:213,266`） | 新增 `feature/notes/EditorViewModel.kt`、`core/note/EditorReducer.kt`、`feature/notes/SaveCoordinator.kt` | 每会话最多一个写入在途；旧回执只标记对应 `editGeneration` | generation 10 提交期间输入到 11 → 10 的回执不显示 11 已保存 |
| §10 附件不可变 | `core/ImageStore.kt:22-39` 时间戳命名、直接写目标文件、`compress` 返回值未检查、`runCatching → null` | 新增 `data/local/AttachmentStore.kt`；改 `ImageStore.kt` | UUID id + 临时写入 + SHA-256 + 发布；失败返回具体错误 | 编码返回 false / 写满磁盘 → 返回失败，不提交有效引用 |
| §10 相机暂存 | `ImageStore.newCameraTarget`（:47-50）直接把 `cam_*.jpg` 写进 `images/` | 同上 | 相机输出先进暂存区，不污染正式附件目录 | 取消拍照后正式目录无残留 |

### 阶段 E 备份（**演进现有实现，不重建**）

| 规范条目 | 现有代码 | 拟修改文件 | 实现方法 | 故障测试 |
|---|---|---|---|---|
| §11.1 格式 | `BackupIo.kt:31-33`：ZIP 内 `backup.json` + `attachments/`；`BackupModels.kt:28 CURRENT_SCHEMA = 1` | `backup/` 全包 | 加 `manifest.json`（含逐项 SHA-256、记录数、完整性状态）；`CURRENT_SCHEMA → 2` 且必须能导入 v1 | 截断包/改字节/缺附件/重复条目 → 拒绝完整恢复 |
| §11.2 一致性快照 | `BackupCodec.kt:21 export()` 直读各表；`BackupIo.kt:37` | `BackupCodec.kt` | 单事务内流式导出 + 固定附件清单 | 备份读多表期间尝试修改 → 备份对应单一状态 |
| §13.1 `body_format_version` 缺口 | 迁移只挂 `oldVersion < 8`（`Db.kt:64`）；备份导入 `BackupCodec.kt:269 put("body", dto.body)` 原样落库，**新装设备走 `onCreate` 不经过 `onUpgrade`** | `backup/`、`core/NoteText.kt` | 导入路径按 `body_format_version` 复用 `NoteMarkup.migrateBodyV1toV2`（已验证幂等） | v1.2.20 备份恢复到全新安装 → 旧标记全部迁移 |

### 阶段 F/G/H

| 规范条目 | 现有代码 | 拟修改文件 | 实现方法 |
|---|---|---|---|
| §12 vault + active 指针 | 无；`DB_NAME = "purenote.db"` 固定（`Db.kt:385`） | `data/local/DatabaseProvider.kt` | `filesDir/vaults/<storeEpoch>/` + `store-control/active.json`（AtomicFile）；`DB_VERSION` 迁移与**存储路径迁移分阶段** |
| §13.1 升级前备份 | 无 | `PureNoteApp`/`DatabaseProvider` | 备份必须发生在 `SQLiteOpenHelper` 触发 `onUpgrade` **之前** |
| §14 WorkManager 自动备份 | 无 | `platform/backup/BackupWorker.kt` | 每 `libraryId` 唯一周期任务（30 分钟目标间隔），显示最后真实成功时间 |
| §18/§14 云能力 | 无（应用当前**无网络权限**，这是产品既定约束） | `platform/backup/` | WebDAV 传输完整包；**需先把「不申请网络权限」这一产品约定提交用户决策**，不得静默改变 |

## 3. 阶段退出条件（重基准）

| 阶段 | 交付 | 退出条件 | 状态 |
|---|---|---|---|
| **A0** | 阻断性缺陷修复 + 回归测试 | 4 项设备复现全部转绿；单测全绿 | ✅ 完成 (`c0ebd64`) |
| A | 删除入口清单、损坏处理器、停用未保护清理 | 能说明每种删除入口；故障测试副本不被自动删掉 | ✅ 完成 |
| B | DatabaseExecutor、待办聚合事务、显式 SaveResult | 先删后插中断不丢旧内容；界面不忽略保存失败 | ✅ 完成 |
| C | **v9** 加法迁移、CAS、operations、历史 | 旧数据保真；并发修改可检测；历史可恢复 | ✅ 完成 |
| D | SaveCoordinator、编辑状态机、不可变附件 | 乱序/离开/失败/图片移除测试通过 | ✅ 完成 |
| E | 逻辑快照、ZIP+manifest、全量校验、SAF | 全新目录恢复并逐项验证成功 | ✅ 完成 |
| F | 存储代、active 指针、旧任务隔离 | 每个切换中断点可恢复，旧版本原始副本保留 | ✅ 完成 |
| G | WorkManager、保留策略、系统备份规则 | 真机验证；明确实际备份时间；无未解决丢数据问题 | ✅ 完成 |
| H | WebDAV 完整包传输 | 独立验证；不实现未设计的双向同步 | ✅ 完成（2026-09-16，用户已拍板申请 INTERNET） |

## 4. 现有重复实现清单（规范 §4 要求先查）

| 规范拟新增 | 仓库中已有的 | 处置 |
|---|---|---|
| `core/backup/BackupModels.kt` | `backup/BackupModels.kt`（`BackupFile`/`NoteDto`/`TodoDto`/`CURRENT_SCHEMA`） | **复用并演进** |
| `data/local/BackupExporter.kt` | `backup/BackupIo.kt:37 export()` + `BackupCodec.kt:21` | **演进，不新建** |
| `data/local/BackupImporter.kt` | `backup/BackupIo.kt:97 import()` + `BackupCodec.kt:149 apply()` + `BackupMerger.kt:60 plan()` | **演进，不新建** |
| `platform/backup/SafBackupTransport.kt` | 无（现有导出走 `BackupIo.export(target: File)`） | 新建，接入现有 `BackupIo` |
| `data/local/DatabaseProvider.kt` | `NoteRepository.kt:17` 单点构造 `NotesDb` | 新建，替换该单点 |

## 附录 A：数据库访问点清单（阶段 A 产出）

- **构造点（1）**：`NoteRepository.kt:17`
- **写入口**：`Db.kt` 共有 `insertNote:112`、`updateNote:142/147/155/164/172/181/189`、`deleteNote:193`、`purgeExpiredTrash:200`、`insertFolder:213`、`updateFolder:218`、`clearFolderNotes:222`、`deleteFolder:228`、`insertTodo:254`、`updateTodo:274/285/293/302`、`deleteSubsOf:311/315/320`、`deleteTodoTree:315-316`、`purgeExpiredTodoTrash:358`、`markAllDone:376`
- **事务**：仅 `BackupCodec.kt:157-254`
- **物理文件删除**：`ImageStore.deleteFile:43`（调用点 `EditorScreen.kt:591`）、`AudioRecorder.kt:76`（取消录音）、`BackupIo.kt:69,72`（临时文件）
- **未保护的自动清理**：`PureNoteApp.kt:23-24`
## 附录 B：阶段 H（云能力）对应表

> 完成日期：2026-09-16　分支：`spec/rebuild-a-h`　前置：阶段 E 的完整备份包（`manifest.json` + 逐项 SHA-256）
> 产品决策：2026-09-15 用户明确「联网只用于用户自己的云同步，此外无任何上传」→ 允许申请 INTERNET 权限。
> 交付边界（规范 §17 阶段 H 原文）：**WebDAV 完整包传输**，独立验证，**不顺带实现未经设计的双向同步**。

### B.1 规范条目 → 现有代码 → 拟修改文件 → 实现方法 → 故障测试

| 规范条目 | 现有代码 | 本次新增/修改 | 实现方法 | 故障测试 |
|---|---|---|---|---|
| §17 H「WebDAV 完整包传输」 | 无任何网络代码；应用此前**不申请网络权限** | `app/src/main/AndroidManifest.xml` | 加 `INTERNET` + `ACCESS_NETWORK_STATE`，并在权限处写明用途（只用于用户自选的云同步） | 未配置时不发任何请求（引擎门禁先判配置与网络） |
| §4.2「单一 RemoteFileStore 抽象，厂商差异不得外泄」 | 无 | `sync/RemoteModels.kt` | `RemoteTransport` 只有文件动作（list/stat/openRead/upload/delete）+ `StoreCapabilities` + 7 类 `RemoteException`；接口内无 provider 字段、无 ByteArray 形状（避免被迫整包进内存） | `BackupSyncEngineTest` 用内存假实现跑完整顺序 |
| §4.2「第一实现 WebDAV，手写动词 + 解析 207」 | 无 | `sync/WebDavTransport.kt`、`sync/WebDavXml.kt` | PROPFIND/MKCOL/GET/PUT/DELETE；207 用无依赖的最小解析器（不用 sardine，不引 XML 库也就没有 XXE 面） | `WebDavTransportTest`（MockWebServer 真跑 HTTP）：认证头 UTF-8、MKCOL、If-Match/412、401/429/507 映射、404→null |
| §4.3「凭据必须用 Keystore 保管」 | 无 | `sync/CredentialStore.kt`、`sync/AndroidCredentialStore.kt` | AES-GCM + `AndroidKeyStore`，密文与 IV 存 SharedPreferences；**Keystore 不可用时保存失败并如实告知，不降级为明文** | `AndroidCredentialStore` 用接口隔离，引擎测试用 `InMemoryCredentialStore` |
| §11.1/§11.2「传输的必须是可校验的完整包」 | `BackupIo.export()`（阶段 E，含 manifest） | `backup/BackupIo.kt` | 新增 `inspect(stream)`：解包 + **逐项 SHA-256 校验清单** + 取出 `backupId`/`libraryId`/计数/完整性；`readEntries`/`ReadResult` 提为 `internal` 供测试复用同一份解析 | `TestBackupPackage` 造真包，引擎测试读回后跑同一套校验 |
| §12「恢复必须换一代，不能就地覆盖」 | 阶段 F 已有存储代 | 未改动 | 云同步只**上传**，不触碰本地存储代与活动指针 | 同步失败不影响本地库（引擎只读本地、只写远端） |
| §14「只能显示实际成功时间」 | 自动备份已按此实现 | `ui/CloudSyncSection.kt` | 界面显示「尚未成功同步过 / 上次成功 <时间>（大小、条数）」；不显示进度百分比——WebDAV 的 PUT 没有可靠回执进度 | 手动核对文案来源只有 `lastSuccessAt` |
| §18「关键接口必须有真实实现，不能空方法或固定成功返回」 | 无 | 全部新增文件 | 上传后**读回远端整包**再跑一次清单校验并比对 `backupId`；校验不过不记录成功 | `BackupSyncEngineTest` 的大小不符 / 读到别人包 / 本地不完整 / 无网络 / 未配置 / 凭据丢失 六类 |

### B.2 与 SYNC_DESIGN §4.3 的差异（**有意为之，不是遗漏**）

| SYNC_DESIGN 的设想 | 阶段 H 的实际交付 | 理由 |
|---|---|---|
| `notes/<uuid>.json` 逐条布局 + dirty 集合 + LWW 冲突 | 只传**完整备份包**（`<dir>/purenote-<时间戳>.purenote.zip`） | 逐条布局是双向同步的前提，而规范 §17 明确要求「不顺带实现未经设计的双向同步」；先让"换机不丢数据"这条路可信 |
| 附件按 sha256 内容寻址、分块上传 | 附件随整包一起打包（阶段 E 已有） | 内容寻址要配合增量同步才有意义；整包传输不需要，且能复用已验证的完整性校验 |
| S3 第二驱动 | 未做 | 抽象已经留好位置（新增实现类 + 设置页表单即可），但没有真实服务器就无法"独立验证"，不做未验证的实现 |

### B.3 已知限制（如实列明）

- **单向**：只上传，不回读覆盖本地，也不删除远端旧包。多设备之间不会互相看到对方的新笔记（各自上传的是各自库的完整包）。
- **无保留策略**：远端不会自动删旧包（本地有 `BackupRetention`）。云盘配额由用户自己管理，界面不代替用户做删除决定。
- **未做限流退避**：坚果云免费版 600 请求/30 分钟，一次同步只用到 3~4 个请求（MKCOL/PUT/PROPFIND/GET），远未逼近；`RemoteException.RateLimited` 已带 `Retry-After`，接入自动同步时需要退避。
- **凭据强度**：Keystore 只保住"备份文件被拷走/应用目录被读"两类泄露；拿到设备解密能力的攻击者仍可读出密码。E2EE（SYNC_DESIGN P2）未做。
- **未在真机验证**：本次验证在 API 36 模拟器 + 本机临时 WebDAV 服务器（`10.0.2.2`）完成；真实云盘（坚果云/Nextcloud）的连接与限流行为需用户用自己的账号复测一次。
### B.4 本次实际跑过的验证（2026-09-16）

| 套件 | 命令 | 结果 |
|---|---|---|
| JVM 单测 | `gradlew.bat :app:testDebugUnitTest` | **198 项全绿**（A–G 时 164 → 新增 34：路径/207 解析/SyncContract 纯函数、MockWebServer 协议、引擎顺序与校验、凭据接口） |
| 设备端到端 | `powershell -File tools/verify/cloud_sync_webdav.ps1` | **通过**：临时起真实 wsgidav（207 Multi-Status + 真落盘），设备测试 2 项通过，服务器目录里出现 `purenote/purenote-20260915-1627.purenote.zip`（894 字节，已核对 SHA-256） |
| 设备全量 | `gradlew.bat :app:connectedDebugAndroidTest`（`ANDROID_SERIAL=emulator-5554`） | **66 项通过 / 0 失败**（另 2 项 skip：就是上面那个 WebDAV 用例在没传 `webdavHost` 时的 `assumeTrue` 跳过——临时服务器没起时它应该跳过而不是假装通过） |',
'| Lint | `gradlew.bat :app:lintDebug` | **无 error**（修掉 4 个 NewApi：`java.time.DateTimeFormatter` 要 API 26，minSdk 是 24，改用 `SimpleDateFormat`） |',
'| APK | `gradlew.bat :app:assembleDebug` | 成功，归档 `../APP/纯记+1.2.20.apk`（20.8 MB，含新增 OkHttp） |'

验证过程中真实发现并修掉的问题（都是"看起来对、实际错"的类型）：

1. **每个请求都没带 Basic 认证头**：凭证算出来了却没加到任何请求上。最初的单元测试只断言"请求到达服务器"，没抓住；改成断言 `Authorization` 头之后立刻暴露。
2. **207 里的目录条目被当成文件**：被查询的目录自己会出现在响应里，结尾斜杠有的有（Nextcloud）有的没有（wsgidav）。只按路径判断会漏判，改为显式看 `resourcetype` 是否含 `collection`。
3. **网络门禁误判国内网络**：`isOnline()` 原本要求 `NET_CAPABILITY_VALIDATED`，而 Android 的连通性探测走 Google 的 `generate_204`，在国内网络下经常判定不通过——会出现"系统说没网、实际能连坚果云"。降为 `NET_CAPABILITY_INTERNET`，真正的失败以传输异常为准。
4. **服务器回执大小不符时重传**：原先把"回执大小不对"当瞬时错误重试一次。它其实是服务器/代理在改写请求，重传不可能变好，只会多花一次上行流量和限流配额，改为直接判失败。
5. **设备测试用中文方法名导致 D8 dex 失败**：`dexBuilderDebugAndroidTest` 直接报 `Compilation failed to complete`。设备测试一律用 ASCII 方法名（JVM 测试不受影响，沿用既有中文名风格）。
