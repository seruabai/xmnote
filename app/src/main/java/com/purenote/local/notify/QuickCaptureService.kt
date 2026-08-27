package com.purenote.local.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.purenote.local.MainActivity
import com.purenote.local.PureNoteApp
import com.purenote.local.R
import com.purenote.local.data.Note
import com.purenote.local.data.NoteFilter
import com.purenote.local.data.SortOrder
import com.purenote.local.data.Todo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 跨应用速记侧栏。右缘窄把手点按/轻拉后打开参考图三的全屏半透明面板，
 * 可浏览最近笔记、勾选待办、内联新增待办或跳转到完整笔记编辑器。
 */
class QuickCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wm: WindowManager
    private var handle: View? = null
    private var panel: View? = null
    private var composerOpen = false
    private var quickDueAt: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureChannel(this)
        startForegroundCompat()
        showHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        removeViews()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("速记侧栏已开启")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("速记侧栏已开启")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun removeViews() {
        handle?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        handle = null
        panel = null
    }

    private fun handleParams() = WindowManager.LayoutParams(
        dip(11),
        dip(94),
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 0
        y = resources.displayMetrics.heightPixels * 2 / 5
    }

    private fun showHandle() {
        if (handle != null || !Settings.canDrawOverlays(this)) return
        val edge = View(this).apply {
            background = rounded(0xCCFFB800.toInt(), 12f)
            setOnClickListener { showPanel() }
        }
        val params = handleParams()
        var downY = 0f
        var startY = 0
        var moved = false
        edge.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dy) > dip(5)) moved = true
                    params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - view.height)
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                else -> true
            }
        }
        handle = edge
        wm.addView(edge, params)
    }

    private fun showPanel() {
        if (panel != null) return
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
        loadAndRenderPanel()
    }

    private fun loadAndRenderPanel() {
        val repo = (application as PureNoteApp).repository
        scope.launch {
            val notes = repo.loadNotes(NoteFilter(), SortOrder.BY_UPDATED).take(5)
            val todos = repo.loadTodos().filter { !it.isSubtask }.sortedWith(compareBy<Todo> { it.done }.thenBy { it.sortIndex })
            postToMain { renderPanel(notes, todos) }
        }
    }

    private fun renderPanel(notes: List<Note>, todos: List<Todo>) {
        panel?.let { runCatching { wm.removeView(it) } }

        val root = BackFrame(this).apply {
            isFocusableInTouchMode = true
            setBackgroundColor(0xE76E7A86.toInt())
            onBack = { hidePanel() }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(18), dip(58), dip(18), dip(28))
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        content.addView(noteHeader())
        content.addView(space(18))
        content.addView(noteStrip(notes))
        content.addView(space(18))
        content.addView(todoHeader())
        content.addView(space(17))

        if (composerOpen) {
            content.addView(todoComposer())
            content.addView(space(11))
        }
        if (todos.isEmpty()) {
            content.addView(cardText("暂无待办", 17f, 0xFF777777.toInt(), 82))
        } else {
            todos.forEach { todo ->
                content.addView(todoCard(todo))
                content.addView(space(10))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = root
        wm.addView(root, params)
        root.requestFocus()
    }

    private fun noteHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(plusButton { openNewNote() }, LinearLayout.LayoutParams(dip(38), dip(38)))
        row.addView(label("笔记 ︿", 18f, Color.WHITE, bold = false).apply {
            setPadding(dip(13), 0, 0, 0)
            setOnClickListener { hidePanel() }
        }, LinearLayout.LayoutParams(0, dip(38), 1f))
        row.addView(label("▢  摘录", 15f, Color.WHITE, bold = false).apply {
            gravity = Gravity.CENTER
            background = rounded(0x35FFFFFF, 28f)
        }, LinearLayout.LayoutParams(dip(104), dip(38)))
        return row
    }

    private fun todoHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(plusButton {
            composerOpen = !composerOpen
            loadAndRenderPanel()
        }, LinearLayout.LayoutParams(dip(38), dip(38)))
        row.addView(label("待办", 18f, Color.WHITE, bold = false).apply {
            setPadding(dip(13), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dip(38), 1f))
        return row
    }

    private fun plusButton(onClick: () -> Unit): TextView = label("+", 30f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = rounded(0xFFFFB800.toInt(), 100f)
        setOnClickListener { onClick() }
    }

    private fun noteStrip(notes: List<Note>): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (notes.isEmpty()) {
            row.addView(cardText("还没有笔记", 16f, 0xFF888888.toInt(), 148), LinearLayout.LayoutParams(dip(142), dip(148)))
        } else {
            notes.forEachIndexed { index, note ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dip(15), dip(16), dip(15), dip(13))
                    background = rounded(Color.WHITE, 18f)
                    elevation = dip(1).toFloat()
                    setOnClickListener { openNote(note.id) }
                }
                val headline = note.title.ifBlank { note.body.lineSequence().firstOrNull().orEmpty() }.ifBlank { "无标题" }
                val preview = if (note.title.isBlank()) note.body.lineSequence().drop(1).joinToString("\n") else note.body
                card.addView(label(headline, 17f, 0xFF171717.toInt(), bold = false), LinearLayout.LayoutParams.MATCH_PARENT, dip(49))
                card.addView(label(preview.take(90), 14f, 0xFF696969.toInt(), bold = false).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                card.addView(label(formatDate(note.updatedAt), 12f, 0xFF999999.toInt(), bold = false), LinearLayout.LayoutParams.MATCH_PARENT, dip(25))
                val params = LinearLayout.LayoutParams(dip(142), dip(150)).apply {
                    if (index > 0) marginStart = dip(10)
                }
                row.addView(card, params)
            }
        }
        scroll.addView(row)
        return scroll
    }

    private fun todoCard(todo: Todo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dip(21), 0, dip(18), 0)
            background = rounded(Color.WHITE, 17f)
            elevation = dip(1).toFloat()
        }
        val check = label(if (todo.done) "✓" else "□", 25f, if (todo.done) 0xFF222222.toInt() else 0xFFCCCCCC.toInt(), false).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                val repo = (application as PureNoteApp).repository
                scope.launch {
                    repo.setTodoDone(todo, !todo.done)
                    loadAndRenderPanel()
                }
            }
        }
        row.addView(check, LinearLayout.LayoutParams(dip(38), dip(76)))
        row.addView(label(todo.title.ifBlank { "待办清单" }, 18f, if (todo.done) 0xFFAAAAAA.toInt() else 0xFF171717.toInt(), false).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dip(76), 1f))
        return row
    }

    private fun todoComposer(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(18), dip(20), dip(13))
            background = rounded(Color.WHITE, 18f)
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        inputRow.addView(label("□", 25f, 0xFFCCCCCC.toInt(), false), LinearLayout.LayoutParams(dip(39), dip(49)))
        val input = EditText(this).apply {
            hint = "回车即可连续添加待办"
            setTextSize(17f)
            setTextColor(0xFF191919.toInt())
            setHintTextColor(0xFFBDBDBD.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dip(49), 1f))
        box.addView(inputRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val reminder = label(if (quickDueAt == null) "◴  设置提醒" else "◴  明天 09:00", 14f, 0xFF242424.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFF0F0F0.toInt(), 11f)
            setOnClickListener {
                val cal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 9)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                }
                quickDueAt = if (quickDueAt == null) cal.timeInMillis else null
                loadAndRenderPanel()
            }
        }
        actions.addView(reminder, LinearLayout.LayoutParams(dip(130), dip(43)))
        actions.addView(space(1), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(label("完成", 17f, 0xFF9B9B9B.toInt(), false).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setOnClickListener
                val repo = (application as PureNoteApp).repository
                scope.launch {
                    repo.quickAddTodo(text, quickDueAt)
                    composerOpen = false
                    quickDueAt = null
                    postToMain { Toast.makeText(this@QuickCaptureService, "已添加待办", Toast.LENGTH_SHORT).show() }
                    loadAndRenderPanel()
                }
            }
        }, LinearLayout.LayoutParams(dip(72), dip(43)))
        box.addView(actions)
        input.post {
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        return box
    }

    private fun openNewNote() {
        hidePanel()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_NEW_NOTE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun openNote(id: Long) {
        hidePanel()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(Reminders.EXTRA_ID, id)
                putExtra(Reminders.EXTRA_KIND, Reminders.KIND_NOTE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        composerOpen = false
        quickDueAt = null
        showHandle()
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = true): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cardText(text: String, sizeSp: Float, color: Int, heightDp: Int): TextView =
        label(text, sizeSp, color, false).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 18f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(heightDp))
        }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dip(radiusDp).toFloat()
        setColor(color)
    }

    private fun space(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dip(dp))
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp))

    private fun dip(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun dip(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun postToMain(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private class BackFrame(context: Context) : FrameLayout(context) {
        var onBack: (() -> Unit)? = null

        override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBack?.invoke()
                return true
            }
            return super.dispatchKeyEventPreIme(event)
        }
    }

    companion object {
        const val CHANNEL_ID = "quick_capture"
        const val NOTIFICATION_ID = 42

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickCaptureService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "速记", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "保持屏幕边缘速记侧栏可用"
                    setShowBadge(false)
                },
            )
        }
    }
}
