# 云同步与备份设计（v2，2026-09-13）

> 目标：在保持「纯记」本地优先、隐私优先的前提下，解决**换机不丢数据**，并支持**多设备云同步**。
> v1（2026-09-07）曾推荐"自研 REST 后端"，因与用户"不想自建服务器、要适配自己已有的云"冲突，v2 推翻该路线并补充实测调研。

## 0. 需求与约束（用户明确）

| 项 | 用户要求 | 设计结论 |
|----|----------|----------|
| 核心痛点 | 换手机后笔记（含图片、录音）不丢 | **备份**是第一优先级，且不依赖任何厂商开放接口 |
| 同步范围 | 笔记 + 废纸篓都要同步 | 双向、含软删除（tombstone） |
| 云的选择 | 不知道自己会用哪家云，**不愿自建服务器** | 走"用户自带云盘"路线；抽象层隔离协议 |
| vivo 云 / 小米云 | 希望用手机自带云 | ❌ **不可行**，见 §1，接口不对第三方开放 |
| 加密 | v1 不加密，明文存网盘 | v1 明文；抽象层预留，后续可加 E2EE |
| 附件 | 文字和附件都要同步 | 附件走同一通道，需增量与分块 |
| 设置界面 | 独立"云同步"栏、周期可配可关、"立即同步"按钮 | 见 §4 设置页 |
| 多后端 | 自研 REST 与 WebDAV 都要能接 | 单一 `RemoteFileStore` 抽象，见 §4.2 |

## 1. 调研结论（2026-09，逐家查官方开发者门户）

**手机厂商云一律不可作为第三方 App 的存储后端**（除华为 Drive Kit 厂商锁定外）：

| 服务 | 对第三方开放 | 证据 |
|------|--------------|------|
| 小米云 / 小米云盘 | ❌ | dev.mi.com 能力清单仅开发/分发/推广/IoT/广告，无云盘文件 API |
| **vivo 云** | ❌ | dev.vivo.com.cn 能力为适配/OriginOS/AI/Game/IoT/Media/分发，无云存储 API |
| OPPO / 欢太云 | ❌ | 欢太云定位便签/照片/联系人/短信备份，无开发者文件 API |
| 腾讯微云 | ❌ | open.weiyun.com 302 跳消费页，无开发者文档 |
| 百度网盘 | ⚠️ 仅企业 | 需营业执照 + 人工审核；App 仅能操作 `/apps/{app}` 单目录 |
| 华为云空间 Drive Kit | ✅（唯一例外） | HMS Core 开放，但仅限华为云空间账号体系，且新版"仅会员可用云盘" |
| **坚果云 WebDAV** | ✅ | 注册即用（账号 + 应用密码） |

**其他可选后端**（面向国内个人开发者）：

| 方案 | 协议 | 门槛 | 免费额度 | 备注 |
|------|------|------|----------|------|
| 坚果云 | WebDAV | 注册 | 上行 1GB/月、下行 3GB/月；限速 600 请求/30 分钟；单次列举 750 项 | 国内直连最稳；**必须分页**否则漏文件 |
| infiniCLOUD | WebDAV | 注册 | **20GB** | 日本机房，国内可直连，额度最大 |
| 七牛云 Kodo | S3/HTTP | 实名 | 10GB 存储 + 10GB CDN 回源 | 国内快；免费为"标准存储"专用 |
| 腾讯云 COS | S3 | 实名 | 50GB/6 个月新客 | **不含免费流量**，下行另计费 |
| Cloudflare R2 | S3 | 注册 | 10GB + 出网免费 | 大陆无 POP，延迟不稳（大陆优化仅企业版） |

**结论：WebDAV 作为第一实现**（理由：国内可用、零成本、不绑定厂商、协议简单到可用 OkHttp 直接实现，无需引入任何厂商 SDK）；S3 作为第二驱动；自研 REST 仅作为抽象层上的一个可选实现位。

## 2. 总体架构：先备份，后同步

两条路互相独立，**备份不需要云端接口，也不受配额限制**：

- **P0 备份（不依赖网络）**：应用内"导出/导入备份" → 一个包含数据库 + 图片 + 录音的压缩包。用户用互传/微信/数据线/任意网盘搬运。这是换机的主力方案，100% 可靠。
- **P1 云同步（依赖用户自带云盘）**：在备份的能力之上，把"整库文件"变成"增量 diff"，通过 `RemoteFileStore` 上传/拉取。

另已修复系统级换机迁移：Android 12+ 的 `device-transfer` 现已包含 `images/`（图片与录音），换机时系统层面多一条保险路径。

## 3. P0 备份：导出 / 导入

**产出物**：`.zip`（或自定义扩展名），内容：

```
purenote-backup-<yyyyMMdd-HHmm>.zip
├── manifest.json          # 格式版本、schema 版本、导出时间、条目计数、校验摘要
├── notes.db               # SQLite 快照（或 JSON 导出，见下）
├── images/                # 图片 img_*.jpg / cam_*.jpg / quick_*.jpg
└── audio/                 # 录音 aud_*.m4a（当前与 images 同目录，按前缀区分）
```

**关键决策**：
- **导出用 JSON 还是原始 db？** → 首选 **JSON + 附件**：跨 schema 版本更稳（导入时可迁移），且人可读、可被以后同步复用。但需覆盖所有表：`notes` / `todos` / `folders`。
- **导入策略**：默认**合并**（按 `uuid` 判重，`updatedAt` 新者胜），而不是"清空覆盖"——避免误操作毁掉现有数据。提供"导入预览"（将新增 N 条、更新 M 条、跳过 K 条）。
- 图片/录音当前同在 `files/images/`（靠 `img_/cam_/quick_/aud_` 前缀区分），备份需一并打包，导入时按名还原。
- 导出/导入都在 IO 线程，进度可见，失败不破坏原库（先写临时文件再原子替换）。

**验收**：导出的 zip 含全部表与附件；清空 app 数据后导入，笔记/待办/分类/置顶/废纸篓/图片/录音全部还原；重复导入同一文件不产生重复数据。

## 4. P1 云同步

### 4.1 为什么必须增量

坚果云免费版每月上传 1GB。**全量同步会在第一次就用完额度**，增量（只传变化的）才是正确设计。首次全量仅需几 MB（文字），附件按需。

### 4.2 抽象层：单一 `RemoteFileStore`（"哑文件仓库"）

**原则：抽象只描述文件动作；同步智能全部在其之上、只写一遍。**

```kotlin
data class RemoteEntry(val path: String, val size: Long, val lastModified: Long?, val etag: String?)
data class StoreCapabilities(
    val maxObjectBytes: Long?, val listPageLimit: Int?,
    val supportsConditionalPut: Boolean, val supportsServerHash: Boolean,
)
sealed class RemoteException(msg: String) : Exception(msg) {
    class Auth(msg: String) : RemoteException(msg)
    class RateLimited(val retryAfterMs: Long?) : RemoteException("rate limited")
    class NotFound(msg: String) : RemoteException(msg)
    class Conflict(msg: String) : RemoteException(msg)
    class TooLarge(msg: String) : RemoteException(msg)
}

interface RemoteFileStore {
    val capabilities: StoreCapabilities
    suspend fun list(prefix: String): List<RemoteEntry>            // 实现内部负责分页
    suspend fun download(path: String): ByteArray
    suspend fun upload(path: String, data: ByteArray, ifMatch: String? = null): RemoteEntry
    suspend fun delete(path: String)
}
```

- **实现类**：`WebDavStore`（坚果云/Nextcloud/infiniCLOUD）、`S3Store`（七牛/COS/R2）、`RestStore`（自研，预留位）。加第二家**只新增实现类 + 设置页表单**，引擎与冲突逻辑不动。
- **禁止**接口出现 `provider` 标志位或厂商字段；厂商差异只允许存在于实现内部、`StoreCapabilities` 与异常类型。
- 传输统一 OkHttp；WebDAV 手写 5 个动词 + 解析 `207 Multi-Status`（不引 sardine，其维护弱且带 SimpleXml 依赖）。

### 4.3 同步引擎（写在抽象之上）

- **数据布局**：`notes/<uuid>.json`、`trash/<uuid>.json`（tombstone，带 deletedAt）、`blobs/<sha256>`（附件，内容寻址去重）、`manifest.json`。
- **变更检测**：本地维护 dirty 集合（Db 写路径统一上报），push 只传 dirty。
- **冲突**：单用户多设备用 LWW（按 `updatedAt`）；但**时钟不可信**，以"本地修订序号 + 服务端 etag"作为判定辅助，避免新笔记被设备时钟偏差覆盖。
- **墓碑**：`trashed + trashed_at` 直接当 tombstone（30 天清理窗口即墓碑保留期）。
- **附件**：按内容哈希去重上传；WebDAV 无标准分块，超限文件分片后重组，或对坚果云免费版限制单文件体积。
- **限流**：坚果云 600 请求/30 分钟，必须做退避与节流；列表分页（750 项/次）不可省。
- **凭据**：应用密码/密钥必须用 Android Keystore 保管（`security-crypto` 已废弃，直接用 Keystore + AES-GCM）。

### 4.4 设置页"云同步"栏

- 后端选择 + 动态表单：WebDAV（服务器地址/账号/应用密码，内置坚果云一键预设）/ S3（Endpoint/Region/Bucket/AK/SK）/ 自研 REST。
- 自动同步周期：关闭 / 15 分钟 / 1 小时 / 6 小时 / 仅手动；后台调度用 WorkManager（系统组件，非重型依赖）。
- **立即同步**按钮 + 上次同步时间 + 结果（成功/失败原因）。
- 附件同步开关（省流量）。

## 5. 落地顺序

| 阶段 | 内容 | 依赖 | 风险 |
|------|------|------|------|
| P0-a | 导出/导入备份（含附件） | 无 | 低 |
| P0-b | 修复系统换机迁移含附件 | 无 | 低（**已完成**） |
| P1-a | `RemoteFileStore` + `WebDavStore` + 设置页表单 | P0-a | 中 |
| P1-b | 同步引擎（dirty/游标/LWW/tombstone） | P1-a | 高 |
| P1-c | 附件同步 + 分片 | P1-b | 中 |
| P1-d | `S3Store`（七牛/COS） | P1-a | 低（复用引擎） |
| P2 | 可选 E2EE | P1 | 中 |

## 6. 改动约定（沿用）

- 涉及 `Db.kt` 升版本必须：`onUpgrade` 加分支 + `SQL_CREATE_*` 同步改 + `DB_VERSION` +1，三处一致。
- uuid 生成只在 `Db.newUuid()`；禁止用自增 id 做跨设备标识。
- 同步层一律走 `NoteRepository`，UI 不许直接碰 Db。
- 加 INTERNET 权限、引入任何网络依赖前后都要在 `DECISIONS.md` 登记。

## 7. 待决问题

1. 导出格式最终用 JSON 还是 SQLite 快照（JSON 更稳但要写全表序列化与版本迁移）。
2. 附件是否默认同步（用户选了"要"，但坚果云免费额度下建议默认关、按需开）。
3. 是否引入 WorkManager（判断：值得，属系统组件）。
4. 第二驱动选七牛还是 infiniCLOUD。
