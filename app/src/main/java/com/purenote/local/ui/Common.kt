package com.purenote.local.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.purenote.local.core.DateFormats
import java.util.Calendar

/** 废纸篓时间：只显示"日期 + 时间"，不用昨天/前天这类相对文案 */
fun formatTrashTime(ts: Long): String =
    "${DateFormats.smartDate(ts)} ${DateFormats.hourMinute(ts)}"

fun formatNoteTime(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val now = Calendar.getInstance()
    val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return DateFormats.hourMinute(ts)
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天"
    return DateFormats.smartDate(ts)
}

/** 笔记卡片时间栏：固定年月日，不带时间（用户 2026-09-07 要求） */
fun formatNoteDate(ts: Long): String = DateFormats.yearMonthDay(ts)

/** 笔记纸色盘：浅色/深色两套，index 0 为默认白纸。iOS 系统调：极淡的中性灰与雾色。 */
private val PaperLight = intArrayOf(
    0xFFFFFFFF.toInt(),   // 白纸（默认）
    0xFFF2F2F7.toInt(),   // systemGray6 浅灰
    0xFFF7F2E7.toInt(),   // 米杏（很淡）
    0xFFEBF2F9.toInt(),   // 雾蓝（很淡）
    0xFFEBF4EF.toInt(),   // 薄荷（很淡）
    0xFFF8EEF1.toInt(),   // 雾粉（很淡）
)

private val PaperDark = intArrayOf(
    0xFF1C1C1E.toInt(),   // systemGray6（深色）
    0xFF262628.toInt(),   // 深灰
    0xFF2B2721.toInt(),   // 深米杏
    0xFF212B35.toInt(),   // 深雾蓝
    0xFF1F2E27.toInt(),   // 深薄荷
    0xFF312429.toInt(),   // 深雾粉
)

/** 卡片纸色：跟随主题自动取浅/暗纸盘 */
@Composable
fun noteContainerColor(index: Int): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (dark) PaperDark else PaperLight
    return Color(palette[(((index % 6) + 6) % 6)])
}

/** 从私有目录异步解码一张缩略图 */
@Composable
fun AsyncThumb(fileName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(fileName) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(fileName) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = com.purenote.local.core.ImageStore.fileFor(context, fileName)
            if (!f.exists()) return@withContext null
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 1200) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    f.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

/** 分享笔记为纯文本（本地能力，不经过任何网络） */
fun shareNoteText(context: android.content.Context, title: String, body: String) {
    val text = if (title.isBlank()) body else "$title\n$body"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享笔记"))
}

/** 移动分类对话框（支持批量） */
@Composable
fun MoveFolderDialog(
    current: Long?,
    folders: List<com.purenote.local.data.Folder>,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("移动到分类") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(null) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == null, onClick = { onPick(null) })
                    Text("无分类")
                }
                folders.forEach { folder ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(folder.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == folder.id, onClick = { onPick(folder.id) })
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    )
}
