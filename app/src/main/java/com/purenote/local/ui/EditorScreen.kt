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
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
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
import com.purenote.local.core.BlockIds
import com.purenote.local.core.BlockType
import com.purenote.local.core.LegacyBody
import com.purenote.local.core.NoteBody
import com.purenote.local.core.RichDoc
import com.purenote.local.core.attachmentNames
import com.purenote.local.core.embedBlockFor
import com.purenote.local.core.insertEmbedAtBlock
import com.purenote.local.core.plainText
import com.purenote.local.core.setHeading
import com.purenote.local.core.toggleHeadTag
import com.purenote.local.core.toggleIndentHead
import com.purenote.local.core.toggleIndentTail
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import com.purenote.local.feature.mind.MindDoc
import com.purenote.local.feature.mind.charCount
import com.purenote.local.feature.mind.flattenOutline
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
    // 正文的唯一形态就是块文档：样式/插图都改文档本身，不再经过标记文本往返
    var doc by remember { mutableStateOf(RichDoc()) }
    // 脑图笔记的正文是树（kind = MIND 时用它，doc 保持空）
    var mind by remember { mutableStateOf(MindDoc()) }
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
    // 光标所在块（工具栏操作以此为锚点）
    var cursorBlock by remember { mutableStateOf(RichDoc.FIRST_BLOCK_ID) }
    // 插图/落点请求：由正文组件一次性消费
    var cursorRequest by remember { mutableStateOf<BlockCursor?>(null) }
    // 清单笔记新增条目后要聚焦过去（-1 = 无）
    val newItemFocus = remember { mutableStateOf(-1) }
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
                doc = note.doc
                note.mind?.let { mind = it }
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
            // 外部分享进来的仍是标记文本（导入路径），入口处转成块文档
            doc = LegacyBody.fromText(screen.prefill.body)
            screen.prefill.imageUris.forEach { raw ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ImageStore.importFromUri(context, android.net.Uri.parse(raw)) }.getOrNull()
                }?.let(imageNames::add)
            }
            if (kind == NoteKind.CHECKLIST && items.isEmpty()) items.add(ChecklistItem(""))
            prefillApplied = true
            if (title.isNotBlank() || doc.plainText().isNotBlank() || imageNames.isNotEmpty()) revision++
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
        // 清单笔记没有正文流（内容由条目派生），插图/录音/手写落到附件条上；
        // 文本笔记才是"按块插进正文"。
        if (kind == NoteKind.CHECKLIST) {
            imageNames.add(fileName)
            markDirty()
            return
        }
        val (next, target) = doc.insertEmbedAtBlock(cursorBlock, embedBlockFor(fileName), BlockIds.newBlockId())
        doc = next
        cursorRequest = BlockCursor(target, 0)
        markDirty()
    }

    fun emptyDraft(): Boolean = when (kind) {
        NoteKind.TEXT -> title.isBlank() && doc.plainText().isBlank() && imageNames.isEmpty()
        NoteKind.CHECKLIST -> title.isBlank() && items.all { it.text.isBlank() } && imageNames.isEmpty()
        // 脑图：只有根节点且根节点没写东西才算空草稿（子节点存在就说明用户建过）
        NoteKind.MIND -> mind.root.label.isBlank() && mind.root.children.isEmpty()
    }

    fun persist() {
        if (!loaded || emptyDraft()) return
        // 图片唯一事实来源是正文里的嵌入块；历史附件条（旧数据/录音）与之取并集
        val imagesUnion = (imageNames + doc.attachmentNames()).distinct()
        // 脑图的标题就是根节点文字（小米笔记同此：脑图没有独立标题）
        val persistTitle = if (kind == NoteKind.MIND) mind.displayTitle else title.trim()
        if (noteId > 0) {
            vm.updateNote(
                noteId = noteId,
                kind = kind,
                title = persistTitle,
                mind = if (kind == NoteKind.MIND) mind else null,
                doc = doc,
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
                title = persistTitle,
                mind = if (kind == NoteKind.MIND) mind else null,
                doc = doc,
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

    // ---- 样式操作：直接改光标所在块的属性（不再改写标记文本，光标也就不必平移）----
    fun applyHeading(level: Int) {
        doc = doc.setHeading(cursorBlock, level)
        markDirty()
    }

    fun applyHeadTag(type: BlockType, ordered: Boolean = false) {
        // 清单笔记里没有"光标所在块"，勾选键的语义是"再加一条勾选栏"
        if (kind == NoteKind.CHECKLIST && type == BlockType.TODO) {
            items.add(ChecklistItem(""))
            newItemFocus.value = items.lastIndex
            markDirty()
            return
        }
        doc = doc.toggleHeadTag(cursorBlock, type, ordered)
        markDirty()
    }

    fun toggleHeadIndentAtCursor() {
        doc = doc.toggleIndentHead(cursorBlock)
        markDirty()
    }

    fun toggleTailIndentAtCursor() {
        doc = doc.toggleIndentTail(cursorBlock)
        markDirty()
    }

    // ---- 手写（第3键）：打开画板，保存为图片插入光标行 ----
    var drawOpen by remember { mutableStateOf(false) }

    // 工具栏随键盘显隐（用户 2026-09-07 要求）。
    //
    // 但"键盘可见"不能只看 WindowInsets.isImeVisible：API < 35 没有强制边到边，
    // adjustResize 直接把窗口压小，IME inset 根本不会送到应用（实测 API 33 上
    // isImeVisible 恒为 false），样式键在 Android 13 这类设备上就永远点不到。
    // 于是并上"正文/标题拿到焦点"这一路信号——它同样意味着用户正在打字。
    var bodyFocused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible || bodyFocused

    val createdLabel = remember(screen.noteId) {
        DateFormats.yearMonthDayHourMinute(System.currentTimeMillis())
    }
    // 字数只数用户写的字：行首标记（# / - [ ] / ![]()）不算，块模型里它们本来就不是文本；
    // 脑图的标题就是根节点，所以只数节点文字，不再另外加 title
    val words = when (kind) {
        NoteKind.MIND -> mind.root.charCount()
        else -> title.length + doc.plainText().length + items.sumOf { it.text.length }
    }
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
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = ::saveAndClose) {
                    Icon(Icons.Outlined.ArrowBack, "返回", modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    shareNoteText(
                        context,
                        title,
                        when (kind) {
                            NoteKind.TEXT -> NoteBody.toMarkup(doc)
                            // 脑图分享成缩进大纲：导图本身是画布，贴到别处只能靠文字层级
                            NoteKind.MIND -> mind.root.flattenOutline()
                                .joinToString("\n") { row -> "  ".repeat(row.depth) + row.label }
                            NoteKind.CHECKLIST -> items.joinToString("\n") { "${if (it.done) "☑" else "☐"} ${it.text}" }
                        },
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
                // 清单笔记也要能用这五键（用户 2026-09-17：笔记目录内都能用）；脑图有自己的操作栏
                visible = (imeVisible || recording || imageMenu) && kind != NoteKind.MIND,
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
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                item("h1") { StyleKey("H1") { applyHeading(1) } }
                                item("h2") { StyleKey("H2") { applyHeading(2) } }
                                item("h3") { StyleKey("H3") { applyHeading(3) } }
                                item("bullet") { StyleKey("•") { applyHeadTag(BlockType.ITEM) } }
                                item("number") { StyleKey("1.") { applyHeadTag(BlockType.ITEM, ordered = true) } }
                                item("quote") { StyleKey("❝") { applyHeadTag(BlockType.QUOTE) } }
                                item("head_indent") { StyleKey("首缩") { toggleHeadIndentAtCursor() } }
                                item("tail_indent") { StyleKey("尾缩") { toggleTailIndentAtCursor() } }
                            }
                            EditorTool(Icons.Outlined.Close, "收起样式") { styleOpen = false }
                            Spacer(Modifier.width(12.dp))
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(60.dp),
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
                            EditorTool(Icons.Outlined.CheckBox, "勾选") { applyHeadTag(BlockType.TODO) }
                            // 第5键：样式面板（H1-3/列表/引用/缩进）
                            EditorTool(Icons.Outlined.Title, "样式") { styleOpen = true }
                        }
                    }
                }
            }
        }
    },
    ) { padding ->
        // 规范 §8：必须如实区分"还没存"与"存好了"。
        // 但它是**悬浮**的：早先放在正文流里，出现/消失会把整篇内容顶一下
        // （用户 2026-09-17 反馈"点一下勾选框整个内容跟着动一下"）。
        val statusText = when (editorState.saveStatus) {
            SaveStatus.SAVING -> "保存中…"
            SaveStatus.PENDING -> "待保存"
            SaveStatus.FAILED -> editorState.failure ?: "保存失败"
            SaveStatus.CONFLICT -> editorState.failure ?: "已在别处被修改"
            SaveStatus.IDLE -> null
        }
        Box(Modifier.padding(padding).fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
            if (kind == NoteKind.MIND) {
                // 脑图没有独立标题：根节点就是标题，改它请点画布上的根节点
                Text(
                    text = mind.displayTitle,
                    style = titleTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
            } else {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .onFocusChanged { bodyFocused = it.isFocused },
                )
            }

            Text(
                "$createdLabel  |  ${words}字",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )

            Spacer(Modifier.height(16.dp))

            if (imageNames.isNotEmpty()) {
                // 附件条只展示正文流之外的遗留附件；正文 [img:] 行已在文中显示缩略图，录音显示录音条
                val stripNames = imageNames.filterNot { it.startsWith("aud_") || it in doc.attachmentNames() }
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
                // 正文只有一个形态：块文档进、块文档出。工具栏与插图都直接改它
                NoteKind.TEXT -> NoteBlockBody(
                    doc = doc,
                    textSize = preferredTextSize,
                    onFocusChange = { bodyFocused = it },
                    onCursor = { cursorBlock = it.blockId },
                    onDocChange = { doc = it; markDirty() },
                    cursorRequest = cursorRequest,
                    onCursorConsumed = { cursorRequest = null },
                    onImageTap = { previewImage = it },
                    modifier = Modifier.weight(1f),
                )
                NoteKind.CHECKLIST -> ChecklistEditor(
                    items = items,
                    textSize = preferredTextSize,
                    onChangeList = ::markDirty,
                    focusIndex = newItemFocus.value,
                    onFocusConsumed = { newItemFocus.value = -1 },
                    onFocusChange = { bodyFocused = it },
                    modifier = Modifier.weight(1f),
                )
                NoteKind.MIND -> MindEditor(
                    mind = mind,
                    textSize = preferredTextSize,
                    onMindChange = { mind = it; markDirty() },
                    modifier = Modifier.weight(1f),
                )
            }
        }

            if (statusText != null) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = if (editorState.saveStatus == SaveStatus.FAILED ||
                        editorState.saveStatus == SaveStatus.CONFLICT
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 12.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .85f),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
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
    focusIndex: Int = -1,
    onFocusConsumed: () -> Unit = {},
    /** 条目获得焦点时上报：工具栏在 API<35 上靠这个信号判断"键盘来了" */
    onFocusChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val typeScale = textSize.typeScale()
    val density = LocalDensity.current
    // 新增/回车拆出来的条目要聚焦过去（外部"勾选"键与回车共用一条通路）
    var pendingFocus by remember { mutableStateOf(-1) }
    LaunchedEffect(focusIndex) {
        if (focusIndex >= 0) {
            pendingFocus = focusIndex
            onFocusConsumed()
        }
    }
    // 条目之间不加额外间距：段内换行的第二行与回车新建的下一行必须落在同一条节奏上
    //（用户 2026-09-17：三行长文本的行距和回车的行距不一样）
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        itemsIndexed(items) { index, item ->
            val requester = remember(index) { FocusRequester() }
            LaunchedEffect(pendingFocus, items.size) {
                if (pendingFocus == index) {
                    requester.requestFocus()
                    pendingFocus = -1
                }
            }
            // 顶对齐：多行条目里勾选框必须跟**第一行**对齐，不能跟着整段居中
            //（否则一条长文字看着像另起了一段）。行本身不留上下留白，行高 = 行距。
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 0.dp)) {
                val checkSize = 21.dp
                Box(
                    modifier = Modifier.offset {
                        // 勾选框中心 = 第一行行盒中心，再补 7% 光学下移（与 MiCheckbox 同一套修正）
                        val linePx = with(density) { typeScale.checklistLineHeightSp.dp.toPx() }
                        val boxPx = with(density) { checkSize.toPx() }
                        IntOffset(0, (linePx / 2f - boxPx / 2f + boxPx * 0.07f).roundToInt())
                    },
                ) {
                    MiCheckbox(done = item.done, size = checkSize, onClick = {
                        items[index] = item.copy(done = !item.done)
                        onChangeList()
                    })
                }
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
                    onValueChange = { new ->
                        // 回车 = 开新的一条勾选栏（新条目未勾选、光标跟过去）。
                        // 早先是把回车当成条目内部换行，界面上就成了"一个勾选框后面两行"——
                        // 用户 2026-09-17 明确要求改成前者。
                        val nl = new.indexOf('\n')
                        when {
                            nl >= 0 -> {
                                val head = new.substring(0, nl)
                                val tail = new.substring(nl + 1)
                                items[index] = item.copy(text = head)
                                items.add(index + 1, ChecklistItem(tail))
                                pendingFocus = index + 1
                                onChangeList()
                            }
                            else -> {
                                items[index] = item.copy(text = new)
                                onChangeList()
                            }
                        }
                    },
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
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                        .focusRequester(requester)
                        .onFocusChanged { onFocusChange(it.isFocused) },
                )
                // 用 24dp 的自绘点按区代替 IconButton：IconButton 自带 48dp 最小触控尺寸，
                // 会把每一条清单行顶成 48dp 高，条目间距就再也压不回"一行高"的节奏。
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { items.removeAt(index); onChangeList() },
                    contentAlignment = Alignment.Center,
                ) {
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
                }.padding(vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text("添加条目", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
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
