# GLM 执行任务书：备份 UI 接线 + WebDAV 云同步

> 本文档交给下一个执行 AI（GLM）照着做。**先读 `AGENTS.md` 和本文件全文，再动手。**
> 复杂且易错的部分（数据格式、跨设备 ID 重映射、合并算法、事务、zip 安全）已由 ZCode 完成并验证，
> 你只需要做 UI 接线与云同步实现，**不要重写备份核心**。

---

## 0. 背景与现状（截至 2026-09-13）

**产品**：纯记 PureNote，Android 本地优先笔记/待办，包名 `com.purenote.local`，仓库 `seruabai/xmnote`。
**用户痛点**：换手机后笔记（含图片、录音）不能丢。用户不懂技术，需求由 AI 判断实现方式。
**已定路线**（见 `wiki/SYNC_DESIGN.md` v2 与 `wiki/DECISIONS.md`）：
1. **先备份，后同步**。备份不依赖任何云厂商接口，是换机主力方案。
2. 云同步走"用户自带云盘"，**第一实现 WebDAV（坚果云）**。
3. 单一抽象 `RemoteFileStore` 承载 WebDAV / S3 / 自研 REST；**不允许两套并行接口**。
4. 用户明确：**v1 不加密**（明文存网盘）；文字与附件都要同步。

**已完成且已验证（不要重做、不要改签名）**：

| 文件 | 作用 | 验证 |
|------|------|------|
| `backup/BackupModels.kt` | 备份 schema（`BackupFile`/`NoteDto`/`TodoDto`/`FolderDto`） | — |
| `backup/BackupJson.kt` | JSON 读写、版本校验、`BackupFormatException` | 单测 6 |
| `backup/BackupMerger.kt` | 纯逻辑合并（LWW、按名去重分类、幂等、孤儿提升） | 单测 15 |
| `backup/BackupCodec.kt` | DB ⇄ 备份模型；uuid 重映射；导入单事务 | 真机测试 |
| `backup/BackupIo.kt` | zip 打包（原子替换）/ 导入（Zip Slip 防护） | 真机测试 |
| `backup/BackupPaths.kt` | 条目名安全清洗 | 单测 6 |
| `data/NoteRepository.kt` | **新增三个入口**：`exportBackup` / `importBackup` / `readBackup` | 编译 |

**已有可复用的 API（直接用，不要改）**：

```kotlin
// NoteRepository（UI 只能通过它访问备份，不许直接碰 NotesDb）
suspend fun exportBackup(target: File, appVersion: String): BackupIo.ExportResult
suspend fun importBackup(source: InputStream): BackupIo.ImportResult
suspend fun readBackup(source: InputStream): BackupFile

// BackupIo.ExportResult: file, noteCount, todoCount, folderCount, attachmentCount,
//                        attachmentBytes, missingAttachments, referencedAttachments, summary
// BackupIo.ImportResult: inserted, updated, skipped, attachmentsRestored,
//                        attachmentsSkippedExisting, warnings, summary
```

---

## 任务 A：备份 UI 接线（先做，独立可交付）

### 目标
设置页出现"备份与恢复"一栏，用户能把整库导出成一个文件、也能从文件导入。

### 步骤

**A1. 取版本号（注意：项目已关闭 `buildConfig`，不能从 `BuildConfig` 取！）**
```kotlin
val appVersion = context.packageManager
    .getPackageInfo(context.packageName, 0).versionName ?: ""
```
若 `getPackageInfo` 在该 API 级别有重载警告，用 `PackageInfoFlags.of(0)` 并加 `@Suppress("DEPRECATION")` 兜底。

**A2. 导出：用系统文件选择器（SAF），不要申请存储权限**
- 入口：设置页新增 `SettingsSectionTitle("备份与恢复")` + `ArrowRow("导出备份")`。
- 用 `ActivityResultContracts.CreateDocument("application/zip")` 让用户选保存位置，文件名建议
  `purenote-backup-yyyyMMdd-HHmm.purenote.zip`（用 `DateFormats`，别自己拼 SimpleDateFormat）。
- 拿到 `Uri` 后：`context.contentResolver.openOutputStream(uri)` → 但 `exportBackup` 需要的是 `File`。
  **做法**：先导出到 `context.cacheDir` 下的临时文件，再用 `contentResolver` 把临时文件拷到用户选的 `Uri`，
  最后删临时文件。**不要**尝试把 `Uri` 转成 `File`。
- 必须在工作线程（`viewModelScope` + Repository 已内部 `Dispatchers.IO`）。
- 完成后弹结果：`result.summary`，并在 `missingAttachments > 0` 时提示"有 N 个附件文件缺失，未能打包"。

**A3. 导入：先预览再确认，不能直接写**
- 入口：`ArrowRow("从备份恢复")` → `ActivityResultContracts.OpenDocument()`
  （mime 建议 `arrayOf("application/zip", "application/octet-stream", "*/*")`，因为各家文件管理器给的 mime 不一致）。
- 流程：`readBackup(inputStream)` → 得到 `BackupFile` → **弹确认框**显示
  "将导入笔记 N 条、待办 M 条、分类 K 个（已有内容按更新时间自动保留最新，重复导入不会产生重复）"
  → 用户确认后才 `importBackup(inputStream)`。
- **注意**：`InputStream` 只能消费一次。预览和导入各自重新 `contentResolver.openInputStream(uri)` 一次。
- 完成后弹结果：`result.summary`；`warnings` 非空时用可滚动文本展示（截断到前 10 条，其余显示"等 N 条"）。
- 导入成功后调用 `vm.refresh()`（ViewModel 已有）刷新界面。

**A4. 需要新增的 ViewModel 状态**
在 `NoteViewModel` 加备份进行中的状态（不要用全局 Toast 代替进度）：
```kotlin
// 建议：sealed interface BackupState { Idle, Running, Done(summary), Failed(message) }
// 以及 fun exportBackup(uri: Uri) / fun importBackup(uri: Uri)
```
UI 在 `Running` 时禁用按钮并显示进度指示。

### 验收（任务 A）
1. `./gradlew.bat :app:compileDebugKotlin` 通过。
2. 真机/模拟器：导出后能找到一个 `.purenote.zip`，用解压工具打开能看到 `backup.json` 与 `attachments/`。
3. **换机模拟（必须做）**：导出 → 设置里卸载重装（或清空应用数据）→ 导入该文件 → 笔记、待办、分类、
   置顶、废纸篓、图片、录音全部回来；**重复导入同一文件不产生重复数据**。
4. 导入一个随便改坏的 zip（比如手动删掉 `backup.json`）→ 必须给出可读错误提示，**不能崩溃**。
5. 用 `adb shell run-as com.purenote.local sqlite3 databases/purenote.db "PRAGMA integrity_check;"` 返回 `ok`。

---

## 任务 B：WebDAV 云同步（后做，依赖任务 A 的导出能力）

### 目标
设置页有独立"云同步"栏：可选 WebDAV 后端、自动同步周期可配置可关闭、有"立即同步"按钮。
笔记与废纸篓都同步。

### 步骤

**B1. 抽象层（严格照此签名，不要发明第二套接口）**
新建 `sync/RemoteFileStore.kt`：
```kotlin
data class RemoteEntry(val path: String, val size: Long, val lastModified: Long?, val etag: String?)
data class StoreCapabilities(
    val maxObjectBytes: Long?, val listPageLimit: Int?,
    val supportsConditionalPut: Boolean, val supportsServerHash: Boolean,
)
sealed class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth(m: String, c: Throwable? = null) : RemoteException(m, c)
    class RateLimited(val retryAfterMs: Long?, m: String) : RemoteException(m)
    class NotFound(m: String) : RemoteException(m)
    class Conflict(m: String) : RemoteException(m)
    class TooLarge(m: String) : RemoteException(m)
}

interface RemoteFileStore {
    val capabilities: StoreCapabilities
    suspend fun list(prefix: String): List<RemoteEntry>
    suspend fun download(path: String): ByteArray
    suspend fun upload(path: String, data: ByteArray, ifMatch: String? = null): RemoteEntry
    suspend fun delete(path: String)
}
```
**禁止**在接口上出现 `provider` 标志位、`isJianguoyun` 之类厂商字段。

**B2. `WebDavStore`（`sync/WebDavStore.kt`）**
- 用 **OkHttp**（新增依赖 `com.squareup.okhttp3:okhttp`，只此一个；**不要**引 `sardine`，它维护弱且带 SimpleXml）。
- 实现 `PROPFIND`（Depth:1）、`GET`、`PUT`、`DELETE`；解析 `207 Multi-Status` XML。
- **坚果云硬限制必须处理**（否则会静默漏同步或超配额）：
  - 单次 `PROPFIND` 最多返回 **750 条** → 必须分页循环，不能一次了事。
  - 免费版 **600 请求/30 分钟** → `RateLimited` 时按 `Retry-After` 退避，不要死循环重试。
  - 单文件上限 **500MB**。
  - 认证是 **Basic + 应用密码**（不是登录密码），地址 `https://dav.jianguoyun.com/dav/`。
  - 服务器可能不给 `getlastmodified`/`getetag` → 都要按可空处理，不能假设存在。
- 凭据**必须**用 Android Keystore 加密后存（`androidx.security:security-crypto` 已废弃，**直接用 Keystore + AES-GCM**），
  **严禁明文写入 SharedPreferences**。

**B3. 同步引擎（`sync/SyncEngine.kt`）**
- 远端布局：`notes/<uuid>.json`、`trash/<uuid>.json`（墓碑）、`blobs/<sha256>`（附件）、`manifest.json`。
- push：只传本地 dirty（可先用"与上次同步快照比对 updatedAt"实现，不必先做 change journal）。
- pull：按 `since` 游标或比对 `updatedAt`，冲突用 **LWW**。
- **必须做增量**：坚果云免费版每月上传 1GB，全量同步第一次就废。首次全量只有几 MB（纯文字）没问题。
- 附件按内容哈希去重；超 500MB 或分片时给出明确提示而非静默失败。

**B4. 设置页"云同步"栏**
- 后端选择 + 动态表单：WebDAV 填「服务器地址 / 账号 / 应用密码」（内置坚果云一键预设按钮）。
- 自动同步周期：**关闭 / 15 分钟 / 1 小时 / 6 小时 / 仅手动**（持久化到 `prefs`）。
- **"立即同步"按钮** + 上次同步时间 + 结果（成功/失败原因）。
- 附件同步开关（默认关，省流量）。

**B5. Manifest**
- 加 `<uses-permission android:name="android.permission.INTERNET" />`。
- 在 `wiki/DECISIONS.md` 登记"引入网络权限与 OkHttp"。

### 验收（任务 B）
1. 编译通过；`lintDebug` 无新增严重项。
2. 用真实坚果云账号（作者提供）实测：导出→上传→在坚果云网页看到文件→清空本机→拉回，内容一致。
3. 断网时"立即同步"给出可读失败提示，不崩溃、不卡 UI。
4. 手动把自动同步设为关闭后，确认不会自行发起请求（看 `adb logcat` 无网络请求）。
5. 目录超过 750 项时验证分页：可用脚本在坚果云建 >800 个文件后确认本地能列全。
6. 凭据落盘检查：`adb shell run-as com.purenote.local cat shared_prefs/*.xml` **不得**出现明文密码。

---

## 通用验收（两个任务都要满足）

- `./gradlew.bat :app:testDebugUnitTest` 全绿（当前基线 55 个）。
- `./gradlew.bat :app:connectedDebugAndroidTest` 全绿（当前基线 2 个：换机往返、父项只在本机的层级保持）。
- 每次改动前跑 `git status` 确认无他人未提交改动（多 AI 共用此仓库）。
- 完成后更新 `wiki/CHANGELOG.md`（一行）与必要的 `wiki/DECISIONS.md`。
- **报告必须如实说明：通过 / 失败 / 未运行，以及原因。** 不得凭编译通过就宣称功能可用。

---

## 禁止事项（违反即返工）

1. **禁止重写或"优化"已完成的备份核心**（`backup/` 下 6 个文件）。有疑问先问，不要动。
2. **禁止改动 `updated_at` 语义**。备份写入必须原样回写 `updated_at`，否则 LWW 与幂等全部失效。
3. **禁止用本地自增 id 做跨设备标识**。跨表引用一律 uuid（`Db.newUuid()` 是唯一生成处）。
4. **禁止 UI 直接操作 SQLite / `NotesDb`**。必须走 `NoteRepository`（见 `AGENTS.md` 分层规定）。
5. **禁止在 `RemoteFileStore` 里开厂商专属分支**（`if (provider == "jianguoyun")` 之类）。
   厂商差异只能存在于实现类内部、`StoreCapabilities` 和异常类型。
6. **禁止明文存储密码/密钥**，禁止把密码写进日志。
7. **禁止引入重依赖**：不要 Room / Hilt / Retrofit / AWS 全量 SDK / Dropbox SDK / Google Drive SDK。
   OkHttp 与 WorkManager 是允许的上限（WorkManager 仅用于后台定时同步）。
8. **禁止用 `SimpleDateFormat` 直接拼日期**，用 `core/DateFormats.kt`。
9. **禁止申请存储权限**（`READ/WRITE_EXTERNAL_STORAGE`）；用 SAF 文件选择器。
10. **禁止跳过验收第 3 条（换机往返）**。这是本功能的唯一目的，必须真实走通。
11. **禁止擅自升版本号、打 tag、发布**。只有用户明确说"发布"才可以（见 `AGENTS.md` 第 7 节）。
12. **禁止大爆炸重构**。一次一个任务，收敛后再进入下一个。

---

## 环境备忘

- Android SDK：`C:/Android/sdk`（**不是**默认路径），构建前 `export ANDROID_SDK_ROOT=C:/Android/sdk`。
- 构建：`./gradlew.bat :app:compileDebugKotlin`（Git Bash，不要 `cd /d`）。
- 模拟器：`PureNote_API_36`；启动需设 `ANDROID_SDK_ROOT`，否则 "Broken AVD system path"。
- **直接读设备数据库**（比截图有效得多）：
  ```bash
  adb shell "run-as com.purenote.local sqlite3 databases/purenote.db 'SELECT ...;'"
  adb shell "run-as com.purenote.local sqlite3 databases/purenote.db 'EXPLAIN QUERY PLAN <SQL>;'"
  ```
  Git Bash 下操作 `/sdcard` 路径需 `export MSYS_NO_PATHCONV=1`。
- 装本地构建需先 `adb uninstall`（CI 的 APK 用 GitHub 密钥签名，与本机签名不匹配），会清数据，先导出再装。
- 修改 `Db.kt` 若升版本，必须三处一致：`onUpgrade` 加分支 + `SQL_CREATE_*` 同步改 + `DB_VERSION` +1。

---

## 建议顺序与停止点

1. 任务 A1–A4 → 跑验收 A → **提交并停下来汇报**（备份已可用，用户可立刻换机）。
2. 任务 B1–B2（抽象 + WebDAV，可先用假数据单测）→ 汇报。
3. 任务 B3–B5 → 跑验收 B → 汇报。

每步独立可交付、可回退。**不要一次做完再汇报。**
