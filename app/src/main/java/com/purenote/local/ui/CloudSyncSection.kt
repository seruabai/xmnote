package com.purenote.local.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.CloudState
import com.purenote.local.NoteViewModel
import com.purenote.local.core.DateFormats
import com.purenote.local.sync.WebDavConfig
import kotlinx.coroutines.delay

/**
 * 设置页「云同步」栏（H 阶段）。
 *
 * 界面上只有三件事可做：填连接信息、测连接、立即同步。
 * 刻意不做"自动双向同步"开关、不做"实时同步"字样——
 * 这一版只把完整备份包传到用户自己的云盘，说得到就做得到。
 */
@Composable
internal fun CloudSyncSection(vm: NoteViewModel) {
    val config by vm.cloudConfig.collectAsState()
    val state by vm.cloudState.collectAsState()

    var serverUrl by remember { mutableStateOf("") }
    var remoteDir by remember { mutableStateOf(WebDavConfig.DEFAULT_DIR) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadCloudConfig() }
    // 首次载入已有配置后填充输入框；之后用户正在编辑的内容不被覆盖。
    // key 用 config.account：配置里账号一变，说明设置真的更新过，可以再填一次。
    LaunchedEffect(config.account) {
        if (!loadedOnce && config.available && config.account.isNotEmpty()) {
            serverUrl = config.serverUrl
            remoteDir = config.remoteDir.ifBlank { WebDavConfig.DEFAULT_DIR }
            account = config.account
            loadedOnce = true
        }
    }

    val running = state is CloudState.Running

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("云同步（实验）", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "把完整备份包直接上传到你自己网盘的 WebDAV 目录，不经任何中间服务器。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Switch(
                    checked = config.enabled,
                    enabled = !running,
                    onCheckedChange = { vm.setCloudEnabled(it) },
                )
            }

            if (!config.online) {
                Text(
                    "当前没有网络连接：可以先填好信息，联网后再点「测试连接」。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Field("服务器地址", serverUrl, { serverUrl = it }, "如 https://dav.jianguoyun.com/dav/", false, !running)
            Field("远端目录", remoteDir, { remoteDir = it }, WebDavConfig.DEFAULT_DIR, false, !running)
            Field("账号", account, { account = it }, "登录名（坚果云填注册邮箱）", false, !running)
            Field(
                "应用密码",
                password,
                { password = it },
                if (config.hasPassword) "已保存，留空表示不改动" else "第三方应用密码（不是登录密码）",
                true,
                !running,
            )

            Text(
                "保存后会先存在本机 Keystore 里，不写明文；同步时只发给上面这个地址。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextButton(
                    enabled = !running,
                    onClick = { showDialog = true },
                ) { Text("连接设置…") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = !running,
                    onClick = {
                        vm.saveCloudConfig(
                            serverUrl = serverUrl,
                            remoteDir = remoteDir,
                            account = account,
                            password = password,
                            enabled = true,
                            testAfterSave = true,
                        )
                        password = ""
                    },
                ) { Text("测试连接") }
                TextButton(
                    enabled = !running && config.available && config.enabled,
                    onClick = { vm.syncCloudNow() },
                ) { Text("立即同步") }
            }

            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    CircularProgressIndicator(Modifier.height(16.dp).padding(end = 8.dp))
                    Text((state as CloudState.Running).text, fontSize = 13.sp)
                }
            }

            // 规范 §14 的同一条原则：只显示**实际发生过**的事，不承诺没发生过的成功
            Text(
                if (config.lastSuccessAt <= 0L) {
                    "尚未成功同步过（只在点击「立即同步」时上传一次）"
                } else {
                    "上次成功 " + DateFormats.smartDate(config.lastSuccessAt) + " " +
                        DateFormats.hourMinute(config.lastSuccessAt) +
                        if (config.lastBytes > 0) "（" + (config.lastBytes / 1024) + " KB，笔记 " + config.lastNoteCount + " 条）" else ""
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }

    if (showDialog) {
        CloudSetupDialog(vm = vm, onDismiss = { showDialog = false })
    }

    when (val current = state) {
        is CloudState.Done -> ResultDialog(
            title = current.title,
            body = current.summary + if (current.warnings.isEmpty()) "" else "\n\n" + current.warnings.joinToString("\n"),
            onDismiss = vm::dismissCloudResult,
        )
        is CloudState.Failed -> ResultDialog(
            title = "云同步未完成",
            body = current.message,
            onDismiss = vm::dismissCloudResult,
        )
        else -> Unit
    }
}

/** 连接设置弹窗：填一次就够，日常只需「立即同步」。 */
@Composable
private fun CloudSetupDialog(vm: NoteViewModel, onDismiss: () -> Unit) {
    val config by vm.cloudConfig.collectAsState()
    val state by vm.cloudState.collectAsState()
    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var remoteDir by remember { mutableStateOf(config.remoteDir.ifBlank { WebDavConfig.DEFAULT_DIR }) }
    var account by remember { mutableStateOf(config.account) }
    var password by remember { mutableStateOf("") }
    val running = state is CloudState.Running

    // 后台把凭据写进 Keystore 需要一点时间，完成前不允许重复点
    LaunchedEffect(state) {
        if (state is CloudState.Done) onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("连接你的云盘") },
        text = {
            Column {
                Text(
                    "当前版本只支持 WebDAV。坚果云：网页端「账户信息 → 安全选项 → 添加应用密码」，" +
                        "然后用邮箱 + 应用密码连接，服务器地址填 https://dav.jianguoyun.com/dav/",
                    fontSize = 13.sp,
                )
                Field("服务器地址", serverUrl, { serverUrl = it }, "https://…/dav/", false, !running)
                Field("远端目录", remoteDir, { remoteDir = it }, WebDavConfig.DEFAULT_DIR, false, !running)
                Field("账号", account, { account = it }, "注册邮箱", false, !running)
                Field("应用密码", password, { password = it }, "不是登录密码", true, !running)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = {
                    vm.saveCloudConfig(
                        serverUrl = serverUrl,
                        remoteDir = remoteDir,
                        account = account,
                        password = password,
                        enabled = true,
                        testAfterSave = true,
                    )
                    password = ""
                },
            ) { Text("保存并测试连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** 结果弹窗：成功与失败用同一个形状，避免"失败时只有一行 toast"这种漏看。 */
@Composable
private fun ResultDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

