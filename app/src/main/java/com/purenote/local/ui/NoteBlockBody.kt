package com.purenote.local.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteTextSize
import com.purenote.local.core.BlockIds
import com.purenote.local.core.BlockType
import com.purenote.local.core.LegacyBody
import com.purenote.local.core.RichBlock
import com.purenote.local.core.RichDoc
import com.purenote.local.core.TextAlign as BlockAlign
import com.purenote.local.core.indexOf
import com.purenote.local.core.mergeWithPrevious
import com.purenote.local.core.remove
import com.purenote.local.core.splitAt
import com.purenote.local.core.toggleChecked
import com.purenote.local.core.updateText
import androidx.compose.runtime.LaunchedEffect as ComposeLaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 笔记正文的**块渲染**版本。
 *
 * 与 [TextNoteBody] 保持**完全相同的参数契约**，因此可以原地替换：
 * 编辑器其余部分（保存、工具栏、插图、IME 处理）一行都不用改，出问题也能一行换回。
 *
 * 这样做的理由：正文的块模型已经落地（core/RichDoc），但如果把编辑器的状态也一起
 * 换成 RichDoc，就要同时重写光标钳制、输入法跟随等一批历史修复——风险与收益不成比例。
 * 这里让块编辑器只负责"呈现与编辑"，对外仍然是"一段 Markdown 文本进、一段文本出"。
 */
@Composable
fun NoteBlockBody(
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
    val context = LocalContext.current

    // 外部内容（工具栏/插图/加载笔记）变化时重新解析；内部编辑不回读，避免打断输入
    var doc by remember { mutableStateOf(LegacyBody.fromText(value)) }
    var lastEmitted by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != lastEmitted) {
            doc = LegacyBody.fromText(value)
        }
    }

    val states = remember { mutableStateMapOf<String, TextFieldValue>() }
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val pendingFocus = remember { mutableStateOf<String?>(null) }

    /** 块在整段正文里的起始偏移：用于把"块内光标"换算成工具栏需要的全局偏移 */
    fun globalOffsetOf(blockId: String, caret: Int): Int {
        val blocks = doc.blocks
        var offset = 0
        for (b in blocks) {
            val line = LegacyBody.toText(RichDoc(blocks = listOf(b)))
            if (b.id == blockId) return offset + caret.coerceIn(0, line.length)
            offset += line.length + 1
        }
        return offset
    }

    /**
     * 每次输入都把文本同步进文档。
     *
     * 关键：不能只更新输入框状态而让 doc 停在旧值——回车拆块是按 doc 里的文本切分的，
     * 文档滞后会让切分点整体错位（实测表现为"回车不换行，文字挤在一起"）。
     */
    fun applyText(blockId: String, tfv: TextFieldValue) {
        states[blockId] = tfv
        val updated = doc.updateText(blockId, tfv.text)
        doc = updated
        val text = LegacyBody.toText(updated)
        lastEmitted = text
        onChange(text)
        onCursor(globalOffsetOf(blockId, tfv.selection.start))
    }

    fun commit(newDoc: RichDoc, focus: String?, caret: Int = 0) {
        doc = newDoc
        val text = LegacyBody.toText(newDoc)
        lastEmitted = text
        onChange(text)
        // 已被删除/合并掉的块，输入态要一并清掉，否则下次同 id 复用时是陈旧文本
        val alive = newDoc.blocks.map { it.id }.toSet()
        states.keys.retainAll(alive)
        if (focus != null) {
            val line = newDoc.blocks.firstOrNull { it.id == focus }?.text ?: ""
            pendingFocus.value = focus
            states[focus] = TextFieldValue(line, TextRange(caret.coerceIn(0, line.length)))
        }
    }

    // 外部请求光标（工具栏改样式后）：把全局偏移落到对应块
    LaunchedEffect(cursorRequest) {
        val target = cursorRequest ?: return@LaunchedEffect
        var offset = 0
        for (b in doc.blocks) {
            val line = LegacyBody.toText(RichDoc(blocks = listOf(b)))
            if (target <= offset + line.length) {
                pendingFocus.value = b.id
                val caret = (target - offset).coerceIn(0, line.length)
                states[b.id] = (states[b.id] ?: TextFieldValue(line)).copy(
                    selection = androidx.compose.ui.text.TextRange(caret),
                )
                break
            }
            offset += line.length + 1
        }
        onCursorConsumed()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(doc.blocks, key = { it.id }) { block ->
            when (block.type) {
                BlockType.IMAGE -> EmbedImage(
                    fileName = block.fileId.orEmpty(),
                    onTap = { onImageTap(block.fileId.orEmpty()) },
                )
                BlockType.SOUND -> EmbedSound(fileName = block.fileId.orEmpty())
                BlockType.LINK -> LinkRow(block = block)
                else -> BlockRow(
                    block = block,
                    typeScale = typeScale,
                    state = states[block.id] ?: TextFieldValue(block.text),
                    requester = requesters.getOrPut(block.id) { FocusRequester() },
                    shouldRequestFocus = pendingFocus.value == block.id,
                    onFocusHandled = { pendingFocus.value = null },
                    onTextChange = { tfv -> applyText(block.id, tfv) },
                    onCursor = { caret -> onCursor(globalOffsetOf(block.id, caret)) },
                    onCheckedToggle = { commit(doc.toggleChecked(block.id), focus = null) },
                    onEnter = { caret ->
                        val newId = BlockIds.newBlockId()
                        val split = doc.splitAt(block.id, caret, newId)
                        // 回车续接：勾选/项目符号沿用，有序列表序号 +1
                        val next = split.blocks.firstOrNull { it.id == newId }
                        val patched = if (next != null && block.type == BlockType.TODO) {
                            split.copy(
                                blocks = split.blocks.map {
                                    if (it.id == newId) it.copy(type = BlockType.TODO, checked = false) else it
                                },
                            )
                        } else {
                            split
                        }
                        commit(patched, focus = newId, caret = 0)
                    },
                    onBackspaceAtStart = {
                        val prevId = doc.blocks.getOrNull(doc.indexOf(block.id) - 1)?.id
                        val (merged, seam) = doc.mergeWithPrevious(block.id)
                        if (merged !== doc && prevId != null) commit(merged, focus = prevId, caret = seam)
                        0 // 调用方不使用返回值，只需让 lambda 类型明确
                    },
                    onEmptyBackspace = {
                        val removed = doc.remove(block.id)
                        if (removed.blocks.size != doc.blocks.size) {
                            val prev = doc.blocks.getOrNull(doc.indexOf(block.id) - 1)
                            commit(removed, focus = prev?.id, caret = prev?.text?.length ?: 0)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BlockRow(
    block: RichBlock,
    typeScale: NoteTypeScale,
    state: TextFieldValue,
    requester: FocusRequester,
    shouldRequestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onCursor: (Int) -> Unit,
    onCheckedToggle: () -> Unit,
    onEnter: (Int) -> Unit,
    onBackspaceAtStart: () -> Int,
    onEmptyBackspace: () -> Unit,
) {
    val fontSize = when (block.headingLevel) {
        1 -> typeScale.editorTitleSp
        2 -> typeScale.editorTitleSp * 0.82f
        3 -> typeScale.editorTitleSp * 0.7f
        else -> typeScale.editorBodySp
    }.sp
    // 回车建出的新块/工具栏改样式后的目标块：必须真的把焦点要过来，否则输入还留在旧块
    ComposeLaunchedEffect(shouldRequestFocus) {
        if (shouldRequestFocus) {
            requester.requestFocus()
            onFocusHandled()
        }
    }
    val style = TextStyle(
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.45f).sp,
        color = if (block.type == BlockType.QUOTE) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        textAlign = when (block.textAlign) {
            BlockAlign.CENTER -> TextAlign.Center
            BlockAlign.RIGHT -> TextAlign.End
            else -> TextAlign.Start
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = if (block.type == BlockType.TODO) Alignment.CenterVertically else Alignment.Top,
    ) {
        when (block.type) {
            BlockType.TODO -> Checkbox(
                checked = block.checked,
                onCheckedChange = { onCheckedToggle() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(34.dp),
            )
            BlockType.ITEM -> Marker(if (block.number > 0) block.number.toString() + "." else "•")
            BlockType.QUOTE -> Marker("❝")
            else -> if (block.indentHead) Spacer(Modifier.width(28.dp))
        }
        BasicTextField(
            value = state,
            onValueChange = { tfv ->
                val text = tfv.text
                when {
                    // 回车：光标前留在本块，光标后交给新块
                    text.contains('\n') -> {
                        val caret = tfv.selection.start.coerceAtMost(text.indexOf('\n'))
                        val head = text.substring(0, caret)
                        onTextChange(tfv.copy(text = head, selection = TextRange(head.length)))
                        onEnter(caret)
                    }
                    // 整块被清空：并入上一块（首块则由父级决定）
                    text.isEmpty() -> {
                        onTextChange(tfv)
                        onEmptyBackspace()
                    }
                    else -> onTextChange(tfv)
                }
            },
            textStyle = style,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .focusRequester(requester)
                .onPreviewKeyEvent { e ->
                    // 段首退格：此时文本没有任何变化，onValueChange 不会触发，只能从按键拦
                    val atStart = state.selection.start == 0 && state.selection.end == 0
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && atStart) {
                        if (block.text.isEmpty()) onEmptyBackspace() else onBackspaceAtStart()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(requester)
                .onFocusChanged { focused -> if (focused.isFocused) onCursor(state.selection.start) },
        )
    }
}

@Composable
private fun Marker(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(28.dp).padding(top = 6.dp),
    )
}

@Composable
private fun LinkRow(block: RichBlock) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = block.linkTitle ?: block.href.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 图片块：自绘加载（不引第三方图片库），带降采样避免大图 OOM */
@Composable
private fun EmbedImage(fileName: String, onTap: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, fileName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = com.purenote.local.core.ImageStore.fileFor(context, fileName)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = fileName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { onTap() },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            ) {
                Text(
                    text = "图片加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun EmbedSound(fileName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.GraphicEq, contentDescription = "录音", modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
