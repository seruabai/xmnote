package com.purenote.local.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.BackupState
import com.purenote.local.NoteTextSize
import com.purenote.local.NoteViewModel
import com.purenote.local.backup.BackupFile
import com.purenote.local.backup.BackupIo
import com.purenote.local.core.DateFormats
import com.purenote.local.data.SortOrder
import com.purenote.local.notify.QuickCaptureService

private enum class SettingChoice { TEXT_SIZE, SORT, LAYOUT }

/** 与参考图二一致的笔记设置页。 */
@Composable
fun SettingsScreen(vm: NoteViewModel) {
    val textSize by vm.noteTextSize.collectAsState()
    val sort by vm.sortOrder.collectAsState()
    val grid by vm.gridMode.collectAsState()
    val strongReminder by vm.strongReminder.collectAsState()
    val context = LocalContext.current

    var choice by remember { mutableStateOf<SettingChoice?>(null) }
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoText by remember { mutableStateOf("") }
    var quickDialog by remember { mutableStateOf(false) }
    var quickEnabled by remember {
        mutableStateOf(QuickCaptureService.running && Settings.canDrawOverlays(context))
    }
    val backupState by vm.backupState.collectAsState()
    // 预览结果持有 Uri 与解析出的条数，用户确认后才真正导入
    var pendingImport by remember { mutableStateOf<Pair<Uri, BackupFile>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(vm::exportBackup) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { selected ->
            vm.previewBackup(selected) { backup -> pendingImport = selected to backup }
        }
    }

    fun showInfo(title: String, text: String) {
        infoTitle = title
        infoText = text
    }

    BackHandler { vm.goHome() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 25.dp),
            ) {
                IconButton(onClick = vm::goHome) {
                    Icon(Icons.Outlined.ArrowBack, "返回", modifier = Modifier.padding(2.dp))
                }
                Text(
                    "笔记",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(end = 48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ArrowRow("最近删除") { vm.goTrash() }
            }

            Spacer(Modifier.height(21.dp))
            SettingsSectionTitle("笔记样式")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ChoiceRow("文字大小", textSize.label()) { choice = SettingChoice.TEXT_SIZE }
                    ChoiceRow("选择排序方式", if (sort == SortOrder.BY_UPDATED) "按编辑日期" else "按创建日期") {
                        choice = SettingChoice.SORT
                    }
                    ChoiceRow("笔记列表布局", if (grid) "宫格模式" else "列表模式") {
                        choice = SettingChoice.LAYOUT
                    }
                }
            }

            Spacer(Modifier.height(21.dp))
            SettingsSectionTitle("快捷功能")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ArrowRow("速记") { quickDialog = true }
            }

            Spacer(Modifier.height(21.dp))
            SettingsSectionTitle("备份与恢复")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val backupRunning = backupState is BackupState.Running
                Column {
                    ArrowRow("导出备份", enabled = !backupRunning, progress = backupRunning) {
                        exportLauncher.launch(
                            "purenote-backup-${DateFormats.fileStamp(System.currentTimeMillis())}.${BackupIo.EXTENSION}",
                        )
                    }
                    ArrowRow("从备份恢复", enabled = !backupRunning, progress = backupRunning) {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }

                    // 规范 §14：只能显示**最后一次实际成功时间**。
                    // 周期任务是"尽力调度"，系统会因电量、待机、配额推迟执行，
                    // 显示"每 30 分钟备份一次"是在骗用户。
                    val appContext = LocalContext.current.applicationContext
                    val lastAuto = remember(backupState, appContext) {
                        com.purenote.local.feature.backup.LocalBackupStore
                            .forApp(appContext)
                            .readState()
                            .lastSuccessAt
                    }
                    Text(
                        if (lastAuto <= 0L) {
                            "自动备份：尚未执行过（首次成功后会显示时间）"
                        } else {
                            "自动备份：上次成功 " +
                                DateFormats.smartDate(lastAuto) + " " + DateFormats.hourMinute(lastAuto) +
                                "（由系统择机执行，可能推迟）"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            Spacer(Modifier.height(21.dp))
            SettingsSectionTitle("云同步")
            CloudSyncSection(vm)

            Spacer(Modifier.height(21.dp))
            SettingsSectionTitle("提醒")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = 14.dp, top = 15.dp, bottom = 15.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("强提醒", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "持续响铃且静音和勿扰状态下仍有效",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Switch(checked = strongReminder, onCheckedChange = vm::setStrongReminder)
                }
            }

            Spacer(Modifier.height(21.dp))
            SettingsSectionTitle("其他")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ArrowRow("隐私政策") {
                        showInfo(
                            "隐私政策",
                            "纯记的数据以本机为准：笔记、待办、图片与提醒信息都存在你的设备上，离线完整可用。" +
                                "应用只在你开启并点击「立即同步」时联网，把一份完整备份包直接上传到你自己填写的 WebDAV " +
                                "网盘目录；不经开发者服务器，没有账号体系，不做遥测与统计，也不用数据做广告画像。",
                        )
                    }
                    ArrowRow("用户协议") {
                        showInfo("用户协议", "使用纯记即表示你同意自行保管本地数据。卸载应用或清除数据前，请先完成必要备份。")
                    }
                    ArrowRow("权限说明") {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                    ArrowRow("ICP备案号", "本应用自行搭建的服务器数为 0") {
                        showInfo(
                            "ICP备案号",
                            "纯记没有开发者运营的联网信息服务（云同步是设备直连用户自己的网盘），因此没有网站 ICP 备案号。",
                        )
                    }
                    ArrowRow("生成式人工智能服务备案号", "本应用未接入生成式人工智能服务") {
                        showInfo("生成式人工智能服务备案号", "纯记不会联网调用生成式人工智能服务。")
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    choice?.let { current ->
        when (current) {
            SettingChoice.TEXT_SIZE -> ChoiceDialog(
                title = "文字大小",
                options = NoteTextSize.entries.map { it.label() to (it == textSize) },
                onPick = { index -> vm.setNoteTextSize(NoteTextSize.entries[index]); choice = null },
                onDismiss = { choice = null },
            )
            SettingChoice.SORT -> ChoiceDialog(
                title = "选择排序方式",
                options = listOf(
                    "按编辑日期" to (sort == SortOrder.BY_UPDATED),
                    "按创建日期" to (sort == SortOrder.BY_CREATED),
                ),
                onPick = { index ->
                    vm.setSortOrder(if (index == 0) SortOrder.BY_UPDATED else SortOrder.BY_CREATED)
                    choice = null
                },
                onDismiss = { choice = null },
            )
            SettingChoice.LAYOUT -> ChoiceDialog(
                title = "笔记列表布局",
                options = listOf("宫格模式" to grid, "列表模式" to !grid),
                onPick = { index -> vm.setGridMode(index == 0); choice = null },
                onDismiss = { choice = null },
            )
        }
    }

    if (quickDialog) {
        AlertDialog(
            onDismissRequest = { quickDialog = false },
            title = { Text("速记") },
            text = {
                Column {
                    Text("开启后，可从屏幕右侧边缘拉出笔记与待办侧栏。")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    ) {
                        Text("屏幕边缘速记", modifier = Modifier.weight(1f))
                        Switch(
                            checked = quickEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !Settings.canDrawOverlays(context)) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                    Toast.makeText(context, "授权后请再次开启速记", Toast.LENGTH_SHORT).show()
                                } else if (enabled) {
                                    QuickCaptureService.start(context)
                                    QuickCaptureService.setEnabled(context, true)
                                    quickEnabled = true
                                } else {
                                    QuickCaptureService.stop(context)
                                    QuickCaptureService.setEnabled(context, false)
                                    quickEnabled = false
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { quickDialog = false }) { Text("完成") } },
        )
    }

    pendingImport?.let { (uri, backup) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("从备份恢复") },
            text = {
                Text(
                    "备份包含笔记 ${backup.notes.size} 条、待办 ${backup.todos.size} 条、分类 ${backup.folders.size} 个。\n\n" +
                        "「导入」：合并进当前数据，已有内容按更新时间保留最新，重复导入不产生重复。\n\n" +
                        "「完整恢复」：回到这份备份的状态，切换到新一代存储；" +
                        "当前数据仍完整保留，验证通过后才会切换，中断也不会损坏现有数据。",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingImport = null
                        vm.restoreBackup(uri)
                    }) { Text("完整恢复") }
                    TextButton(onClick = {
                        pendingImport = null
                        vm.importBackup(uri)
                    }) { Text("导入") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }

    when (val state = backupState) {
        is BackupState.Done -> AlertDialog(
            onDismissRequest = vm::dismissBackupResult,
            title = { Text(state.title) },
            text = {
                Column {
                    Text(state.summary)
                    if (state.warnings.isNotEmpty()) {
                        val suffix = if (state.warnings.size > 10) "\n等 ${state.warnings.size} 条" else ""
                        Text(
                            state.warnings.take(10).joinToString("\n") { "· $it" } + suffix,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissBackupResult) { Text("知道了") } },
        )
        is BackupState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissBackupResult,
            title = { Text("操作失败") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = vm::dismissBackupResult) { Text("知道了") } },
        )
        else -> {}
    }

    infoTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { infoTitle = null },
            title = { Text(title) },
            text = { Text(infoText) },
            confirmButton = { TextButton(onClick = { infoTitle = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 15.sp,
        modifier = Modifier.padding(start = 17.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(horizontal = 17.dp),
    ) {
        Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun ArrowRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    progress: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(if (subtitle == null) 64.dp else 72.dp)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 17.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 17.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (progress) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, Boolean>>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(index) }.padding(vertical = 5.dp),
                    ) {
                        RadioButton(selected = option.second, onClick = { onPick(index) })
                        Text(option.first, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun NoteTextSize.label(): String = when (this) {
    NoteTextSize.SMALL -> "小"
    NoteTextSize.DEFAULT -> "默认"
    NoteTextSize.LARGE -> "大"
}
