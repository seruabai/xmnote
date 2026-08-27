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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
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
import com.purenote.local.core.ImageStore
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.NoteKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 小米笔记式编辑器：独立大标题、时间/字数、留白正文和底部五项工具栏。 */
@OptIn(FlowPreview::class)
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
    var revision by remember { mutableStateOf(0) }

    var moreMenu by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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
            ImageStore.importFromUri(context, uri)?.let {
                imageNames.add(it)
                markDirty()
            }
        }
    }

    val createdLabel = remember(screen.noteId) {
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date())
    }
    val words = title.length + body.length + items.sumOf { it.text.length }

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
                        DropdownMenuItem(
                            text = { Text(if (pinned) "取消置顶" else "置顶") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { pinned = !pinned; moreMenu = false; markDirty() },
                        )
                        DropdownMenuItem(
                            text = { Text("设置提醒") },
                            leadingIcon = { Icon(Icons.Outlined.NotificationsNone, null) },
                            onClick = {
                                moreMenu = false
                                openReminderPicker(context) { remindAt = it; markDirty() }
                            },
                        )
                        DropdownMenuItem(text = { Text("移动到分类") }, onClick = { moreMenu = false; moveOpen = true })
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { moreMenu = false; confirmDelete = true },
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .32f))
                Row(
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(61.dp),
                ) {
                    EditorTool(Icons.Outlined.GraphicEq, "语音") { }
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
                            DropdownMenuItem(text = { Text("从相册选择") }, onClick = {
                                imageMenu = false
                                imageLauncher.launch("image/*")
                            })
                        }
                    }
                    EditorTool(Icons.Outlined.Draw, "手写") { colorOpen = true }
                    EditorTool(Icons.Outlined.CheckBox, "清单") {
                        if (kind == NoteKind.TEXT) {
                            body = if (body.isBlank()) "☐ " else "$body\n☐ "
                            markDirty()
                        } else {
                            items.add(ChecklistItem(""))
                            markDirty()
                        }
                    }
                    EditorTool(Icons.Outlined.Title, "文本") { }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 22.dp),
        ) {
            val titleTextStyle = TextStyle(
                fontSize = 31.sp,
                lineHeight = 38.sp,
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
                        modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxWidth().padding(top = 19.dp).height(42.dp),
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
                    onChange = { body = it; markDirty() },
                    modifier = Modifier.weight(1f),
                )
                NoteKind.CHECKLIST -> ChecklistEditor(
                    items = items,
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

@Composable
private fun TextNoteBody(
    value: String,
    textSize: NoteTextSize,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontSize = when (textSize) {
        NoteTextSize.SMALL -> 16.sp
        NoteTextSize.DEFAULT -> 18.sp
        NoteTextSize.LARGE -> 21.sp
    }
    val scroll = rememberScrollState()
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.55f,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开始书写或", fontSize = 19.sp, color = MaterialTheme.colorScheme.outlineVariant)
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
            }
        },
        modifier = modifier.fillMaxWidth().verticalScroll(scroll),
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
    onChangeList: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                        fontSize = 17.sp,
                        lineHeight = 23.sp,
                        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (item.text.isEmpty()) Text("清单内容", color = MaterialTheme.colorScheme.outlineVariant)
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
