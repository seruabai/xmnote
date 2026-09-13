package com.purenote.local.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.purenote.local.core.ImageStore

/** 一条笔迹：点序列 + 画笔样式（颜色/线宽 dp），保存与回显都按各自样式渲染。 */
data class PenStroke(
    val points: List<Offset>,
    val color: Color,
    val widthDp: Float,
)

/** 画笔调色板：3 色 × 3 粗细（用户 2026-09-13 要求画板支持多画笔） */
private val PEN_COLORS = listOf(
    Color(0xFF1C1C1E),   // 墨黑
    Color(0xFFFF3B30),   // 红
    Color(0xFF007AFF),   // 蓝
)
private val PEN_WIDTHS = listOf(2f, 4f, 7f)   // dp
private val PEN_WIDTH_LABELS = listOf("细", "中", "粗")

/**
 * 手写画板（第3键）：全屏白板，多画笔（颜色×粗细）+ 撤销/清空，「完成」时按画布像素尺寸
 * 离屏重绘为图片存入 images 目录，文件名回调给编辑器插到光标行。
 */
@Composable
fun DrawBoardDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<PenStroke>() }
    var drawing by remember { mutableStateOf<PenStroke?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var penColor by remember { mutableStateOf(PEN_COLORS.first()) }
    var penWidthIndex by remember { mutableStateOf(1) }

    fun finish() {
        val all = strokes.toList() + listOfNotNull(drawing)
        if (all.all { it.points.isEmpty() }) {
            onDismiss()
            return
        }
        saving = true
        val size = canvasSize
        val color = penColor
        val widthDp = PEN_WIDTHS[penWidthIndex]
        Thread {
            val name = runCatching {
                val w = size.width.coerceAtLeast(1)
                val h = size.height.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                val metrics = context.resources.displayMetrics
                val paint = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    isAntiAlias = true
                }
                for (stroke in all) {
                    if (stroke.points.isEmpty()) continue
                    // dp → px 用同一套 displayMetrics,与屏上绘制定宽
                    paint.color = stroke.color.toArgb()
                    paint.strokeWidth = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_DIP, stroke.widthDp, metrics,
                    )
                    if (stroke.points.size == 1) {
                        // 单点：画圆点
                        paint.style = android.graphics.Paint.Style.FILL
                        canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, paint.strokeWidth / 2f, paint)
                        paint.style = android.graphics.Paint.Style.STROKE
                        continue
                    }
                    val path = android.graphics.Path()
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    for (p in stroke.points.drop(1)) path.lineTo(p.x, p.y)
                    canvas.drawPath(path, paint)
                }
                ImageStore.saveBitmap(context, bmp).also { bmp.recycle() }
            }.getOrNull()
            android.os.Handler(context.mainLooper).post {
                saving = false
                if (name != null) onSave(name)
                else android.widget.Toast.makeText(context, "手写保存失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        MotionDialogEnter {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                    Text(
                        "手写",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = ::finish, enabled = !saving) { Text(if (saving) "保存中…" else "完成") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
                // 画笔选择行：3 色 × 3 粗细 + 撤销/清空
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                ) {
                    PEN_COLORS.forEach { c ->
                        val selected = c == penColor
                        Box(
                            modifier = Modifier
                                .size(if (selected) 26.dp else 22.dp)
                                .background(c, CircleShape)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable(enabled = !saving) { penColor = c },
                        )
                        Box(Modifier.size(width = 10.dp, height = 1.dp))
                    }
                    Box(Modifier.size(width = 14.dp, height = 1.dp))
                    PEN_WIDTHS.forEachIndexed { index, w ->
                        val selected = index == penWidthIndex
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable(enabled = !saving) { penWidthIndex = index },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size((6f + w * 1.4f).dp)
                                    .background(penColor, CircleShape),
                            )
                        }
                        Box(Modifier.size(width = 8.dp, height = 1.dp))
                    }
                    Box(Modifier.weight(1f))
                    TextButton(
                        onClick = { strokes.removeLastOrNull(); drawing = null },
                        enabled = !saving && strokes.isNotEmpty(),
                    ) { Text("撤销") }
                    TextButton(
                        onClick = { strokes.clear(); drawing = null },
                        enabled = !saving && (strokes.isNotEmpty() || drawing != null),
                    ) { Text("清除") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(saving) {
                            if (saving) return@pointerInput
                            detectDragGestures(
                                onDragStart = { pos ->
                                    drawing = PenStroke(listOf(pos), penColor, PEN_WIDTHS[penWidthIndex])
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    drawing?.let { d ->
                                        drawing = d.copy(points = d.points + change.position)
                                    }
                                },
                                onDragEnd = {
                                    drawing?.let { if (it.points.isNotEmpty()) strokes.add(it) }
                                    drawing = null
                                },
                            )
                        },
                ) {
                    for (s in strokes) {
                        drawStrokePath(s.points, s.color, s.widthDp)
                    }
                    drawing?.let { d ->
                        drawStrokePath(d.points, d.color, d.widthDp)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(
                        onClick = { strokes.clear(); drawing = null },
                        enabled = !saving && (strokes.isNotEmpty() || drawing != null),
                    ) { Text("清除") }
                }
            }
        }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    points: List<Offset>,
    color: Color,
    widthDp: Float,
) {
    val stroke = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round)
    if (points.size < 2) {
        if (points.size == 1) drawCircle(color, radius = stroke.width / 2f, center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, color, style = stroke)
}
