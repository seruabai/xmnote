package com.purenote.local.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.purenote.local.core.ImageStore

/**
 * 手写画板（第3键）：全屏白板黑笔迹，「完成」时按画布像素尺寸离屏重绘为图片
 * 存入 images 目录，文件名回调给编辑器插到光标行。
 */
@Composable
fun DrawBoardDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var drawing by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    fun finish() {
        val all = strokes.toList() + listOf(drawing)
        if (all.all { it.isEmpty() }) {
            onDismiss()
            return
        }
        saving = true
        val size = canvasSize
        Thread {
            val name = runCatching {
                val w = size.width.coerceAtLeast(1)
                val h = size.height.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 5f
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    isAntiAlias = true
                }
                for (stroke in all) {
                    if (stroke.isEmpty()) continue
                    if (stroke.size == 1) {
                        // 单点：画圆点
                        paint.style = android.graphics.Paint.Style.FILL
                        canvas.drawCircle(stroke[0].x, stroke[0].y, 2.5f, paint)
                        paint.style = android.graphics.Paint.Style.STROKE
                        continue
                    }
                    val path = android.graphics.Path()
                    path.moveTo(stroke[0].x, stroke[0].y)
                    for (p in stroke.drop(1)) path.lineTo(p.x, p.y)
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
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(saving) {
                            if (saving) return@pointerInput
                            detectDragGestures(
                                onDragStart = { pos -> drawing = listOf(pos) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    drawing = drawing + change.position
                                },
                                onDragEnd = {
                                    if (drawing.isNotEmpty()) strokes.add(drawing)
                                    drawing = emptyList()
                                },
                            )
                        },
                ) {
                    val color = androidx.compose.ui.graphics.Color.Black
                    val stroke = Stroke(width = 5f)
                    for (s in strokes) drawStrokePath(s, color, stroke)
                    drawStrokePath(drawing, color, stroke)
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(
                        onClick = { strokes.clear(); drawing = emptyList() },
                        enabled = !saving && (strokes.isNotEmpty() || drawing.isNotEmpty()),
                    ) { Text("清除") }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    points: List<Offset>,
    color: androidx.compose.ui.graphics.Color,
    stroke: Stroke,
) {
    if (points.size < 2) {
        if (points.size == 1) drawCircle(color, radius = 2.5f, center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, color, style = stroke)
}
