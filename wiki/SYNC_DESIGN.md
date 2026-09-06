# 云同步设计（v1 草案）

> 目标：在保持「纯记」本地优先、隐私优先卖点的前提下，增加多设备同步。
> 本文档记录协议调研结论、推荐路线与分阶段落地计划，供后续开发（含多 AI 协作）遵循。

## 0. 现状盘点（同步就绪度）

| 项 | 现状 | 结论 |
|----|------|------|
| 稳定 ID | v1.2.18 起每条 note/todo 带 `uuid`（32 位十六进制，迁移时 `hex(randomblob(16))` 补齐） | ✅ 地基已铺 |
| 软删除 | `trashed` + `trashed_at`（30 天清理=天然墓碑窗口） | ✅ 可直接当 tombstone 用 |
| 时间戳 | `created_at`/`updated_at` 全表都有，所有写路径都会刷新 | ✅ 支持 LWW 与增量游标 |
| 冲突处理 | 单用户多设备，无协作编辑需求 | LWW（按 updated_at）够用 |
| 图片 | 应用私有目录 `files/images/`，文件名即引用 | 同步时按文件名清单对账 |
| 缺 | 变更日志（change journal）、同步游标、账号层 | 见阶段计划 |

## 1. 协议/引擎调研结论（2026）

| 方案 | 代表 | 优点 | 对纯记的缺点 |
|------|------|------|--------------|
| 自研 REST + 增量游标 + LWW | Standard Notes 模型 | 服务端极薄、可自托管、E2EE 容易加 | 冲突合并粒度=整条 |
| 文件级同步 | Joplin/Obsidian (WebDAV/S3) | 零后端，坚果云/Nextcloud 用户零成本 | 粒度=整库或整文件，冲突易丢 |
| 同步引擎（Server-of-truth） | PowerSync（Kotlin SDK）、Zero（2026 1.0） | 生产级、实时 | 重依赖，违背最小依赖哲学 |
| CRDT | cr-sqlite（SQLite 扩展）、Automerge | 多端无主合并、可协作 | 集成成本高，单用户场景收益小 |
| 块/OT 协作 | Notion/腾讯文档（OT、Yjs） | 多人实时协作 | 纯记暂无协作需求 |

**推荐路线：自研轻量同步（REST + 游标 + LWW + 可选 E2EE）**，理由：
1. 与「无框架、最小依赖」的既定决策一致（DECISIONS.md）。
2. trashed/updated_at/uuid 三件套已经天然满足该协议的全部前提。
3. E2EE（Notesnook/Standard Notes 式：客户端加密，服务端只见密文）能保住「数据不出设备」的卖点。
4. 后期若要协作编辑，再评估 cr-sqlite/Automerge，不与现路线冲突。

服务端形态（两个选项，待定）：自建轻后端（Ktor/Go，REST+SQLite/Postgres）或兼容 WebDAV 的纯文件导出作兜底备份（坚果云用户友好）。**建议两者都做：WebDAV 整库导出先行（1-2 天量级），账号同步随后。**

## 2. 分阶段计划

- **P0 地基（已完成 v1.2.18）**：uuid 列 + 迁移 + 索引；Model 暴露 uuid。
- **P1 结构解耦**：`NoteRepository` 抽接口 `NoteDataSource`；Db 写路径统一走 `withChangeTracking { }`（内存 change journal，记录 dirty uuid 集合）；设置页加「导出/导入整库 JSON」。
- **P2 备份通道**：WebDAV（坚果云）整库加密导出/导入（JSON+图片 zip，AES-GCM，口令派生）。
- **P3 账号同步**：`sync_token` 游标；push=dirty 集合、pull=since 游标；冲突 LWW；图片按内容哈希去重上传。
- **P4 加密**：账号口令 + scrypt 派生密钥，客户端 AES-GCM 加密 payload，服务端零知识。
- **P5 特色增强**（与同步解耦，可穿插）：SQLite FTS5 全文搜索、版本历史快照、Markdown 导入导出（Obsidian 互通）。

## 3. 改动约定

- 涉及 `Db.kt` 升版本必须：`onUpgrade` 加分支 + `SQL_CREATE_*` 同步改 + `DB_VERSION` +1，三处一致。
- uuid 生成只在 `Db.newUuid()`；禁止用自增 id 做跨设备标识。
- 同步层一律走 `NoteRepository`，UI 不许直接碰 Db（沿用 AGENTS.md 分层）。
