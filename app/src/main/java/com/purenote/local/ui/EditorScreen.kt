package com.purenote.local.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.purenote.local.NoteTextSize
import com.purenote.local.NoteViewModel
import com.purenote.local.Screen
import com.purenote.local.core.AudioRecorder
import com.purenote.local.core.DateFormats
import com.purenote.local.core.ImageStore
import com.purenote.local.core.NoteMarkup
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.io.File

/** 小米笔记式编辑器：独立大标题、时间/字数、留白正文和底部五项工具栏。 */
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: NoteViewModel, screen: Screen.Editor) {
    val context = LocalContext.current
    val folders by vm.folders.collectAsState()
    val preferredTextSize by vm.noteTextSize.collectAsState()
    val kind = screen.kind

    var loaded by remember { mutableStateOf(screen.noteId <= 0) }
    var prefillApplied by remember { mutableStateOf(false) }
    var noteId by remember { mutableStateOf(screen.noteId) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<ChecklistItem>() }
    val imageNames = remember { mutableStateListOf<String>() }
    var colorIndex by remember { mutableStateOf(0) }
    var folderId by remember { mutableStateOf<Long?>(screen.folderId) }
    var pinned by remember { mutableStateOf(false) }
    var remindAt by remember { mutableStateOf<Long?>(null) }
    var noteRepeat by remember { mutableStateOf(com.purenote.local.data.RepeatRule.NONE) }
    var noteAllDay by remember { mutableStateOf(false) }
    var remindOpen by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    var moreMenu by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 正文光标位置（selection end），工具栏操作以此为锚定行
    var bodyCursor by remember { mutableStateOf(0) }
    // 工具栏行编辑（前缀增删）后请求把 IME 光标平移到的新位置，由 TextNoteBody 一次性消费
    var bodyCursorRequest by remember { mutableStateOf<Int?>(null) }

    if (screen.noteId > 0 && !loaded) {
        vm.getNoteOnce(screen.noteId) { note ->
            if (note == null) {
                vm.goHome()
            } else {
                title = note.title
                body = note.body
                items.clear()
                items.addAll(note.items)
                imageNames.clear()
                imageNames.addAll(note.images)
                colorIndex = note.colorIndex
                folderId = note.folderId
                pinned = note.pinned
                remindAt = note.remindAt
                noteRepeat = note.repeat
                noteAllDay = note.allDay
            }
            loaded = true
        }
    }

    LaunchedEffect(loaded, prefillApplied) {
        if (loaded && !prefillApplied && screen.noteId <= 0) {
            title = screen.prefill.title
            body = screen.prefill.body
            screen.prefill.imageUris.forEach { raw ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ImageStore.importFromUri(context, android.net.Uri.parse(raw)) }.getOrNull()
                }?.let(imageNames::add)
            }
            if (kind == NoteKind.CHECKLIST && items.isEmpty()) items.add(ChecklistItem(""))
            prefillApplied = true
            if (title.isNotBlank() || body.isNotBlank() || imageNames.isNotEmpty()) revision++
        }
    }

    fun markDirty() { revision++ }

    fun emptyDraft(): Boolean = when (kind) {
        NoteKind.TEXT -> title.isBlank() && body.isBlank() && imageNames.isEmpty()
        NoteKind.CHECKLIST -> title.isBlank() && items.all { it.text.isBlank() } && imageNames.isEmpty()
    }

    fun persist() {
        if (!loaded || emptyDraft()) return
        if (noteId > 0) {
            vm.updateNote(
                noteId = noteId,
                kind = kind,
                title = title.trim(),
                body = body,
                items = items.filter { it.text.isNotBlank() },
                images = imageNames.toList(),
                colorIndex = colorIndex,
                folderId = folderId,
                pinned = pinned,
                remindAt = remindAt,
                repeat = noteRepeat,
                allDay = noteAllDay,
            )
        } else if (!creating) {
            creating = true
            vm.createNote(
                kind = kind,
                title = title.trim(),
                body = body,
                items = items.filter { it.text.isNotBlank() },
                images = imageNames.toList(),
                colorIndex = colorIndex,
                folderId = folderId,
                remindAt = remindAt,
                repeat = noteRepeat,
                allDay = noteAllDay,
            ) { id ->
                noteId = id
                creating = false
            }
        }
    }

    fun saveAndClose() {
        persist()
        vm.goHome()
    }

    LaunchedEffect(loaded) {
        if (loaded) {
            snapshotFlow { revision }.drop(1).debounce(500).collect { persist() }
        }
    }

    BackHandler { saveAndClose() }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = pendingCameraFile
        pendingCameraFile = null
        if (ok && target != null) {
            ImageStore.importCaptured(context, target)?.let {
                imageNames.add(it)
                markDirty()
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // 导入在 IO 线程（Activity result 回调非协程，用线程池）
            Thread {
                val imported = runCatching { ImageStore.importFromUri(context, uri) }.getOrNull()
                android.os.Handler(context.mainLooper).post {
                    if (imported != null) {
                        body = NoteMarkup.insertImageLineAtCursor(body, bodyCursor, imported)
                        markDirty()
                    } else {
                        android.widget.Toast.makeText(context, "图片导入失败，请换一张试试", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    // ---- 录音（第1键）：MediaRecorder 状态机，录完作为音频条目插入光标行 ----
    val audioRecorder = remember { AudioRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        while (recording) {
            recordMs = audioRecorder.elapsedMs
            kotlinx.coroutines.delay(200)
        }
    }

    // ---- 手写（第3键）：打开画板，保存为图片插入光标行 ----
    var drawOpen by remember { mutableStateOf(false) }

    // 工具栏随键盘显隐：键盘可见才显示（用户 2026-09-07 要求）
    val imeVisible = WindowInsets.isImeVisible

    val createdLabel = remember(screen.noteId) {
        DateFormats.yearMonthDayHourMinute(System.currentTimeMillis())
    }
    val words = title.length + body.length + items.sumOf { it.text.length }
    val typeScale = preferredTextSize.typeScale()

    Scaffold(
        containerColor = noteContainerColor(colorIndex),
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                IconButton(onClick = ::saveAndClose) {
                    Icon(Icons.Outlined.ArrowBack, "返回", modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    shareNoteText(
                        context,
                        title,
                        if (kind == NoteKind.TEXT) body
                        else items.joinToString("\n") { "${if (it.done) "☑" else "☐"} ${it.text}" },
                    )
                }) {
                    Icon(Icons.Outlined.Share, "分享", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(27.dp))
                }
                IconButton(onClick = { colorOpen = true }) {
                    Icon(Icons.Outlined.Palette, "更换纸色", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(27.dp))
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(27.dp))
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        // 菜单精简（用户 2026-09-07 要求）：去图标、只留文本、去置顶项、文案「移动到」
                        DropdownMenuItem(
                            text = { Text("设置提醒") },
                            onClick = {
                                moreMenu = false
                                remindOpen = true
                            },
                        )
                        DropdownMenuItem(text = { Text("移动到") }, onClick = { moreMenu = false; moveOpen = true })
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { moreMenu = false; confirmDelete = true },
                        )
                    }
                }
            }
        },
        bottomBar = {
            // 工具栏随输入法显隐：不呼出键盘不显示；imePadding 使其贴键盘顶沿随其升降（用户 2026-09-07 要求）
            if (imeVisible && kind == NoteKind.TEXT) {
                Column(Modifier.imePadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .32f))
                    Row(
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(61.dp),
                    ) {
                        // 第1键：录音。未录开始录（高亮计时），录制中再点结束并作为音频条目插入光标行
                        if (recording) {
                            EditorToolActive(Icons.Outlined.Stop, "停止录音 ${recordMs / 1000}s") {
                                recording = false
                                audioRecorder.stop()?.let { name ->
                                    body = NoteMarkup.insertImageLineAtCursor(body, bodyCursor, name)
                                    markDirty()
                                }
                            }
                        } else {
                            EditorTool(Icons.Outlined.GraphicEq, "录音") {
                                if (audioRecorder.start()) {
                                    recordMs = 0L
                                    recording = true
                                }
                            }
                        }
                        // 第2键：图片（拍照/相册），插入光标所在行
                        Box {
                            EditorTool(Icons.Outlined.Image, "图片") { imageMenu = true }
                            DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                                DropdownMenuItem(text = { Text("拍照") }, onClick = {
                                    imageMenu = false
                                    val target = ImageStore.newCameraTarget(context)
                                    pendingCameraFile = target
                                    cameraLauncher.launch(
                                        FileProvider.getUriForFile(context, context.packageName + ".files", target),
                                    )
                                })
                                DropdownMenuItem(text = { Text("从本地选择图片") }, onClick = {
                                    imageMenu = false
                                    imageLauncher.launch("image/*")
                                })
                            }
                        }
                        // 第3键：手写画板，保存为图片插入光标行
                        EditorTool(Icons.Outlined.Draw, "手写") { drawOpen = true }
                        // 第4键：行内勾选栏。当前行无勾选→加 ☐；有→移除（用户 2026-09-07 要求）
                        EditorTool(Icons.Outlined.CheckBox, "勾选") {
                            val range = NoteMarkup.lineRangeAt(body, bodyCursor)
                            val line = body.substring(range.first, range.last + 1)
                            body = NoteMarkup.replaceLine(body, range, NoteMarkup.toggleCheckboxLine(line))
                            // 前缀增删发生在光标之前：光标必须随文本一起平移到内容起点，否则文字会把方块顶走
                            val headLen = line.length - NoteMarkup.withoutHeading(line).length
                            val adding = !NoteMarkup.hasCheckbox(line)
                            bodyCursorRequest = range.first + headLen + if (adding) NoteMarkup.BOX_UNCHECKED_PREFIX.length else 0
                            markDirty()
                        }
                        // 第5键：标题级别 H1/H2/H3 轮换（正文→H1→H2→H3→正文）
                        EditorTool(Icons.Outlined.Title, "标题") {
                            val range = NoteMarkup.lineRangeAt(body, bodyCursor)
                            val line = body.substring(range.first, range.last + 1)
                            val next = when (NoteMarkup.headingLevel(line)) {
                                0 -> 1
                                1 -> 2
                                2 -> 3
                                else -> 0
                            }
                            val newLine = NoteMarkup.withHeading(line, next)
                            body = NoteMarkup.replaceLine(body, range, newLine)
                            // 标题标记在光标之前增删：光标随文本平移到内容起点
                            bodyCursorRequest = range.first + (newLine.length - NoteMarkup.withoutHeading(newLine).length)
                            markDirty()
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 22.dp),
        ) {
            val titleTextStyle = TextStyle(
                fontSize = typeScale.editorTitleSp.sp,
                lineHeight = typeScale.editorTitleLineHeightSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            )
            BasicTextField(
                value = title,
                onValueChange = { title = it.replace("\n", " "); markDirty() },
                singleLine = true,
                textStyle = titleTextStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = (typeScale.editorTitleLineHeightSp + 4f).dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (title.isEmpty()) {
                            Text(
                                "标题",
                                style = titleTextStyle.copy(color = MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 19.dp),
            )

            Text(
                "$createdLabel  |  ${words}字",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 22.dp),
            )

            if (imageNames.isNotEmpty()) {
                ImagesStrip(imageNames.toList()) { name ->
                    imageNames.remove(name)
                    ImageStore.deleteFile(context, name)
                    markDirty()
                }
                Spacer(Modifier.height(12.dp))
            }

            when (kind) {
                NoteKind.TEXT -> TextNoteBody(
                    value = body,
                    textSize = preferredTextSize,
                    onCursor = { bodyCursor = it },
                    onChange = { body = it; markDirty() },
                    cursorRequest = bodyCursorRequest,
                    onCursorConsumed = { bodyCursorRequest = null },
                    modifier = Modifier.weight(1f),
                )
                NoteKind.CHECKLIST -> ChecklistEditor(
                    items = items,
                    textSize = preferredTextSize,
                    onChangeList = ::markDirty,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条笔记？") },
            text = { Text("笔记会移入废纸篓。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (noteId > 0) vm.trash(noteId) else vm.goHome()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    if (moveOpen) {
        MoveFolderDialog(
            current = folderId,
            folders = folders,
            onDismiss = { moveOpen = false },
            onPick = { folderId = it; moveOpen = false; markDirty() },
        )
    }

    if (colorOpen) {
        ColorPickDialog(
            current = colorIndex,
            onDismiss = { colorOpen = false },
            onPick = { colorIndex = it; colorOpen = false; markDirty() },
        )
    }

    // 自绘提醒选择器（compact：只留日期/时间），确认后随 persist 透传 repeat/allDay
    if (remindOpen) {
        RemindPickerDialog(
            initialDue = remindAt,
            initialAllDay = noteAllDay,
            initialRepeat = noteRepeat,
            compact = true,
            onApply = { at, allDay, repeat ->
                remindAt = at
                noteAllDay = allDay
                noteRepeat = repeat
                remindOpen = false
                markDirty()
            },
            onClear = {
                remindAt = null
                noteAllDay = false
                noteRepeat = com.purenote.local.data.RepeatRule.NONE
                remindOpen = false
                markDirty()
            },
            onDismiss = { remindOpen = false },
        )
    }

    // 手写画板（第3键）：保存为图片插入光标行
    if (drawOpen) {
        DrawBoardDialog(
            onDismiss = { drawOpen = false },
            onSave = { name ->
                drawOpen = false
                body = NoteMarkup.insertImageLineAtCursor(body, bodyCursor, name)
                markDirty()
            },
        )
    }
}

@Composable
private fun EditorTool(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp).clickable(onClick = onClick).padding(1.dp),
    )
}

/** 录音中等激活态工具键：主题色高亮 */
@Composable
private fun EditorToolActive(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp).clickable(onClick = onClick).padding(1.dp),
    )
}

@Composable
private fun TextNoteBody(
    value: String,
    textSize: NoteTextSize,
    onCursor: (Int) -> Unit,
    onChange: (String) -> Unit,
    cursorRequest: Int? = null,
    onCursorConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val typeScale = textSize.typeScale()
    val bodyTextStyle = TextStyle(
        fontSize = typeScale.editorBodySp.sp,
        lineHeight = typeScale.editorBodyLineHeightSp.sp,
        color = MaterialTheme.colorScheme.onSurface,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Top,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
    val scroll = rememberScrollState()
    var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    if (tfv.text != value) tfv = tfv.copy(text = value)
    // 布局结果：因 VisualTransformation 用 Identity 映射，勾选框字符的视觉坐标直接对应原文 offset
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // 点击某勾选框行首，切换 ☐↔☑
    fun toggleCheckboxAt(offset: Int) {
        if (offset !in 0..tfv.text.length) return
        val line = NoteMarkup.lineRangeAt(tfv.text, offset)
        val lineText = tfv.text.substring(line.first, line.last + 1)
        if (!NoteMarkup.hasCheckbox(lineText)) return
        val toggled = NoteMarkup.replaceLine(tfv.text, line, NoteMarkup.cycleCheckboxLine(lineText))
        tfv = tfv.copy(text = toggled)
        if (toggled != value) onChange(toggled)
    }
    // 光标夹紧：禁止光标(或选区)落到任意勾选框前缀字符上 —— 勾选框是控件，光标只在文字区内移动
    fun clampSelection(t: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue {
        val text = t.text
        fun clampOne(p: Int): Int {
            if (p < 0 || p > text.length) return p.coerceIn(0, text.length)
            var search = 0
            while (search < text.length) {
                val lineStart = search
                val nl = text.indexOf('\n', search)
                val lineEnd = if (nl == -1) text.length else nl
                val lineText = text.substring(lineStart, lineEnd)
                val stripped = NoteMarkup.withoutHeading(lineText)
                val prefix = when {
                    stripped.startsWith(NoteMarkup.BOX_UNCHECKED_PREFIX) ||
                        stripped.startsWith(NoteMarkup.BOX_CHECKED_PREFIX) -> 2
                    else -> 0
                }
                if (prefix > 0) {
                    val headLen = lineText.length - stripped.length
                    val lo = lineStart + headLen          // 前缀起点(标题标记之后)
                    val hi = lo + prefix                   // 前缀终点 = 内容起点
                    if (p in lo until hi) return hi
                }
                if (nl == -1) break
                search = nl + 1
            }
            return p
        }
        val ns = clampOne(t.selection.start)
        val ne = clampOne(t.selection.end)
        return if (ns == t.selection.start && ne == t.selection.end) t
        else t.copy(selection = androidx.compose.ui.text.TextRange(ns, ne))
    }
    // 工具栏行编辑（前缀增删）后：光标随文本一起平移到请求位置——根治"文字被顶开而光标不动"
    LaunchedEffect(cursorRequest) {
        cursorRequest?.let { target ->
            val t = clampSelection(tfv.copy(selection = androidx.compose.ui.text.TextRange(target, target)))
            tfv = t
            onCursor(t.selection.end)
            onCursorConsumed()
        }
    }
    BasicTextField(
        value = tfv,
        onValueChange = { raw ->
            val new = clampSelection(raw)
            onCursor(new.selection.end)
            // 回车继承：新行的上一行带勾选框时，新行自动补 ☐（用户 2026-09-07 要求）
            if (new.text.length == tfv.text.length + 1) {
                val insAt = new.selection.end - 1
                if (insAt >= 0 && new.text.getOrNull(insAt) == '\n') {
                    val prev = NoteMarkup.lineAt(new.text, (insAt - 1).coerceAtLeast(0))
                    if (NoteMarkup.hasCheckbox(prev)) {
                        val r = NoteMarkup.lineRangeAt(new.text, new.selection.end)
                        val patched = NoteMarkup.replaceLine(new.text, r, NoteMarkup.BOX_UNCHECKED_PREFIX)
                        // 光标落在勾选前缀之后（新行内容起点），而不是文末
                        tfv = new.copy(
                            text = patched,
                            selection = androidx.compose.ui.text.TextRange(r.first + NoteMarkup.BOX_UNCHECKED_PREFIX.length),
                        )
                        onChange(patched)
                        return@BasicTextField
                    }
                }
            }
            tfv = new
            if (new.text != value) onChange(new.text)
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // 只在文本变化时记录布局结果：TextLayoutResult 无相等性，无守卫会每帧写状态→无限重组，IME 输入被持续打断
        onTextLayout = { l ->
            val cur = layout
            if (cur == null || cur.layoutInput.text != l.layoutInput.text) layout = l
        },
        decorationBox = { inner ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "开始书写或",
                            style = bodyTextStyle.copy(color = MaterialTheme.colorScheme.outlineVariant),
                        )
                        Spacer(Modifier.width(9.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .2f)),
                        ) {
                            Text(
                                "☷ 创建思维笔记",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                inner()
                // 勾选框控件层：与 inner() 同 Box 同原点，getBoundingBox 的文本坐标即为覆盖层坐标
                val l = layout
                if (l != null && value.isNotEmpty()) {
                    CheckboxOverlay(
                        layout = l,
                        body = value,
                        onToggle = ::toggleCheckboxAt,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        visualTransformation = NoteMarkupVisualTransformation(typeScale),
        modifier = modifier.fillMaxWidth().verticalScroll(scroll),
    )
}

/**
 * 勾选框控件层：遍历视觉行，在每行行首 ☐/☑ 字符处画一个可点的方块。
 * 方块是可独立点击的目标，点击即切换该行勾选状态；方块区域拦截点击，但放行拖动以不破坏滚动。
 */
@Composable
private fun CheckboxOverlay(
    layout: androidx.compose.ui.text.TextLayoutResult,
    body: String,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 收集所有勾选框方块：visual line 首字符若属于勾选框逻辑行，则其 bounding box 作为方块区
    data class BoxZone(val rect: androidx.compose.ui.geometry.Rect, val checked: Boolean, val start: Int)
    val zones = androidx.compose.runtime.remember(body, layout) {
        buildList {
            // body 与 layout 短暂不同步时（如空布局还没收到新文本），以 layout 实际文本长度为准防越界
            val layoutLen = layout.layoutInput.text.length
            for (line in 0 until layout.lineCount) {
                val start = layout.getLineStart(line)
                if (start >= layoutLen || start > body.length) continue
                val lineText = NoteMarkup.lineAt(body, start)
                val stripped = NoteMarkup.withoutHeading(lineText)
                val checked = stripped.startsWith(NoteMarkup.BOX_CHECKED_PREFIX)
                if (!NoteMarkup.hasCheckbox(lineText)) continue
                // 前缀渲染成两个全宽空格，其 bounding box 就是方块 + 缩进区
                val box = runCatching { layout.getBoundingBox(start) }.getOrNull() ?: continue
                val zone = androidx.compose.ui.geometry.Rect(
                    left = box.left,
                    top = box.top,
                    right = box.right + box.width,
                    bottom = box.bottom,
                )
                add(BoxZone(zone, checked, start))
            }
        }
    }

    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val check = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        for (z in zones) {
            val strokeW = 2.dp.toPx()
            // 画成垂直居中的方角小方块（如 MiCheckbox）
            val boxSize = (z.rect.height * 0.62f)
            val cx = z.rect.left + boxSize * 0.62f
            val cy = z.rect.center.y
            val half = boxSize / 2f
            val r = androidx.compose.ui.geometry.Rect(
                cx - half,
                cy - half,
                cx + half,
                cy + half,
            )
            // 方块背景（未勾透明+描边，已勾深色填充）
            drawRect(
                color = if (z.checked) check else androidx.compose.ui.graphics.Color.Transparent,
                topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                size = androidx.compose.ui.geometry.Size(r.width, r.height),
            )
            drawRect(
                color = if (z.checked) check else color,
                topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                size = androidx.compose.ui.geometry.Size(r.width, r.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
            )
            if (z.checked) {
                // 简单对勾：两条折线
                val cx2 = r.left + r.width * 0.5f
                val cy2 = r.top + r.height * 0.5f
                val s = r.width * 0.25f
                val line = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx2 - s, cy2)
                    lineTo(cx2 - s * 0.35f, cy2 + s * 0.7f)
                    lineTo(cx2 + s * 1.2f, cy2 - s * 0.8f)
                }
                drawPath(line, androidx.compose.ui.graphics.Color(0xFFFAFAFA), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW * 1.1f))
            }
        }
    }
    // 每个方块一个独立小可点块：点文字区完全不影响光标，只有点到方块才切换勾选
    val density = LocalDensity.current
    zones.forEach { z ->
        Box(
            Modifier
                .offset { IntOffset(z.rect.left.roundToInt(), z.rect.top.roundToInt()) }
                .size(with(density) { z.rect.width.toDp() }, with(density) { z.rect.height.toDp() })
                .clickable { onToggle(z.start) },
        )
    }
}

/**
 * 富标记视觉变换（长度不变，Offset 一一对应）：
 * H1/H2/H3 行整行放大加粗（标记字符零宽显示不了，保留原字符但极小化干扰）、
 * 勾选行整行置灰删除线、图片/录音行着色提示。
 */
internal class NoteMarkupVisualTransformation(private val typeScale: NoteTypeScale) :
    androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString):
        androidx.compose.ui.text.input.TransformedText {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        val raw = text.text
        var consumed = 0
        for (line in raw.split('\n')) {
            val level = NoteMarkup.headingLevel(line)
            val imgName = NoteMarkup.imageNameOf(line)
            // 勾选行：行首 ☐/☑ 前缀(剥标题后开头 2 字符)渲染为两个全宽空格——
            // 长度不变(Identity 安全)，但给覆盖层方块留出缩进空间，文字与勾选框保持距离
            val stripped = NoteMarkup.withoutHeading(line)
            val boxChecked = stripped.startsWith(NoteMarkup.BOX_CHECKED_PREFIX)
            val checkPrefix = when {
                boxChecked || stripped.startsWith(NoteMarkup.BOX_UNCHECKED_PREFIX) -> 2
                else -> 0
            }
            val checkedStyle = androidx.compose.ui.text.SpanStyle(
                color = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
                textDecoration = TextDecoration.LineThrough,
            )
            if (checkPrefix > 0) {
                val headLen = line.length - stripped.length
                if (headLen > 0) {
                    if (boxChecked) {
                        builder.pushStyle(checkedStyle)
                        builder.append(line.substring(0, headLen))
                        builder.pop()
                    } else {
                        builder.append(line.substring(0, headLen))
                    }
                }
                // 前缀 2 字符 → 全宽空格占位（方块控件画在这里）
                builder.append("\u3000\u3000")
                if (boxChecked) {
                    builder.pushStyle(checkedStyle)
                    builder.append(line.substring(headLen + 2))
                    builder.pop()
                } else {
                    builder.append(line.substring(headLen + 2))
                }
            } else {
                builder.pushStyle(
                    when {
                        imgName != null -> androidx.compose.ui.text.SpanStyle(
                            color = androidx.compose.ui.graphics.Color(0xFFB8860B),
                            fontSize = 14.sp,
                        )
                        level > 0 -> androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = when (level) {
                                1 -> 24.sp
                                2 -> 20.sp
                                else -> 17.sp
                            },
                        )
                        else -> androidx.compose.ui.text.SpanStyle()
                    },
                )
                builder.append(line)
                builder.pop()
            }
            consumed += line.length
            // Identity 映射要求变换前后长度一致：空文本不能补 \n，否则光标越界崩溃
            if (consumed < raw.length) {
                builder.append('\n')
                consumed++
            }
        }
        return androidx.compose.ui.text.input.TransformedText(
            builder.toAnnotatedString(),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
    }
}

@Composable
private fun ImagesStrip(names: List<String>, onDelete: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(names, key = { it }) { name ->
            Box {
                AsyncThumb(name, Modifier.size(width = 112.dp, height = 112.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(23.dp).clickable { onDelete(name) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, "移除图片", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistEditor(
    items: MutableList<ChecklistItem>,
    textSize: NoteTextSize,
    onChangeList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeScale = textSize.typeScale()
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        itemsIndexed(items) { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                MiCheckbox(done = item.done, size = 21.dp, onClick = {
                    items[index] = item.copy(done = !item.done)
                    onChangeList()
                })
                BasicTextField(
                    value = item.text,
                    onValueChange = { items[index] = item.copy(text = it); onChangeList() },
                    textStyle = TextStyle(
                        fontSize = typeScale.checklistSp.sp,
                        lineHeight = typeScale.checklistLineHeightSp.sp,
                        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (item.text.isEmpty()) {
                                Text(
                                    "清单内容",
                                    style = TextStyle(
                                        fontSize = typeScale.checklistSp.sp,
                                        lineHeight = typeScale.checklistLineHeightSp.sp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    ),
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = { items.removeAt(index); onChangeList() }) {
                    Icon(Icons.Outlined.Close, "移除", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(17.dp))
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    items.add(ChecklistItem(""))
                    onChangeList()
                }.padding(vertical = 11.dp),
            ) {
                Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text("添加条目", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 9.dp))
            }
        }
    }
}

@Composable
private fun ColorPickDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("便签纸色") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(6) { index ->
                    val selected = current.mod(6) == index
                    Surface(
                        shape = CircleShape,
                        color = noteContainerColor(index),
                        border = BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.size(40.dp).clickable { onPick(index) },
                    ) {
                        if (selected) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, "已选择", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun openReminderPicker(context: Context, onPicked: (Long) -> Unit) {
    val now = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            android.app.TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    if (picked.timeInMillis > System.currentTimeMillis()) onPicked(picked.timeInMillis)
                },
                now.get(java.util.Calendar.HOUR_OF_DAY),
                now.get(java.util.Calendar.MINUTE),
                true,
            ).show()
        },
        now.get(java.util.Calendar.YEAR),
        now.get(java.util.Calendar.MONTH),
        now.get(java.util.Calendar.DAY_OF_MONTH),
    ).show()
}
