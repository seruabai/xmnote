package com.purenote.local.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.purenote.local.NoteTextSize
import com.purenote.local.NoteViewModel
import com.purenote.local.Screen
import com.purenote.local.core.AudioRecorder
import com.purenote.local.core.DateFormats
import com.purenote.local.core.ImageStore
import com.purenote.local.core.NoteMarkup
import com.purenote.local.core.insertEmbedMarkup
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import com.purenote.local.feature.notes.SaveStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
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
    // 样式面板（H1-3/列表/引用/缩进）与图片预览
    var styleOpen by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<String?>(null) }

    // 选图/手写前确定性收起键盘：焦点交还 + IME 隐藏，避免选择器返回后键盘来回跳
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    fun hideIme() {
        focusManager.clearFocus()
        var c: android.content.Context? = view.context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) {
                androidx.core.view.WindowCompat.getInsetsController(c.window, c.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                break
            }
            c = c.baseContext
        }
    }

    if (screen.noteId > 0 && !loaded) {
        // 建立编辑会话基线：读取当前修订号作为下一次 CAS 的 expectedRevision
        vm.beginEditorSession(screen.noteId)
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

    fun markDirty() {
        revision++
        // 规范 §8：每次输入推进编辑代次，保存回执只能标记它自己那一代
        vm.markEditorEdited()
    }

    /**
     * 把图片/录音作为**块**插入光标所在块之后（光标在空行时就地占位），并把光标移到插入后可输入的位置。
     *
     * 取代过去直接拼 "![](file)" 文本行的做法：插入位置按块计算，必要时补出落点。
     * 这样图片才真正是"一个块"，块级操作（拖拽排序、整块删除）才有意义。
     */
    fun insertEmbedAsBlock(fileName: String) {
        val (newBody, caret) = insertEmbedMarkup(body, bodyCursor, fileName)
        body = newBody
        bodyCursorRequest = caret
        markDirty()
    }

    fun emptyDraft(): Boolean = when (kind) {
        NoteKind.TEXT -> title.isBlank() && body.isBlank() && imageNames.isEmpty()
        NoteKind.CHECKLIST -> title.isBlank() && items.all { it.text.isBlank() } && imageNames.isEmpty()
    }

    fun persist() {
        if (!loaded || emptyDraft()) return
        // 图片唯一事实来源是正文 [img:] 行；历史附件条（旧数据/录音）与之取并集
        val imagesUnion = (imageNames + NoteMarkup.imageNames(body)).distinct()
        if (noteId > 0) {
            vm.updateNote(
                noteId = noteId,
                kind = kind,
                title = title.trim(),
                body = body,
                items = items.filter { it.text.isNotBlank() },
                images = imagesUnion,
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
                images = imagesUnion,
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

    val exitScope = rememberCoroutineScope()

    fun saveAndClose() {
        persist()
        // 规范 §8 flushAndAwait：先等本次提交真正落库，再退出编辑器。
        // 原实现是 persist() 后立刻 goHome()——界面已经走了，内容还在异步路上。
        exitScope.launch {
            vm.awaitPendingSaves()
            vm.goHome()
        }
    }

    LaunchedEffect(loaded) {
        if (loaded) {
            snapshotFlow { revision }.drop(1).debounce(500).collect { persist() }
        }
    }

    BackHandler { saveAndClose() }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    // 从选择器/相机返回时窗口重新聚焦，系统/焦点恢复会拉起 IME；延迟两次确定性收起，
    // 落在恢复动作之后，保证"返回后键盘不再出现"（用户反馈的输入法来回跳）
    fun settleImeAfterReturn() {
        val h = android.os.Handler(context.mainLooper)
        h.postDelayed({ hideIme() }, 600)
        h.postDelayed({ hideIme() }, 1200)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = pendingCameraFile
        pendingCameraFile = null
        hideIme()
        settleImeAfterReturn()
        if (ok && target != null) {
            // 与相册/手写一致：拍照图作为 [img:] 行插入光标处（正文流内显示缩略图）
            ImageStore.importCaptured(context, target)?.let {
                insertEmbedAsBlock(it)
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 返回时系统会尝试拉起 IME：延迟两次确定性收起（见 settleImeAfterReturn 注释）
        hideIme()
        settleImeAfterReturn()
        if (uri != null) {
            // 导入在 IO 线程（Activity result 回调非协程，用线程池）
            Thread {
                val imported = runCatching { ImageStore.importFromUri(context, uri) }.getOrNull()
                android.os.Handler(context.mainLooper).post {
                    if (imported != null) {
                        insertEmbedAsBlock(imported)
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
    // 离开编辑器时若仍在录音:放弃并清理(此时录音尚未入库,保存没有意义)
    DisposableEffect(Unit) {
        onDispose {
            if (recording) {
                recording = false
                audioRecorder.cancel()
            }
        }
    }
    // RECORD_AUDIO 是运行时权限：未授权时 MediaRecorder.start() 会抛 SecurityException，
    // 之前被 runCatching 吞掉表现为"录音功能消失"。先请求授权再开录。
    fun startRecording() {
        if (audioRecorder.start()) {
            recordMs = 0L
            recording = true
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }
    fun requestStartRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---- 行级样式操作：替换光标所在行并让光标平移到新的内容起点 ----
    fun applyHeading(level: Int) {
        val range = NoteMarkup.lineRangeAt(body, bodyCursor)
        val line = body.substring(range.first, range.last + 1)
        val newLine = NoteMarkup.withHeading(line, level)
        body = NoteMarkup.replaceLine(body, range, newLine)
        bodyCursorRequest = range.first + (newLine.length - NoteMarkup.withoutHeading(newLine).length)
        markDirty()
    }

    fun applyHeadTag(tag: NoteMarkup.HeadTag) {
        val range = NoteMarkup.lineRangeAt(body, bodyCursor)
        val line = body.substring(range.first, range.last + 1)
        // 有序列表接续编号：取上一行的序号 +1
        val prevLine = if (range.first == 0) ""
        else body.substring(0, range.first).let { it.substring(it.lastIndexOf('\n') + 1) }
        val prevInfo = NoteMarkup.tagInfo(prevLine)
        val numberForNew = if (prevInfo.tag == NoteMarkup.HeadTag.NUMBER) prevInfo.number + 1 else 1
        val (newLine, contentOffset) = NoteMarkup.toggleHeadTag(line, tag, numberForNew)
        body = NoteMarkup.replaceLine(body, range, newLine)
        bodyCursorRequest = range.first + contentOffset
        markDirty()
    }

    fun toggleTailIndentAtCursor() {
        val range = NoteMarkup.lineRangeAt(body, bodyCursor)
        val line = body.substring(range.first, range.last + 1)
        val newLine = NoteMarkup.toggleTailIndent(line)
        body = NoteMarkup.replaceLine(body, range, newLine)
        // 光标在行尾（含贴着旧缩进）时随行尾平移，否则原地不动
        bodyCursorRequest = if (bodyCursor > range.last) range.first + newLine.length else bodyCursor
        markDirty()
    }

    // ---- 手写（第3键）：打开画板，保存为图片插入光标行 ----
    var drawOpen by remember { mutableStateOf(false) }

    // 工具栏随键盘显隐：键盘可见才显示（用户 2026-09-07 要求）
    val imeVisible = WindowInsets.isImeVisible

    val createdLabel = remember(screen.noteId) {
        DateFormats.yearMonthDayHourMinute(System.currentTimeMillis())
    }
    val words = title.length + body.length + items.sumOf { it.text.length }
    // 编辑会话状态机（规范 §8）：待保存 / 保存中 / 保存失败 / 冲突
    val editorState by vm.editorState.collectAsState()
    val typeScale = preferredTextSize.typeScale()

    Scaffold(
        // 共享元素另一端:已有笔记(noteId>0)从卡片原地放大而来;新建笔记 key 为空走普通转场
        modifier = Modifier.noteSharedBounds(key = if (screen.noteId > 0) "note-${screen.noteId}" else null),
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
            // 工具栏随输入法显隐；录音中保持可见（否则授权回来找不到停止键）；
            // 图片菜单打开时也要保活——菜单弹窗夺焦点会使 IME 隐藏，bottomBar 一拆菜单就闪生即死
            // 显隐走滑入滑出动画（跟随键盘升降，Motion 令牌）
            AnimatedVisibility(
                visible = (imeVisible || recording || imageMenu) && kind == NoteKind.TEXT,
                enter = slideInVertically(Motion.sheetSpring()) { it } + fadeIn(tween(Motion.FADE)),
                exit = slideOutVertically(tween(Motion.SCREEN_OUT, easing = Motion.EaseIn)) { it } +
                    fadeOut(tween(Motion.SCREEN_OUT)),
            ) {
                Column(Modifier.imePadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .32f))
                    AnimatedContent(
                        targetState = styleOpen,
                        transitionSpec = { fadeIn(tween(Motion.FADE)) togetherWith fadeOut(tween(Motion.FADE)) },
                        label = "stylePanel",
                    ) { panelOpen ->
                        if (panelOpen) {
                        // 样式面板：对标参考图——H1-3 调整字号，•/1. 列表，引用，首/尾行缩进，右侧固定关闭
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(61.dp),
                        ) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                item("h1") { StyleKey("H1") { applyHeading(1) } }
                                item("h2") { StyleKey("H2") { applyHeading(2) } }
                                item("h3") { StyleKey("H3") { applyHeading(3) } }
                                item("bullet") { StyleKey("•") { applyHeadTag(NoteMarkup.HeadTag.BULLET) } }
                                item("number") { StyleKey("1.") { applyHeadTag(NoteMarkup.HeadTag.NUMBER) } }
                                item("quote") { StyleKey("❝") { applyHeadTag(NoteMarkup.HeadTag.QUOTE) } }
                                item("head_indent") { StyleKey("首缩") { applyHeadTag(NoteMarkup.HeadTag.INDENT) } }
                                item("tail_indent") { StyleKey("尾缩") { toggleTailIndentAtCursor() } }
                            }
                            EditorTool(Icons.Outlined.Close, "收起样式") { styleOpen = false }
                            Spacer(Modifier.width(10.dp))
                        }
                    } else {
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
                                        insertEmbedAsBlock(name)
                                    }
                                }
                            } else {
                                EditorTool(Icons.Outlined.GraphicEq, "录音") { requestStartRecording() }
                            }
                            // 第2键：图片（拍照/相册），插入光标所在行。打开选择器前确定性收起键盘
                            Box {
                                EditorTool(Icons.Outlined.Image, "图片") { imageMenu = true }
                                DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                                    DropdownMenuItem(text = { Text("拍照") }, onClick = {
                                        imageMenu = false
                                        hideIme()
                                        val target = ImageStore.newCameraTarget(context)
                                        pendingCameraFile = target
                                        cameraLauncher.launch(
                                            FileProvider.getUriForFile(context, context.packageName + ".files", target),
                                        )
                                    })
                                    DropdownMenuItem(text = { Text("从本地选择图片") }, onClick = {
                                        imageMenu = false
                                        hideIme()
                                        imageLauncher.launch("image/*")
                                    })
                                }
                            }
                            // 第3键：手写画板，保存为图片插入光标行
                            EditorTool(Icons.Outlined.Draw, "手写") {
                                hideIme()
                                drawOpen = true
                            }
                            // 第4键：行内勾选栏（当前行无→☐，有→移除）
                            EditorTool(Icons.Outlined.CheckBox, "勾选") { applyHeadTag(NoteMarkup.HeadTag.CHECKBOX) }
                            // 第5键：样式面板（H1-3/列表/引用/缩进）
                            EditorTool(Icons.Outlined.Title, "样式") { styleOpen = true }
                        }
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
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )

            // 规范 §8：必须如实区分"还没存"与"存好了"。
            // 原实现把 saveExisting 的返回值丢掉，界面无从知道内容有没有落库；
            // 现在由状态机给出，且旧回执只能标记它自己那一代次。
            val statusText = when (editorState.saveStatus) {
                SaveStatus.SAVING -> "保存中…"
                SaveStatus.PENDING -> "待保存"
                SaveStatus.FAILED -> editorState.failure ?: "保存失败"
                SaveStatus.CONFLICT -> editorState.failure ?: "已在别处被修改"
                SaveStatus.IDLE -> null
            }
            if (statusText != null) {
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = if (editorState.saveStatus == SaveStatus.IDLE) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else if (editorState.saveStatus == SaveStatus.PENDING || editorState.saveStatus == SaveStatus.SAVING) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(bottom = 22.dp),
                )
            } else {
                Spacer(Modifier.height(14.dp))
            }

            if (imageNames.isNotEmpty()) {
                // 附件条只展示正文流之外的遗留附件；正文 [img:] 行已在文中显示缩略图，录音显示录音条
                val stripNames = imageNames.filterNot { it.startsWith("aud_") || it in NoteMarkup.imageNames(body) }
                if (stripNames.isNotEmpty()) {
                    ImagesStrip(stripNames) { name ->
                        // 规范 §2/§10：移除图片只改引用，绝不在这里删文件。
                        // 原实现在正文尚未保存成功时就 ImageStore.deleteFile()，
                        // 保存失败即形成"正文还引用着、文件已经没了"的不可恢复状态。
                        // 物理清理移交给独立的受保护清理入口（需同时确认当前版本、
                        // 历史版本、回收站与备份引用），阶段 A 起一律不做。
                        imageNames.remove(name)
                        markDirty()
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            when (kind) {
                // 块渲染版正文：契约与 TextNoteBody 完全一致，出问题可一行换回
                NoteKind.TEXT -> NoteBlockBody(
                    value = body,
                    textSize = preferredTextSize,
                    onCursor = { bodyCursor = it },
                    onChange = { body = it; markDirty() },
                    cursorRequest = bodyCursorRequest,
                    onCursorConsumed = { bodyCursorRequest = null },
                    onImageTap = { previewImage = it },
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
                insertEmbedAsBlock(name)
            },
        )
    }

    // 正文图片点按大图预览（MotionDialogEnter：缩放+淡入升起）
    previewImage?.let { name ->
        Dialog(onDismissRequest = { previewImage = null }) {
            MotionDialogEnter {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { previewImage = null },
                ) {
                    AsyncThumb(name, Modifier.fillMaxWidth().height(420.dp))
                }
            }
        }
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

/** 样式面板键：文字标签（H1/•/1./❝/首缩/尾缩），直观可读 */
@Composable
private fun StyleKey(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    )
}

@Composable
/** 旧实现（单文本框 + 标记变换）：块渲染稳定后删除，保留作为回退参考 */
private fun TextNoteBody(
    value: String,
    textSize: NoteTextSize,
    onCursor: (Int) -> Unit,
    onChange: (String) -> Unit,
    cursorRequest: Int? = null,
    onCursorConsumed: () -> Unit = {},
    onImageTap: (String) -> Unit = {},
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
    // 布局结果：图片行展开后原文与变换文本偏移不再一致，覆盖层用同一份映射换算坐标
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val textTransform = remember(value, typeScale) { transformNoteText(value, typeScale) }
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
    // 光标夹紧：标签前缀（勾选/列表/引用/缩进）字符不可落入——标签是控件不是文本；
    // 图片行是原子块，光标只允许落在其行尾之后（下一行行首），防止把标记拆散成"jpg文字"
    fun clampSelection(t: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue {
        val text = t.text
        fun clampOne(p: Int): Int {
            if (p < 0 || p > text.length) return p.coerceIn(0, text.length)
            var search = 0
            while (search <= text.length) {
                val lineStart = search
                val nl = text.indexOf('\n', search)
                val lineEnd = if (nl == -1) text.length else nl
                val lineText = text.substring(lineStart, lineEnd)
                if (NoteMarkup.isImageLine(lineText)) {
                    if (p >= lineStart && p <= lineEnd) {
                        return if (lineEnd < text.length) lineEnd + 1 else lineEnd
                    }
                } else {
                    // 标题标记与行首标签都是"控件不是文本"，光标一律钳到内容起点。
                    // 曾只护 tagLen，标题的 "# " 不设防 → 光标可停在标记内部，退格/输入都会把标题拆坏。
                    val info = NoteMarkup.tagInfo(lineText)
                    val contentStart = lineStart + info.headLen + info.tagLen
                    if (contentStart > lineStart && p in lineStart until contentStart) return contentStart
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
    // 变换器实例必须按 typeScale 稳定：每帧新建会让 BasicTextField 反复重算变换→无限重组（ANR）
    val visualTransformation = remember(typeScale) { NoteMarkupVisualTransformation(typeScale) }
    BasicTextField(
        value = tfv,
        // 不传 textStyle 会回落到默认样式：输入文字比"开始书写或"占位小且首行位置错位
        textStyle = bodyTextStyle,
        onValueChange = { raw0 ->
            // 先保证图片行是完整整行，再钳制光标：否则光标会停在标记内部
            val normalized = NoteMarkup.terminateTrailingImageLine(raw0.text)
            val raw = if (normalized === raw0.text) raw0 else raw0.copy(text = normalized)
            val new = clampSelection(raw)
            val oldText = tfv.text
            onCursor(new.selection.end)
            // ---- 退格结构化拦截：单字符删除时保护标签与图片行 ----
            if (new.text.length == oldText.length - 1 && new.selection.collapsed) {
                backspaceIntercept(oldText, new.selection.start)?.let { (patched, cursor) ->
                    tfv = new.copy(text = patched, selection = androidx.compose.ui.text.TextRange(cursor))
                    onChange(patched)
                    return@BasicTextField
                }
            }
            // ---- 回车继承：上一行的标签（勾选/列表/引用/缩进）在新行自动续上（插入语义，保留光标后文本） ----
            if (new.text.length == oldText.length + 1) {
                enterInheritIntercept(new.text, new.selection.end)?.let { (patched, cursor) ->
                    tfv = new.copy(text = patched, selection = androidx.compose.ui.text.TextRange(cursor))
                    onChange(patched)
                    return@BasicTextField
                }
                // 图片行被追加字符（"![](x.jpg)c"）→ 把追加内容拆到下一行，保持图片行原子
                NoteMarkup.imageLineAppendIntercept(new.text, new.selection.end)?.let { (patched, cursor) ->
                    tfv = new.copy(text = patched, selection = androidx.compose.ui.text.TextRange(cursor))
                    onChange(patched)
                    return@BasicTextField
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
                // 标记控件层：与 inner() 同 Box 同原点，坐标经 textTransform 映射
                val l = layout
                if (l != null && value.isNotEmpty()) {
                    MarkupOverlay(
                        layout = l,
                        body = value,
                        transform = textTransform,
                        onToggle = ::toggleCheckboxAt,
                        onImageTap = onImageTap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth().verticalScroll(scroll),
    )
}

/**
 * 标记控件层：遍历视觉行——
 * 1) 勾选框：行首 ☐/☑ 处画可点方块，点方块切换勾选，点文字区是正常光标；
 * 2) 图片行：在该行区域显示真实缩略图（点按大图预览）；
 * 3) 录音行：显示录音条占位（播放功能后续版本接入）。
 */
@Composable
private fun MarkupOverlay(
    layout: androidx.compose.ui.text.TextLayoutResult,
    body: String,
    transform: NoteTextTransformResult,
    onToggle: (Int) -> Unit,
    onImageTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 勾选框方块：按原文行遍历，坐标经映射换算到变换文本（图片行展开后两者偏移不同）
    data class BoxZone(val rect: androidx.compose.ui.geometry.Rect, val checked: Boolean, val start: Int)
    data class MediaZone(val top: Int, val height: Int, val name: String)
    val zones = androidx.compose.runtime.remember(body, layout, transform) {
        buildList {
            var search = 0
            while (search <= body.length) {
                val nl = body.indexOf('\n', search)
                val lineEnd = if (nl == -1) body.length else nl
                val lineText = body.substring(search, lineEnd)
                val info = NoteMarkup.tagInfo(lineText)
                if (info.tag == NoteMarkup.HeadTag.CHECKBOX) {
                    // 方块画在两个全宽空格占位符处（跳过零宽化的标题标记）。
                    // body 与 layout 短暂不同步时坐标可能越界：吞掉但必须继续推进 search，
                    // 否则同一行无限重试会卡死主线程
                    val ts = transform.origToTrans[(search + info.headLen).coerceAtMost(body.length)]
                    runCatching { layout.getBoundingBox(ts) }.getOrNull()?.let { box ->
                        val zone = androidx.compose.ui.geometry.Rect(
                            left = box.left,
                            top = box.top,
                            right = box.right + box.width,
                            bottom = box.bottom,
                        )
                        add(
                            BoxZone(
                                zone,
                                lineText.startsWith(NoteMarkup.TASK_DONE, info.headLen),
                                search,
                            ),
                        )
                    }
                }
                if (nl == -1) break
                search = nl + 1
            }
        }
    }
    val mediaZones = androidx.compose.runtime.remember(body, layout, transform) {
        buildList {
            for (b in transform.imageBlocks) {
                val name = body.substring(b.origStart, b.origEnd).removePrefix(NoteMarkup.IMG_PREFIX).removeSuffix(NoteMarkup.IMG_SUFFIX)
                val firstLine = runCatching { layout.getLineForOffset(b.transStart) }.getOrNull() ?: continue
                val lastLine = runCatching { layout.getLineForOffset((b.transEnd - 1).coerceAtLeast(0)) }.getOrNull() ?: continue
                val top = layout.getLineTop(firstLine)
                add(MediaZone(top.roundToInt(), (layout.getLineBottom(lastLine) - top).roundToInt(), name))
            }
        }
    }

    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val check = MaterialTheme.colorScheme.onSurface
    // 勾选进度动画：切换时填充弹性淡入、对勾从方块中心弹出（Motion 微交互）
    data class AnimatedZone(val zone: BoxZone, val progress: androidx.compose.runtime.State<Float>)
    val animatedZones: List<AnimatedZone> = zones.map { z ->
        androidx.compose.runtime.key(z.start) {
            val prog = remember(z.start) {
                androidx.compose.animation.core.Animatable(if (z.checked) 1f else 0f)
            }
            LaunchedEffect(z.checked) {
                prog.animateTo(
                    if (z.checked) 1f else 0f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f),
                )
            }
            AnimatedZone(z, prog.asState())
        }
    }
    Canvas(modifier) {
        for (a in animatedZones) {
            val z = a.zone
            val p = a.progress.value
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
            // 方块背景：已勾深色填充，透明度随勾选进度弹性进入
            if (z.checked && p > 0f) {
                drawRect(
                    color = check.copy(alpha = p),
                    topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                    size = androidx.compose.ui.geometry.Size(r.width, r.height),
                )
            }
            drawRect(
                color = if (z.checked) check else color,
                topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                size = androidx.compose.ui.geometry.Size(r.width, r.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
            )
            if (z.checked && p > 0.01f) {
                // 简单对勾：两条折线，从方块中心随进度弹出
                val cx2 = r.left + r.width * 0.5f
                val cy2 = r.top + r.height * 0.5f
                val s = r.width * 0.25f
                val line = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx2 - s, cy2)
                    lineTo(cx2 - s * 0.35f, cy2 + s * 0.7f)
                    lineTo(cx2 + s * 1.2f, cy2 - s * 0.8f)
                }
                withTransform({
                    scale(p, p, pivot = androidx.compose.ui.geometry.Offset(r.center.x, r.center.y))
                }) {
                    drawPath(
                        line,
                        androidx.compose.ui.graphics.Color(0xFFFAFAFA).copy(alpha = p),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW * 1.1f),
                    )
                }
            }
        }
    }
    val density = LocalDensity.current
    // 图片/录音控件：与文字同层摆放，位置由对应行的行高决定（变换层已把该行撑高）
    mediaZones.forEach { z ->
        val hDp = with(density) { z.height.toDp() }
        if (z.name.startsWith("aud_")) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .55f),
                modifier = Modifier
                    .offset { IntOffset(0, z.top + (z.height * 0.17f).roundToInt()) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = "录音",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("录音", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            AsyncThumb(
                fileName = z.name,
                modifier = Modifier
                    .offset { IntOffset(0, z.top) }
                    .height(hDp)
                    .width(hDp * 1.4f)
                    .clickable { onImageTap(z.name) },
            )
        }
    }
    // 每个方块一个独立小可点块：点文字区完全不影响光标，只有点到方块才切换勾选
    zones.forEach { z ->
        Box(
            Modifier
                .offset { IntOffset(z.rect.left.roundToInt(), z.rect.top.roundToInt()) }
                .size(with(density) { z.rect.width.toDp() }, with(density) { z.rect.height.toDp() })
                .clickable { onToggle(z.start) },
        )
    }
}

/** 图片行展开后的块区间（原/变换坐标），覆盖层用它的行盒放缩略图 */
internal data class ImageBlock(
    val origStart: Int,
    val origEnd: Int,      // 不含行尾换行
    val transStart: Int,
    val transEnd: Int,     // 含追加的空行
)

/** 图片行展开变换结果：annotated + 双向坐标映射，覆盖层与输入框共用同一份推导 */
internal class NoteTextTransformResult(
    val annotated: androidx.compose.ui.text.AnnotatedString,
    val mapping: androidx.compose.ui.text.input.OffsetMapping,
    val origToTrans: IntArray,
    val imageBlocks: List<ImageBlock>,
)

/** 图片行展开出的空行数：块高 ≈ (1+K) × 基础行高，K=3 时约 112dp 高的缩略图 */
private const val IMAGE_BLOCK_EXTRA_LINES = 3

/**
 * 回车继承拦截（纯函数便于测试）：
 * 上一行带标签（勾选/列表/引用/缩进）时，新行自动续上前缀。
 * 修复过的关键语义：前缀是**插入**到新行行首，绝不替换新行已有内容——
 * 光标在行中按回车时，光标后的文本必须原样保留（旧实现用 replaceLine 整行替换，会清空光标后的全部文本）。
 * @param cursorAfter 插入换行后的光标位置
 * @return (新文本, 新光标)；不需要继承时返回 null
 */
internal fun enterInheritIntercept(newText: String, cursorAfter: Int): Pair<String, Int>? {
    val insAt = cursorAfter - 1
    if (insAt < 0 || newText.getOrNull(insAt) != '\n') return null
    val prev = NoteMarkup.lineAt(newText, (insAt - 1).coerceAtLeast(0))
    val inherit = NoteMarkup.inheritTagPrefix(prev) ?: return null
    val r = NoteMarkup.lineRangeAt(newText, cursorAfter)
    val rest = newText.substring(r.first, r.last + 1)          // 新行内容(光标后的本行剩余)
    val tail = newText.substring(r.last + 1)                   // 本行之后的所有内容(同样必须保留!)
    val patched = newText.substring(0, r.first) + inherit + rest + tail
    return patched to (r.first + inherit.length)
}

/**
 * 退格结构化拦截（纯函数便于测试）：
 * - 光标在标签行内容起点删标签末字符 → 整个标签一次移除，光标落到标题标记后
 * - 删到图片行任一字符/其相邻换行 → 整行图片删除，光标落到行起点
 * - 其余情况返回 null，交给输入框默认行为（正常删字符/合并行）
 * @param cursorAfter 删除发生后的光标位置（= 被删字符下标）
 */
internal fun backspaceIntercept(oldText: String, cursorAfter: Int): Pair<String, Int>? {
    val delIdx = cursorAfter
    val delChar = oldText.getOrNull(delIdx) ?: return null
    if (delChar == '\n') {
        // 删的是换行且它终止的行是图片行（会把 [img:] 并进相邻行）→ 改为删除整行图片
        val termRange = NoteMarkup.lineRangeAt(oldText, delIdx)
        val termText = oldText.substring(termRange.first, termRange.last + 1)
        if (NoteMarkup.isImageLine(termText)) {
            val start = if (termRange.first > 0) termRange.first - 1 else termRange.first
            val patched = oldText.substring(0, start) + oldText.substring(termRange.last + 1)
            return patched to start.coerceAtMost(patched.length)
        }
        return null
    }
    val delLineRange = NoteMarkup.lineRangeAt(oldText, delIdx)
    val delLineText = oldText.substring(delLineRange.first, delLineRange.last + 1)
    // 图片行任一字符被删 → 整行删除（连同前导换行），光标落到行起点
    if (NoteMarkup.isImageLine(delLineText)) {
        val start = if (delLineRange.first > 0) delLineRange.first - 1 else delLineRange.first
        val patched = oldText.substring(0, start) + oldText.substring(delLineRange.last + 1)
        return patched to start.coerceAtMost(patched.length)
    }
    // 标签行：光标在内容起点、删的是标签末字符 → 整个标签一次移除（不是先变"小方框"），
    // 光标落到标题标记之后；下一次退格才是正常的合并上一行
    val cursorLineRange = NoteMarkup.lineRangeAt(oldText, (delIdx + 1).coerceAtMost(oldText.length))
    val cursorLineText = oldText.substring(cursorLineRange.first, cursorLineRange.last + 1)
    val info = NoteMarkup.tagInfo(cursorLineText)
    val contentStart = cursorLineRange.first + info.headLen + info.tagLen
    if (delIdx == contentStart - 1) {
        // 标签行（勾选/列表/引用/缩进）：整个标签一次移除，标题标记保留
        if (info.tagLen > 0) {
            val newLine = cursorLineText.substring(0, info.headLen) +
                cursorLineText.substring(info.headLen + info.tagLen)
            val patched = oldText.substring(0, cursorLineRange.first) + newLine +
                oldText.substring(cursorLineRange.last + 1)
            return patched to (cursorLineRange.first + info.headLen)
        }
        // 标题行（无其他标签）：整个 "# " 一次移除。
        // 否则默认退格只删掉标记里的空格 → "# 标题" 变成字面量 "#标题"，标题样式静默丢失。
        if (info.headLen > 0) {
            val newLine = cursorLineText.substring(info.headLen)
            val patched = oldText.substring(0, cursorLineRange.first) + newLine +
                oldText.substring(cursorLineRange.last + 1)
            return patched to cursorLineRange.first
        }
    }
    return null
}

/**
 * 富标记视觉变换：
 * - H1/H2/H3：标题标记字符零宽化（PUA 字符不渲染成豆腐块），整行放大加粗
 * - 勾选行：前缀渲染为两个全宽空格（方块控件画在这里），勾选后整行置灰删除线
 * - 无序列表 "- " 显示为 "• "（等长替换）；引用/缩进按原字符渲染
 * - 图片/录音行：字符透明零宽化，其后追加 3 个真实空行撑出竖向空间放缩略图/录音条，
 *   通过自定义 OffsetMapping 维护原文↔变换坐标（其余文本 1:1）
 */
internal fun transformNoteText(raw: String, typeScale: NoteTypeScale): NoteTextTransformResult {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val origToTrans = IntArray(raw.length + 1)
    val imageBlocks = mutableListOf<ImageBlock>()
    var pos = 0
    // 按行切分只做一次：曾在循环体内反复 raw.split('\n')，复杂度 O(行数²)，
    // 800 行笔记每次按键 40ms+（该函数每按键至少跑两遍：composition + filter）
    val lines = raw.split('\n')
    for ((idx, line) in lines.withIndex()) {
        val level = NoteMarkup.headingLevel(line)
        val imgName = NoteMarkup.imageNameOf(line)
        val info = NoteMarkup.tagInfo(line)
        val blockStart = builder.length
        when {
            imgName != null -> {
                // 字符透明零宽化（长度 1:1），空行在行尾追加
                builder.pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        fontSize = 12.sp,
                    ),
                )
                builder.append("\u2060".repeat(line.length))
                builder.pop()
            }
            else -> {
                val stripped = NoteMarkup.withoutHeading(line)
                val boxChecked = info.tag == NoteMarkup.HeadTag.CHECKBOX &&
                    stripped.startsWith(NoteMarkup.TASK_DONE)
                val hasBox = info.tag == NoteMarkup.HeadTag.CHECKBOX
                val checkedStyle = androidx.compose.ui.text.SpanStyle(
                    color = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
                    textDecoration = TextDecoration.LineThrough,
                )
                val lineStyle = if (level > 0) {
                    androidx.compose.ui.text.SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (level) {
                            1 -> 24.sp
                            2 -> 20.sp
                            else -> 17.sp
                        },
                    )
                } else {
                    androidx.compose.ui.text.SpanStyle()
                }
                val headLen = info.headLen
                // Markdown 标题标记字符零宽化（"# " 等不参与视觉显示）
                if (headLen > 0) {
                    builder.pushStyle(if (boxChecked) checkedStyle else lineStyle)
                    builder.append("\u2060".repeat(headLen))
                    builder.pop()
                }
                when {
                    hasBox -> {
                        // 任务前缀 "- [ ] "(6字符) → 等长占位:6 个四分之四em窄空格(总宽≈1.5em≈27dp),
                        // 勾选方块(≈16dp)后紧跟小间距即正文——缩进克制(用户反馈 72dp 太宽)
                        val placeholder = "\u2005".repeat(info.tagLen)
                        builder.pushStyle(if (boxChecked) checkedStyle else androidx.compose.ui.text.SpanStyle())
                        builder.append(placeholder)
                        builder.pop()
                        // 标题行叠加任务时,内容必须保留标题字号/字重(否则加勾选后整行掉回正文字号)
                        builder.pushStyle(
                            when {
                                boxChecked -> checkedStyle
                                level > 0 -> lineStyle
                                else -> androidx.compose.ui.text.SpanStyle()
                            },
                        )
                        builder.append(stripped.substring(info.tagLen))
                        builder.pop()
                    }
                    info.tagLen > 0 -> {
                        builder.pushStyle(lineStyle)
                        // 无序列表符号等长显示为 •；有序/引用/缩进按原字符
                        builder.append(
                            if (info.tag == NoteMarkup.HeadTag.BULLET) "• "
                            else stripped.substring(0, info.tagLen),
                        )
                        builder.append(stripped.substring(info.tagLen))
                        builder.pop()
                    }
                    else -> {
                        builder.pushStyle(lineStyle)
                        builder.append(stripped)
                        builder.pop()
                    }
                }
            }
        }
        // 行内偏移 1:1 映射
        for (o in pos..pos + line.length) origToTrans[o] = blockStart + (o - pos)
        if (imgName != null) {
            repeat(IMAGE_BLOCK_EXTRA_LINES) { builder.append('\n') }
            imageBlocks += ImageBlock(pos, pos + line.length, blockStart, builder.length)
        }
        if (idx < lines.lastIndex) builder.append('\n')
        // 图片行行尾（原 '\n'）在追加了空行之后：取最后一个换行后的位置
        if (imgName != null) origToTrans[pos + line.length] = builder.length - 1
        pos += line.length + 1
    }
    origToTrans[raw.length] = builder.length

    val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            origToTrans[offset.coerceIn(0, raw.length)]

        override fun transformedToOriginal(offset: Int): Int {
            val o = offset.coerceIn(0, builder.length)
            // 落在图片块展开区（含边界）→ 钳到图片行行尾，光标进不了标记内部
            for (b in imageBlocks) {
                if (o >= b.transStart && o <= b.transEnd) return b.origEnd
            }
            // 二分：最大的 orig 使 origToTrans[orig] <= o
            var lo = 0
            var hi = raw.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (origToTrans[mid] <= o) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
    return NoteTextTransformResult(builder.toAnnotatedString(), mapping, origToTrans, imageBlocks)
}

internal class NoteMarkupVisualTransformation(private val typeScale: NoteTypeScale) :
    androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString):
        androidx.compose.ui.text.input.TransformedText {
        val r = transformNoteText(text.text, typeScale)
        return androidx.compose.ui.text.input.TransformedText(r.annotated, r.mapping)
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
                // 字段与占位符共用同一份基础样式(纪律:占位符必须 copy 完整样式,行高来源不同会错位)
                val itemTextStyle = TextStyle(
                    fontSize = typeScale.checklistSp.sp,
                    lineHeight = typeScale.checklistLineHeightSp.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                )
                BasicTextField(
                    value = item.text,
                    onValueChange = { items[index] = item.copy(text = it); onChangeList() },
                    textStyle = itemTextStyle.copy(
                        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (item.text.isEmpty()) {
                                Text(
                                    "清单内容",
                                    style = itemTextStyle.copy(color = MaterialTheme.colorScheme.outlineVariant),
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
