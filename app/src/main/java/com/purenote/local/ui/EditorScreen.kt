package com.purenote.local.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.purenote.local.NoteViewModel
import com.purenote.local.Screen
import com.purenote.local.core.ImageStore
import com.purenote.local.core.PreviewBuilder
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.io.File

/** 首行即标题：输入时首行自动加粗放大（小米笔记式流式正文） */
private val TitleFirstLineTransform = VisualTransformation { text ->
    val idx = text.text.indexOf('\n')
    if (idx <= 0) {
        TransformedText(text, OffsetMapping.Identity)
    } else {
        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                append(text.text.substring(0, idx))
            }
            append(text.text.substring(idx))
        }
        TransformedText(annotated, OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EditorScreen(vm: NoteViewModel, screen: Screen.Editor) {
    val context = LocalContext.current
    val folders by vm.folders.collectAsState()

    var loaded by remember { mutableStateOf(screen.noteId <= 0) }
    var prefillApplied by remember { mutableStateOf(false) }
    var noteId by remember { mutableStateOf(screen.noteId) }
    var creating by remember { mutableStateOf(false) }
    val kind = screen.kind

    var text by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<ChecklistItem>() }
    val imageNames = remember { mutableStateListOf<String>() }
    var colorIndex by remember { mutableStateOf(0) }
    var folderId by remember { mutableStateOf<Long?>(screen.folderId) }
    var pinned by remember { mutableStateOf(false) }
    var remindAt by remember { mutableStateOf<Long?>(null) }
    var revision by remember { mutableStateOf(0) }

    var menuOpen by remember { mutableStateOf(false) }
    var imageMenuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }

    if (screen.noteId > 0 && !loaded) {
        vm.getNoteOnce(screen.noteId) { n ->
            if (n != null) {
                text = PreviewBuilder.joinTitleBody(n.title, n.body)
                items.clear()
                items.addAll(n.items)
                imageNames.clear()
                imageNames.addAll(n.images)
                colorIndex = n.colorIndex
                folderId = n.folderId
                pinned = n.pinned
                remindAt = n.remindAt
            } else {
                vm.goHome()
            }
            loaded = true
        }
    }

    // 分享进入的预填内容（只应用一次）
    LaunchedEffect(loaded, prefillApplied) {
        if (loaded && !prefillApplied && screen.noteId <= 0) {
            val p = screen.prefill
            if (p.title.isNotBlank() || p.body.isNotBlank()) {
                text = PreviewBuilder.joinTitleBody(p.title, p.body)
            }
            prefillApplied = true
            if (text.isNotBlank() || p.imageUris.isNotEmpty()) revision++
            p.imageUris.forEach { uri ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ImageStore.importFromUri(context, android.net.Uri.parse(uri)) }.getOrNull()
                }?.let { name ->
                    imageNames.add(name)
                    revision++
                }
            }
        }
    }

    fun markDirty() {
        revision++
    }

    fun isEmptyDraft(): Boolean =
        when (kind) {
            NoteKind.TEXT -> text.isBlank() && imageNames.isEmpty()
            NoteKind.CHECKLIST -> items.all { it.text.isBlank() } && imageNames.isEmpty()
        }

    fun persist() {
        if (!loaded || isEmptyDraft()) return
        val (titlePart, bodyPart) = PreviewBuilder.splitTitle(text)
        if (noteId > 0) {
            vm.updateNote(
                noteId = noteId,
                kind = kind,
                title = titlePart,
                body = bodyPart,
                items = items.filter { it.text.isNotBlank() },
                images = imageNames.toList(),
                colorIndex = colorIndex,
                folderId = folderId,
                pinned = pinned,
                remindAt = remindAt,
            )
        } else if (!creating) {
            creating = true
            vm.createNote(
                kind = kind,
                title = titlePart,
                body = bodyPart,
                items = items.filter { it.text.isNotBlank() },
                images = imageNames.toList(),
                colorIndex = colorIndex,
                folderId = folderId,
                remindAt = remindAt,
            ) { newId ->
                noteId = newId
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
            snapshotFlow { Triple(text, kind, revision) }
                .drop(1)
                .debounce(500)
                .collect { persist() }
        }
    }

    BackHandler { saveAndClose() }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = pendingCameraFile
        pendingCameraFile = null
        if (ok && target != null) {
            ImageStore.importCaptured(context, target)?.let {
                imageNames.add(it); markDirty(); persist()
            }
        }
    }
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            ImageStore.importFromUri(context, uri)?.let {
                imageNames.add(it); markDirty(); persist()
            }
        }
    }

    // 签名交互：编辑器背景就是这张便签纸的颜色
    Scaffold(
        containerColor = noteContainerColor(colorIndex),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onClick = { saveAndClose() }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                title = {},
                actions = {
                    IconButton(onClick = {
                        pinned = !pinned
                        markDirty()
                        persist()
                    }) {
                        Icon(
                            Icons.Outlined.PushPin,
                            "置顶",
                            tint = if (pinned) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("更换纸色") }, onClick = {
                                menuOpen = false; colorOpen = true
                            })
                            DropdownMenuItem(text = { Text("移动到分类") }, onClick = {
                                menuOpen = false; moveOpen = true
                            })
                            if (remindAt != null) {
                                DropdownMenuItem(text = { Text("清除提醒") }, onClick = {
                                    menuOpen = false
                                    remindAt = null
                                    markDirty()
                                    persist()
                                })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Box {
                        IconButton(onClick = { imageMenuOpen = true }) {
                            Icon(Icons.Outlined.Image, "插入图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = imageMenuOpen, onDismissRequest = { imageMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("拍照") }, onClick = {
                                imageMenuOpen = false
                                val target = ImageStore.newCameraTarget(context)
                                pendingCameraFile = target
                                cameraLauncher.launch(
                                    FileProvider.getUriForFile(context, context.packageName + ".files", target),
                                )
                            })
                            DropdownMenuItem(text = { Text("从相册选择") }, onClick = {
                                imageMenuOpen = false
                                pickImageLauncher.launch("image/*")
                            })
                        }
                    }
                    IconButton(onClick = { colorOpen = true }) {
                        Icon(Icons.Outlined.Palette, "卡片底色", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        openReminderPicker(context) { newAt ->
                            remindAt = newAt
                            markDirty()
                            persist()
                        }
                    }) {
                        Icon(Icons.Outlined.NotificationsNone, "提醒", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val (t, b) = PreviewBuilder.splitTitle(text)
                        shareNoteText(
                            context,
                            t.ifBlank { "" },
                            if (kind == NoteKind.TEXT) b
                            else items.joinToString("\n") { "${if (it.done) "☑" else "☐"} ${it.text}" },
                        )
                    }) {
                        Icon(Icons.Outlined.Share, "分享", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(
                    folders.firstOrNull { it.id == folderId }?.name ?: "未分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.weight(1f))
                if (remindAt != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp),
                        ) {
                            Icon(
                                Icons.Outlined.NotificationsNone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                formatNoteTime(remindAt ?: 0L),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            IconButton(onClick = {
                                remindAt = null
                                markDirty()
                                persist()
                            }, modifier = Modifier.size(26.dp)) {
                                Icon(
                                    Icons.Outlined.Close,
                                    "清除提醒",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (imageNames.isNotEmpty()) {
                ImagesStrip(
                    names = imageNames.toList(),
                    onDelete = { name ->
                        imageNames.remove(name)
                        ImageStore.deleteFile(context, name)
                        markDirty()
                        persist()
                    },
                )
                Spacer(Modifier.height(10.dp))
            }

            when (kind) {
                NoteKind.TEXT -> FlowTextField(
                    value = text,
                    onChange = { text = it; markDirty() },
                    modifier = Modifier.weight(1f),
                )
                NoteKind.CHECKLIST -> ChecklistEditor(
                    items = items,
                    onChangeList = { markDirty() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条笔记？") },
            text = { Text("会移入废纸篓，30 天后自动清除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (noteId > 0) vm.trash(noteId) else vm.goHome()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    if (moveOpen) {
        MoveFolderDialog(
            current = folderId,
            folders = folders,
            onDismiss = { moveOpen = false },
            onPick = { target ->
                folderId = target
                moveOpen = false
                markDirty()
                persist()
            },
        )
    }

    if (colorOpen) {
        ColorPickDialog(
            current = colorIndex,
            onDismiss = { colorOpen = false },
            onPick = { idx ->
                colorIndex = idx
                colorOpen = false
                markDirty()
                persist()
            },
        )
    }
}

/** 小米式流式正文：首行标题加粗；外层可滚动保证长文可达 */
@Composable
private fun FlowTextField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier.verticalScroll(scroll)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                fontSize = 17.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = TitleFirstLineTransform,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "标题",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun ImagesStrip(names: List<String>, onDelete: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        items(names, key = { it }) { name ->
            Box {
                AsyncThumb(
                    fileName = name,
                    modifier = Modifier.size(width = 112.dp, height = 112.dp),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp)
                        .clickable { onDelete(name) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Close,
                            "移除图片",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
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
                (0 until 6).forEach { idx ->
                    val selected = ((current % 6) + 6) % 6 == idx
                    Surface(
                        shape = CircleShape,
                        color = noteContainerColor(idx),
                        border = androidx.compose.foundation.BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onPick(idx) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChecklistEditor(
    items: MutableList<ChecklistItem>,
    onChangeList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(items) { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                MiCheckbox(
                    done = item.done,
                    size = 20.dp,
                    onClick = {
                        items[index] = items[index].copy(done = !items[index].done)
                        onChangeList()
                    },
                )
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    BasicTextField(
                        value = item.text,
                        onValueChange = { newText ->
                            items[index] = items[index].copy(text = newText)
                            onChangeList()
                        },
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box {
                                if (item.text.isEmpty()) {
                                    Text("条目内容", color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = {
                    items.removeAt(index)
                    onChangeList()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        "移除条目",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        items.add(ChecklistItem(""))
                        onChangeList()
                    }
                    .padding(vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加条目", color = MaterialTheme.colorScheme.secondary)
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

/** 直接打开系统日期/时间选择器，仅接受未来时间 */
fun openReminderPicker(context: Context, onPicked: (Long) -> Unit) {
    val now = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(
        context,
        { _, y, m, d ->
            android.app.TimePickerDialog(
                context,
                { _, h, min ->
                    val c = java.util.Calendar.getInstance().apply {
                        set(y, m, d, h, min, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    if (c.timeInMillis > System.currentTimeMillis()) onPicked(c.timeInMillis)
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
