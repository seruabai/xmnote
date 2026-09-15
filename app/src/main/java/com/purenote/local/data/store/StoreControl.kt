package com.purenote.local.data.store

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 活动存储指针（规范 §12）。
 *
 * [dbName] 是 SQLiteOpenHelper 的库名，因此库文件落在 `databases/<dbName>`。
 * epoch 化命名让"换一代存储"= 换一个**新文件**，而不是就地覆盖正在用的那个，
 * 于是任何一次恢复都可以在验证通过之后再用一次原子写切换过去。
 */
data class StorePointer(
    val epoch: String,
    val dbName: String,
    val libraryId: String = "",
    val pathFormatVersion: Int = CURRENT_PATH_FORMAT,
) {
    companion object {
        const val CURRENT_PATH_FORMAT = 1
    }
}

/** 恢复意图记录（规范 §12 第 6 步）。存在即说明上次恢复没有走完。 */
data class RecoveryRecord(
    val state: String,
    val fromEpoch: String,
    val toEpoch: String,
    val at: Long,
) {
    companion object {
        const val PREPARED = "PREPARED"
    }
}

/**
 * 活动存储指针的读写（规范 §12）。
 *
 * 为什么需要它：恢复的原子点不能是"把文件一个个拷回去"——
 * 中途断电就会留下半新半旧的库与图片。这里把原子点收敛成**一次写入 active.json**：
 * 新库在别处完整建好并逐项验证通过之后，才让指针指过去。
 *
 * 用 [AtomicFile] 写（写临时文件 + rename），并用进程内锁串行化；
 * AtomicFile 本身**不提供并发互斥**（规范已指出），所以两者都要有。
 *
 * 注意：全部方法都是同步的小文件 IO，必须在 IO 线程调用。
 */
class StoreControl(private val context: Context) {

    private companion object {
        /**
         * **进程级**锁，而不是实例级。
         *
         * 这是一个真实故障的根因：原先每个 StoreControl 实例各持一把锁，
         * 而 NoteRepository 在 3 处被构造（Application、ReminderReceiver ×2），
         * 每处都新建一个 StoreControl。两个实例同时看到"没有指针"，
         * 各自生成一个 epoch 并各写一次 active.json —— 结果磁盘上出现**两个库**，
         * 写进 A 的数据在 B 里看不见。
         *
         * 锁必须是进程级的，指针的读取与生成才真正互斥。
         */
        private val LOCK = Any()
    }

    private val lock = LOCK

    val controlDir: File get() = File(context.filesDir, "store-control")
    private val activeFile get() = File(controlDir, "active.json")
    private val recoveryFile get() = File(controlDir, "recovery.json")
    private val readyFile get() = File(controlDir, "ready.json")

    /** 旧版遗留的库名：首次运行且没有指针时优先接管它，绝不能凭空建一个空库。 */
    private fun legacyDbName(): String = "purenote.db"

    fun legacyDatabaseFile(): File = context.getDatabasePath(legacyDbName())

    /**
     * 读取活动指针；不存在时**接管既有库**并落盘一份指针。
     *
     * 接管这一步是必需的：老用户的数据库文件已经存在，
     * 如果这里直接生成新 epoch 的名字，App 会打开一个空库——表现为"升级后笔记全没了"。
     */
    /** [justInitialized] 为 true 表示这次才生成指针（首次运行），此时允许创建库文件。 */
    data class ActiveStore(val pointer: StorePointer, val justInitialized: Boolean)

    fun active(): ActiveStore = synchronized(lock) {
        val existing = readPointer()
        if (existing != null) {
            ActiveStore(existing, justInitialized = false)
        } else {
            val adopted = adoptExisting()
            writePointer(adopted)
            ActiveStore(adopted, justInitialized = true)
        }
    }

    fun activePointer(): StorePointer = active().pointer

    fun publish(pointer: StorePointer) = synchronized(lock) {
        writePointer(pointer)
        readyFile.delete()   // 换了一代存储：新库要重新走一次"成功打开"才被认可
    }

    /** 记录"恢复已准备"，用于识别上次恢复是否走完（规范 §12 第 6 步）。 */
    fun markRecoveryPrepared(fromEpoch: String, toEpoch: String) = synchronized(lock) {
        writeJson(
            recoveryFile,
            JSONObject().apply {
                put("state", RecoveryRecord.PREPARED)
                put("fromEpoch", fromEpoch)
                put("toEpoch", toEpoch)
                put("at", System.currentTimeMillis())
            },
        )
    }

    fun readRecovery(): RecoveryRecord? = synchronized(lock) {
        readJson(recoveryFile)?.let {
            RecoveryRecord(
                state = it.optString("state"),
                fromEpoch = it.optString("fromEpoch"),
                toEpoch = it.optString("toEpoch"),
                at = it.optLong("at"),
            )
        }
    }

    fun clearRecovery() = synchronized(lock) {
        recoveryFile.delete()
    }

    /**
     * 标记某个代次的数据库**确实被成功打开过**。
     *
     * 为什么需要它：指针是在"解析出活动存储"时落盘的，而库文件要到
     * DatabaseProvider.require() 真正打开时才创建。两者之间被强杀（崩溃注入里很容易命中），
     * 下次启动就会看到"指针存在、库文件不存在"，被误判成"库丢了"而进入恢复状态——
     * 一个全新安装、从没有过数据的应用被自己的保护机制挡在门外。
     *
     * 有了这个标记就能区分：
     *   指针在 + 无标记 + 库不存在 -> 从未成功建立过，照常创建；
     *   指针在 + 有标记 + 库不存在 -> 确实丢过，进入恢复，绝不静默重建空库。
     */
    fun markReady(epoch: String) = synchronized(lock) {
        writeJson(readyFile, JSONObject().put("epoch", epoch))
    }

    fun isReady(epoch: String): Boolean = synchronized(lock) {
        readJson(readyFile)?.optString("epoch") == epoch
    }

    fun newEpoch(): String = UUID.randomUUID().toString().replace("-", "")

    fun dbNameFor(epoch: String): String = "purenote-$epoch.db"

    private fun readPointer(): StorePointer? = readJson(activeFile)?.let { json ->
        val epoch = json.optString("epoch")
        val dbName = json.optString("dbName")
        if (epoch.isBlank() || dbName.isBlank()) null
        else StorePointer(
            epoch = epoch,
            dbName = dbName,
            libraryId = json.optString("libraryId"),
            pathFormatVersion = json.optInt("pathFormatVersion", StorePointer.CURRENT_PATH_FORMAT),
        )
    }

    private fun adoptExisting(): StorePointer {
        val legacy = legacyDatabaseFile()
        return if (legacy.exists() && legacy.length() > 0) {
            // 接管既有库：沿用原来的文件名，epoch 只作标识
            StorePointer(epoch = "legacy", dbName = legacyDbName())
        } else {
            val epoch = newEpoch()
            StorePointer(epoch = epoch, dbName = dbNameFor(epoch))
        }
    }

    private fun writePointer(pointer: StorePointer) = writeJson(
        activeFile,
        JSONObject().apply {
            put("epoch", pointer.epoch)
            put("dbName", pointer.dbName)
            put("libraryId", pointer.libraryId)
            put("pathFormatVersion", pointer.pathFormatVersion)
            put("updatedAt", System.currentTimeMillis())
        },
    )

    private fun readJson(file: File): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
    }
}
