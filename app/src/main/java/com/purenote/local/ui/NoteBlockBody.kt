package com.purenote.local.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.purenote.local.NoteTextSize
import com.purenote.local.core.BlockIds
import com.purenote.local.core.BlockSpan
import com.purenote.local.core.BlockType
import com.purenote.local.core.RichBlock
import com.purenote.local.core.RichDoc
import com.purenote.local.core.TextAlign as BlockAlign
import com.purenote.local.core.blockIndexAt
import com.purenote.local.core.blockInsertIndex
import com.purenote.local.core.blockMoveTarget
import com.purenote.local.core.find
import com.purenote.local.core.indexOf
import com.purenote.local.core.mergeWithPrevious
import com.purenote.local.core.move
import com.purenote.local.core.remove
import com.purenote.local.core.splitAt
import com.purenote.local.core.toggleChecked
import com.purenote.local.core.updateText
import androidx.compose.runtime.LaunchedEffect as ComposeLaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 块内光标：块 id + 块内偏移。工具栏据此判断"操作的是哪一块" */
data class BlockCursor(val blockId: String, val caret: Int = 0)

/**
 * 笔记正文的**块渲染**版本，也是编辑器唯一的正文形态。
 *
 * 2.1 时这里的契约还是"一段 Markdown 文本进、一段文本出"，好让编辑器只换一行就能回退；
 * 工具栏切成"直接操作光标所在块"之后，标记文本那层往返（标记桥）已经没有存在理由，
 * 于是契约改成**块文档进、块文档出**：样式/插图都改文档本身，不再修改文本内容。
 *
 * 块下标、插入边界、拖拽落点这些换算都在 core（RichDoc / BlockDrag / BlockEdit）里，
 * 是纯函数、可单测；这里只负责呈现、输入法与手势。
 *
 * 块级拖拽排序见 core/BlockDrag：长按抬起一块 → 跟着手指走 → 指示线标出落点。
 *
 * **只有没有输入框的块（图片/录音/链接卡片）能拖**：文本块长按不参与拖拽。
 * 原因（用户 2026-09-17 提出）：文本块几乎铺满整行，长按等长按阈值很容易在
 * "边想边点屏幕"时误触发拖拽，而文本笔记里排序的收益远小于误触代价；
 * 文本块的长按回归它本来的用途——选字。附带好处：系统那个
 * "Select all / Autofill" 浮层再也不会被拖拽带出来了。
 */
@Composable
fun NoteBlockBody(
    doc: RichDoc,
    textSize: NoteTextSize,
    onCursor: (BlockCursor) -> Unit,
    onDocChange: (RichDoc) -> Unit,
    /** 正文是否持有焦点：工具栏在 API < 35 上要靠它判断"键盘来了"（见 EditorScreen 注释） */
    onFocusChange: (Boolean) -> Unit = {},
    cursorRequest: BlockCursor? = null,
    onCursorConsumed: () -> Unit = {},
    onImageTap: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val typeScale = textSize.typeScale()
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val textToolbar = LocalTextToolbar.current

    // 编辑以本地文档为准：同一帧里可能连着改两次（输入后立刻回车拆块），等外部回传会用到旧值。
    // 外部（工具栏/插图/加载笔记）改了文档就采纳；自己发出去的那份回来时相等，不会打断输入
    var local by remember { mutableStateOf(doc) }
    LaunchedEffect(doc) {
        if (doc != local) local = doc
    }

    val states = remember { mutableStateMapOf<String, TextFieldValue>() }
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val pendingFocus = remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    // 手势协程的寿命比单次重组长，回调必须取最新版本，否则闭包里是首帧的回调
    val emitDoc = rememberUpdatedState(onDocChange)

    // ---- 拖拽排序状态：draggingId 在长按命中时就定下，lifted 到真正拖动才置起 ----
    var draggingId by remember { mutableStateOf<String?>(null) }
    var lifted by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    // 拖拽结束后要再收一次选区（见下方 LaunchedEffect）
    val settleSelection = remember { mutableStateOf<String?>(null) }

    /**
     * 每次输入都把文本同步进文档。
     *
     * 关键：不能只更新输入框状态而让文档停在旧值——回车拆块是按文档里的文本切分的，
     * 文档滞后会让切分点整体错位（实测表现为"回车不换行，文字挤在一起"）。
     */
    fun applyText(blockId: String, tfv: TextFieldValue) {
        states[blockId] = tfv
        val before = local
        val updated = before.updateText(blockId, tfv.text)
        local = updated
        // 文本框把"选区变化"也走 onValueChange 报上来：文字没变就不要往外发，
        // 否则拖拽/长按选字这种纯光标动作会被编辑器当成一次改动去写库
        if (updated != before) emitDoc.value(updated)
        onCursor(BlockCursor(blockId, tfv.selection.start))
    }

    fun commit(newDoc: RichDoc, focus: String?, caret: Int = 0) {
        local = newDoc
        emitDoc.value(newDoc)
        // 已被删除/合并掉的块，输入态要一并清掉，否则下次同 id 复用时是陈旧文本
        val alive = newDoc.blocks.map { it.id }.toSet()
        states.keys.retainAll(alive)
        if (focus != null) {
            val line = newDoc.blocks.firstOrNull { it.id == focus }?.text ?: ""
            pendingFocus.value = focus
            states[focus] = TextFieldValue(line, TextRange(caret.coerceIn(0, line.length)))
        }
    }

    /** 可见块在视口里的纵向范围（LazyColumn 的 item.index 就是块下标） */
    fun visibleSpans(): List<BlockSpan> = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        if (local.blocks.getOrNull(info.index) == null) {
            null
        } else {
            BlockSpan(info.index, info.offset.toFloat(), (info.offset + info.size).toFloat())
        }
    }

    /** 松手：按指示线所在边界落块；顺序没变就什么都不做（不写库、不产生一次无谓保存） */
    fun dropDragged() {
        val id = draggingId ?: return
        val spans = visibleSpans()
        val at = if (spans.isEmpty()) -1 else blockInsertIndex(spans, pointerY, local.blocks.size)
        val to = blockMoveTarget(local.indexOf(id), at)
        if (to != null) {
            // 只换顺序，块 id 与每块的行内状态都还在，所以不必清 states（清掉反而会丢光标）
            val moved = local.move(id, to)
            local = moved
            emitDoc.value(moved)
        }
        draggingId = null
        lifted = false
        dragOffsetY = 0f
    }

    /**
     * 命中块与移动阈值：长按抬手时定位是"按在谁身上"，随后按位移判断是否真的在拖。
     * 文本块直接不参与（见文件头注释）：不吞事件、不给触感反馈，长按留给选字。
     */
    fun beginDrag(y: Float) {
        val hitIndex = blockIndexAt(visibleSpans(), y)
        val hit = local.blocks.getOrNull(hitIndex) ?: return
        if (local.blocks.size < 2) return
        if (!hit.draggable) return
        draggingId = hit.id
        lifted = false
        dragOffsetY = 0f
        pointerY = y
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // 外部请求光标（插图后落到新块等）：按块 id 直接落，不再做全局偏移换算
    LaunchedEffect(cursorRequest) {
        val target = cursorRequest ?: return@LaunchedEffect
        val block = local.find(target.blockId)
        if (block != null) {
            pendingFocus.value = block.id
            val caret = target.caret.coerceIn(0, block.text.length)
            states[block.id] = (states[block.id] ?: TextFieldValue(block.text)).copy(
                selection = TextRange(caret),
            )
        }
        onCursorConsumed()
    }

    // 拖拽结束后隔一拍再收一次：文本框在长按选中期间会忽略外部值变化，
    // 只有等它自己的选择手势彻底结束，收选区与收浮层才会真正生效
    LaunchedEffect(settleSelection.value) {
        val id = settleSelection.value ?: return@LaunchedEffect
        // 浮层是平台异步弹出来的，收完立刻 hide 有时赶在它弹出来之前（实测会残留）。
        // 于是连着收一小段时间：只要它冒出来就被按下去。
        repeat(8) {
            kotlinx.coroutines.delay(120)
            states[id]?.let { tfv ->
                if (!tfv.selection.collapsed) {
                    states[id] = tfv.copy(selection = TextRange(tfv.selection.start))
                }
            }
            // 说明：那个 "Select all / Autofill" 浮层是平台自己的，Compose 的 TextToolbar.hide()
            // 与 AutofillManager.cancel() 都收不掉它（都试过）；这里只能保证选区被收回、文字不被选中。
            // 要彻底不弹，只能改成"从左边缘把手拖"这种不碰文本框的方案（待用户定）
            textToolbar.hide()
        }
        settleSelection.value = null
    }

    // 拖到列表上下边缘时自动滚动：长文里不这么做就永远拖不到头（对应小米的 Scrollable 插件）
    LaunchedEffect(draggingId, lifted) {
        if (draggingId == null || !lifted) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val top = info.viewportStartOffset.toFloat()
            val bottom = info.viewportEndOffset.toFloat()
            val y = pointerY
            val step = when {
                y < top + edge -> -maxStep * ((top + edge - y) / edge).coerceIn(0f, 1f)
                y > bottom - edge -> maxStep * ((y - (bottom - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (step != 0f) listState.scrollBy(step)
        }
    }

    val spans = visibleSpans()
    val fromIndex = draggingId?.let { local.indexOf(it) } ?: -1
    val insertAt = if (fromIndex >= 0 && spans.isNotEmpty()) {
        blockInsertIndex(spans, pointerY, local.blocks.size)
    } else {
        -1
    }
    // 指示线画在插入边界上；顺序不会变时不画（对应小米 divider 插件里"悬停自己就隐藏"）
    val indicatorY: Float? = if (
        fromIndex >= 0 && insertAt >= 0 && spans.isNotEmpty() &&
        blockMoveTarget(fromIndex, insertAt) != null
    ) {
        if (insertAt >= local.blocks.size) {
            spans.last().bottom
        } else {
            spans.firstOrNull { it.index == insertAt }?.top
                ?: spans.firstOrNull { it.index == insertAt - 1 }?.bottom
        }
    } else {
        null
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 长按之前不碰任何事件：文本框的点击/选择、勾选框、列表滚动全都照常
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val armed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        beginDrag(armed.position.y)

                        val slop = viewConfiguration.touchSlop
                        val slopSq = slop * slop
                        val start = armed.position
                        var moved = false
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial)
                                .changes.firstOrNull { it.id == armed.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break
                            if (!moved) {
                                // 累计位移过阈值才算拖动：单次事件的位移可能只有零点几像素
                                // （慢速滑动会被合并成很小的增量），拿单次增量和 slop 比会永远起不来
                                val dx = change.position.x - start.x
                                val dy = change.position.y - start.y
                                if (dx * dx + dy * dy > slopSq) {
                                    moved = true
                                    lifted = true
                                }
                            }
                            if (moved) {
                                // 在 Initial 阶段吃掉事件：文本框与列表都收不到这次拖动
                                change.consume()
                                // 用绝对位置而不是自己累加增量：慢速滑动下累加会丢位移，落点会差一格
                                pointerY = change.position.y
                                dragOffsetY = change.position.y - start.y
                                // 文本框自己的长按选择与块拖拽抢的是同一个手势：拖拽期间持续收回选区，
                                // 并收掉系统选择浮层（Select all / Autofill），否则拖块时一直挂着选字菜单。
                                // hide() 每个事件都调：浮层是异步弹出的，只在"收回选区那一次"调用会赶在它弹出来之前
                                draggingId?.let { id ->
                                    states[id]?.takeIf { !it.selection.collapsed }?.let { tfv ->
                                        states[id] = tfv.copy(selection = TextRange(tfv.selection.start))
                                    }
                                }
                                textToolbar.hide()
                            }
                        }
                        if (moved) {
                            val id = draggingId
                            dropDragged()
                            // 文本框在长按选中期间会忽略外部值变化，拖拽途中收选区不生效；
                            // 手势结束后再收一次，浮层（Select all / Autofill）才会跟着消失
                            if (id != null) {
                                states[id]?.let { tfv ->
                                    if (!tfv.selection.collapsed) {
                                        states[id] = tfv.copy(selection = TextRange(tfv.selection.start))
                                    }
                                }
                            }
                            textToolbar.hide()
                            settleSelection.value = id
                        } else {
                            // 纯长按没拖动：不当作拖拽，留给文本框自己的长按选择
                            draggingId = null
                            lifted = false
                            dragOffsetY = 0f
                        }
                    }
                },
        ) {
            items(local.blocks, key = { it.id }) { block ->
                val dragging = block.id == draggingId
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            if (dragging) {
                                translationY = dragOffsetY
                                val s = if (lifted) 1.02f else 1f
                                scaleX = s
                                scaleY = s
                                shadowElevation = if (lifted) 12.dp.toPx() else 0f
                                shape = RoundedCornerShape(10.dp)
                                alpha = if (lifted) 0.97f else 1f
                            }
                        }
                        .background(
                            if (dragging && lifted) MaterialTheme.colorScheme.surface else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        ),
                ) {
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
                            onFocusChange = onFocusChange,
                            onCursor = { caret -> onCursor(BlockCursor(block.id, caret)) },
                            onCheckedToggle = { commit(local.toggleChecked(block.id), focus = null) },
                            onEnter = { caret ->
                                val newId = BlockIds.newBlockId()
                                val split = local.splitAt(block.id, caret, newId)
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
                                val prevId = local.blocks.getOrNull(local.indexOf(block.id) - 1)?.id
                                val (merged, seam) = local.mergeWithPrevious(block.id)
                                if (merged !== local && prevId != null) commit(merged, focus = prevId, caret = seam)
                                0 // 调用方不使用返回值，只需让 lambda 类型明确
                            },
                            onEmptyBackspace = {
                                val removed = local.remove(block.id)
                                if (removed.blocks.size != local.blocks.size) {
                                    val prev = local.blocks.getOrNull(local.indexOf(block.id) - 1)
                                    commit(removed, focus = prev?.id, caret = prev?.text?.length ?: 0)
                                }
                            },
                        )
                    }
                }
            }
        }

        // 插入指示线：3dp 暖色横条，标出的就是松手后块落到的地方
        if (indicatorY != null) {
            val y = indicatorY - with(density) { 1.5.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, y.roundToInt()) }
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
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
    onFocusChange: (Boolean) -> Unit,
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
                // 与文字中线对齐：汉字墨水中心比行盒中心低，勾选框要按比例下移一点
                // （与 MiCheckbox 同一套光学修正，见 TodoCard.kt）
                modifier = Modifier
                    .size(34.dp)
                    .offset { IntOffset(0, with(density) { 34.dp.toPx() * 0.07f }.roundToInt()) },
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
                .onFocusChanged { focused ->
                    onFocusChange(focused.isFocused)
                    if (focused.isFocused) onCursor(state.selection.start)
                },
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
