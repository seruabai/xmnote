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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.purenote.local.MainActivity
import com.purenote.local.PureNoteApp
import com.purenote.local.R
import com.purenote.local.data.NoteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 速记：屏幕右缘窄把手，点按展开快捷面板；可保存速记笔记或快速添加待办。
 * 需要“显示在其他应用上层”权限（由设置页引导授权）。
 */
class QuickCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wm: WindowManager
    private var handle: View? = null
    private var panel: LinearLayout? = null
    private lateinit var input: EditText

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
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("速记把手已就绪")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("速记把手已就绪")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun removeViews() {
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun handleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dip(40),
            dip(88),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (resources.displayMetrics.heightPixels / 5 * 2)
        }

    private fun showHandle() {
        if (handle != null || !Settings.canDrawOverlays(this)) return
        val tv = TextView(this).apply {
            text = "速记"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(24f, 24f, 0f, 0f, 0f, 0f, 24f, 24f)
                setColor(0xCCB96A1B.toInt())
            }
        }
        val params = handleLayoutParams()

        var downRawY = 0f
        var startY = 0
        var moved = false
        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dy) > dip(6)) moved = true
                    params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - v.height)
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
        handle = tv
        wm.addView(tv, params)
    }

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(0xFFF8F4EC.toInt())
                setStroke(px(1), 0x33000000)
            }
        }
        input = EditText(this).apply {
            hint = "随手记点什么…"
            minLines = 3
            gravity = Gravity.TOP
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(15f)
            setTextColor(0xFF221B12.toInt())
            setHintTextColor(0xFF9A9083.toInt())
        }

        val close = TextView(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(0xFF7A7264.toInt())
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { hidePanel() }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        headerRow.addView(close)
        root.addView(headerRow)

        root.addView(
            input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(120)),
        )

        fun actionButton(label: String, tint: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(tint)
                gravity = Gravity.CENTER
                setPadding(px(14), px(8), px(14), px(8))
                background = GradientDrawable().apply {
                    cornerRadius = px(20).toFloat()
                    setColor(0x14000000)
                }
                setOnClickListener { onClick() }
            }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(actionButton("存为待办", 0xFFA44E33.toInt()) { saveText(asTodo = true) })
        row.addView(View(this).apply { minimumWidth = px(10) })
        row.addView(actionButton("存为笔记", 0xFFB96A1B.toInt()) { saveText(asTodo = false) })
        root.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        panel = root
        wm.addView(root, params)
    }

    private fun saveText(asTodo: Boolean) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val repo = (application as PureNoteApp).repository
        scope.launch {
            runCatching {
                if (asTodo) repo.quickAddTodo(text)
                else repo.createNote(NoteKind.TEXT, "", text, emptyList(), emptyList(), 0, null)
            }.onSuccess {
                postToMain { Toast.makeText(this@QuickCaptureService, "已保存", Toast.LENGTH_SHORT).show() }
            }
        }
        input.setText("")
        hidePanel()
    }

    private fun postToMain(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun dip(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val CHANNEL_ID = "quick_capture"
        const val NOTIFICATION_ID = 42

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickCaptureService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "速记",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持速记把手可用的常驻通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
