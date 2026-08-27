package com.purenote.local.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.purenote.local.MainActivity
import com.purenote.local.PureNoteApp
import com.purenote.local.R
import com.purenote.local.data.DataChanges
import com.purenote.local.data.Note
import com.purenote.local.data.NoteFilter
import com.purenote.local.data.RepeatRule
import com.purenote.local.data.SortOrder
import com.purenote.local.data.Todo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 跨应用速记侧栏。右缘窄把手点按或向左滑动后打开全屏毛玻璃面板，
 * 可浏览最近笔记、勾选待办、内联新增待办或跳转到完整笔记编辑器。
 */
class QuickCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wm: WindowManager
    private var handle: View? = null
    private var panel: View? = null
    private var panelLoading = false
    private var panelScroll: ScrollView? = null
    private var panelScrollY = 0
    private var dismissingPanel = false
    private var inlineEditorDraft: OverlayTodoDraft? = null
    private var inlineSaveRevision = 0L
    private val inlineSaveMutex = Mutex()
    private var overlayDraftKeyCounter = 0L
    private var registeredBackDispatcher: Any? = null
    private var registeredBackCallback: Any? = null
    private val collapsedTodoIds = mutableSetOf<Long>()

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
        scheduleInlineEditorSave(immediate = true)
        unregisterPanelBackCallback()
        handle?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        handle = null
        panel = null
        panelLoading = false
    }

    private fun handleParams() = WindowManager.LayoutParams(
        dip(HANDLE_TOUCH_WIDTH_DP),
        dip(HANDLE_HEIGHT_DP),
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 0
        y = (handleTravelRange() * savedHandleYFraction()).roundToInt()
    }

    private fun handleTravelRange(): Int =
        (resources.displayMetrics.heightPixels - dip(HANDLE_HEIGHT_DP)).coerceAtLeast(0)

    private fun savedHandleYFraction(): Float =
        getSharedPreferences(HANDLE_PREFERENCES, Context.MODE_PRIVATE)
            .getFloat(HANDLE_Y_FRACTION, DEFAULT_HANDLE_Y_FRACTION)
            .coerceIn(0f, 1f)

    private fun persistHandleY(y: Int) {
        val travel = handleTravelRange()
        val fraction = if (travel == 0) DEFAULT_HANDLE_Y_FRACTION else y.toFloat() / travel
        getSharedPreferences(HANDLE_PREFERENCES, Context.MODE_PRIVATE).edit {
            putFloat(HANDLE_Y_FRACTION, fraction.coerceIn(0f, 1f))
        }
    }

    private fun showHandle() {
        if (handle != null || !Settings.canDrawOverlays(this)) return
        val edge = FloatingHandleView(this).apply {
            contentDescription = "纯记侧栏，左滑展开，上下拖动位置"
            addView(
                View(this@QuickCaptureService).apply {
                    background = rounded(0xCCFFB800.toInt(), 12f)
                },
                FrameLayout.LayoutParams(
                    dip(HANDLE_VISIBLE_WIDTH_DP),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END,
                ),
            )
            setOnClickListener { showPanel() }
        }
        val params = handleParams()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var startY = 0
        var gestureMode = HANDLE_GESTURE_UNDECIDED
        edge.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.translationX = 0f
                    downX = event.rawX
                    downY = event.rawY
                    startY = params.y
                    gestureMode = HANDLE_GESTURE_UNDECIDED
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (gestureMode == HANDLE_GESTURE_UNDECIDED) {
                        gestureMode = when {
                            -dx > touchSlop && -dx > abs(dy) * 1.15f -> HANDLE_GESTURE_OPEN
                            abs(dy) > touchSlop && abs(dy) > abs(dx) -> HANDLE_GESTURE_MOVE
                            else -> HANDLE_GESTURE_UNDECIDED
                        }
                    }
                    when (gestureMode) {
                        HANDLE_GESTURE_OPEN -> {
                            view.translationX = dx.coerceIn(-dip(HANDLE_SWIPE_PREVIEW_DP).toFloat(), 0f)
                        }
                        HANDLE_GESTURE_MOVE -> {
                            params.y = (startY + dy.roundToInt()).coerceIn(0, handleTravelRange())
                            wm.updateViewLayout(view, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when (gestureMode) {
                        HANDLE_GESTURE_OPEN -> {
                            if (-view.translationX >= dip(HANDLE_OPEN_THRESHOLD_DP)) {
                                showPanel()
                            } else {
                                view.animate().translationX(0f).setDuration(130L).start()
                            }
                        }
                        HANDLE_GESTURE_MOVE -> persistHandleY(params.y)
                        else -> view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (gestureMode == HANDLE_GESTURE_MOVE) persistHandleY(params.y)
                    if (gestureMode == HANDLE_GESTURE_OPEN) {
                        if (-view.translationX >= dip(HANDLE_OPEN_THRESHOLD_DP)) {
                            showPanel()
                        } else {
                            view.animate().translationX(0f).setDuration(130L).start()
                        }
                    }
                    gestureMode = HANDLE_GESTURE_UNDECIDED
                    true
                }
                else -> true
            }
        }
        handle = edge
        wm.addView(edge, params)
    }

    private fun showPanel() {
        if (panel != null || panelLoading) return
        panelLoading = true
        // 数据准备期间保留悬浮柄，面板真正挂载后再移除，避免桌面短暂空闪。
        loadAndRenderPanel(animateIn = true)
    }

    private fun loadAndRenderPanel(animateIn: Boolean = false) {
        val repo = (application as PureNoteApp).repository
        scope.launch {
            runCatching {
                val notes = repo.loadNotes(NoteFilter(), SortOrder.BY_UPDATED).take(5)
                val todos = repo.loadTodos().sortedWith(
                    compareBy<Todo> { it.done }.thenBy { it.sortIndex }.thenByDescending { it.updatedAt },
                )
                notes to todos
            }.onSuccess { (notes, todos) ->
                postToMain { renderPanel(notes, todos, animateIn) }
            }.onFailure {
                postToMain {
                    if (animateIn) panelLoading = false
                    handle?.animate()?.translationX(0f)?.setDuration(130L)?.start()
                }
            }
        }
    }

    private fun renderPanel(notes: List<Note>, todos: List<Todo>, animateIn: Boolean = false) {
        val previousPanel = panel
        val animateOpening = animateIn && previousPanel == null
        panelScrollY = panelScroll?.scrollY ?: panelScrollY
        unregisterPanelBackCallback()
        panelScroll = null
        dismissingPanel = false

        val root = EdgeDismissFrame(this).apply {
            isFocusableInTouchMode = true
            // API 31+ 由系统提供跨窗口虚化；半透明色同时是旧设备/关闭虚化时的降级效果。
            setBackgroundColor(0x786A7682)
            onDismiss = { direction -> dismissPanel(direction) }
            isDismissInProgress = { dismissingPanel }
            if (animateOpening) {
                translationX = resources.displayMetrics.widthPixels.toFloat()
                alpha = 0f
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        panelScroll = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(18), dip(58), dip(18), dip(28))
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        content.addView(noteHeader())
        content.addView(space(18))
        val notesStrip = noteStrip(notes)
        root.excludeHorizontalGesture(notesStrip)
        content.addView(notesStrip)
        content.addView(space(18))
        content.addView(todoHeader())
        content.addView(space(17))

        val rootTodos = todos.filter { !it.isSubtask }
        if (rootTodos.isEmpty()) {
            content.addView(cardText("暂无待办", 17f, 0xFF777777.toInt(), 82))
        } else {
            rootTodos.forEach { todo ->
                val children = todos.filter { it.parentId == todo.id }
                    .sortedWith(compareBy<Todo> { it.sortIndex }.thenBy { it.createdAt })
                content.addView(todoCard(todo, children))
                content.addView(space(10))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            applyGlassBlur(this)
        }
        panel = root
        wm.addView(root, params)
        panelLoading = false
        if (previousPanel == null) {
            handle?.let { runCatching { wm.removeView(it) } }
            handle = null
        }
        // 新面板先覆盖到窗口上，再移除旧面板，避免勾选待办刷新时露出一帧桌面背景。
        previousPanel?.takeIf { it !== root }?.let { old ->
            runCatching { wm.removeView(old) }
        }
        root.requestFocus()
        if (animateOpening) {
            root.post {
                if (panel === root && !dismissingPanel) {
                    root.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(PANEL_ENTER_DURATION_MS)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withLayer()
                        .start()
                }
            }
        }
        scroll.post { scroll.scrollTo(0, panelScrollY) }
        when {
            Build.VERSION.SDK_INT >= 34 -> registerAnimatedPredictiveBack(root)
            Build.VERSION.SDK_INT >= 33 -> registerPredictiveBack(root)
        }
    }

    private fun noteHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(plusButton { openNewNote() }, LinearLayout.LayoutParams(dip(38), dip(38)))
        row.addView(label("笔记 ︿", 18f, Color.WHITE, bold = false).apply {
            setPadding(dip(13), 0, 0, 0)
            setOnClickListener { dismissPanel(1f) }
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
        row.addView(plusButton { startInlineTodoEditor(null) }, LinearLayout.LayoutParams(dip(38), dip(38)))
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

    private fun todoCard(todo: Todo, children: List<Todo>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 17f)
            elevation = dip(1).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dip(21), 0, dip(18), 0)
        }
        val check = NativeTodoCheckbox(this, todo.done, 22f).apply {
            setOnClickListener { toggleTodoFromPanel(todo) }
        }
        header.addView(check, LinearLayout.LayoutParams(dip(38), dip(76)))
        val title = label(
            todo.title.ifBlank { "待办清单" },
            18f,
            if (todo.done) 0xFFC4C4C4.toInt() else 0xFF171717.toInt(),
            false,
        ).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (todo.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setOnClickListener { startInlineTodoEditor(todo.id) }
            contentDescription = "编辑待办：${todo.title}"
        }
        header.addView(title, LinearLayout.LayoutParams(0, dip(76), 1f))

        if (children.isNotEmpty()) {
            val doneCount = children.count { it.done }
            val expandControl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = if (todo.id in collapsedTodoIds) "展开子待办" else "收起子待办"
            }
            expandControl.addView(label("$doneCount/${children.size}", 14f, 0xFF777777.toInt(), false).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dip(48), dip(76)))
            val arrow = label(if (todo.id in collapsedTodoIds) "›" else "⌄", 19f, 0xFF888888.toInt(), false).apply {
                gravity = Gravity.CENTER
            }
            expandControl.addView(arrow, LinearLayout.LayoutParams(dip(29), dip(76)))
            header.addView(expandControl, LinearLayout.LayoutParams(dip(77), dip(76)))

            val childrenBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isVisible = todo.id !in collapsedTodoIds
            }
            children.forEach { child ->
                childrenBox.addView(View(this).apply {
                    setBackgroundColor(0xFFF0F0F0.toInt())
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(1)).apply {
                    marginStart = dip(50)
                })
                childrenBox.addView(subTodoRow(child))
            }
            expandControl.setOnClickListener {
                val collapsing = childrenBox.isVisible
                childrenBox.isVisible = !collapsing
                arrow.text = if (collapsing) "›" else "⌄"
                expandControl.contentDescription = if (collapsing) "展开子待办" else "收起子待办"
                if (collapsing) collapsedTodoIds.add(todo.id) else collapsedTodoIds.remove(todo.id)
            }
            card.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(76)))
            card.addView(childrenBox)
        } else {
            card.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(76)))
        }
        return card
    }

    private fun subTodoRow(todo: Todo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dip(49), 0, dip(18), 0)
        }
        row.addView(NativeTodoCheckbox(this, todo.done, 17f).apply {
            setOnClickListener { toggleTodoFromPanel(todo) }
        }, LinearLayout.LayoutParams(dip(34), dip(54)))
        row.addView(label(todo.title, 15.5f, if (todo.done) 0xFFC4C4C4.toInt() else 0xFF555555.toInt(), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (todo.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setOnClickListener { startInlineTodoEditor(todo.parentId ?: todo.id, todo.id) }
            contentDescription = "编辑子待办：${todo.title}"
        }, LinearLayout.LayoutParams(0, dip(54), 1f))
        return row
    }

    private fun toggleTodoFromPanel(todo: Todo) {
        val repo = (application as PureNoteApp).repository
        scope.launch {
            val markingDone = !todo.done
            repo.setTodoDone(todo, markingDone)
            DataChanges.notifyChanged()
            loadAndRenderPanel()
        }
    }

    /** 在悬浮层内部打开待办编辑器，不再启动 MainActivity。 */
    private fun startInlineTodoEditor(todoId: Long?, focusSubId: Long? = null) {
        val repo = (application as PureNoteApp).repository
        scope.launch {
            val todo = todoId?.let { repo.getTodo(it) }
            val children = if (todo == null) emptyList() else {
                repo.loadTodos()
                    .filter { it.parentId == todo.id }
                    .sortedWith(compareBy<Todo> { it.sortIndex }.thenBy { it.createdAt })
            }
            val draft = OverlayTodoDraft(
                id = todo?.id ?: -1L,
                title = todo?.title.orEmpty(),
                done = todo?.done ?: false,
                dueAt = todo?.dueAt,
                allDay = todo?.allDay ?: false,
                repeat = todo?.repeat ?: RepeatRule.NONE,
                subs = children.mapTo(mutableListOf()) {
                    OverlaySubDraft(
                        key = nextOverlayDraftKey(),
                        sourceId = it.id,
                        text = it.title,
                        done = it.done,
                    )
                },
            )
            postToMain {
                inlineEditorDraft = draft
                renderInlineTodoEditor(draft, focusSubId)
            }
        }
    }

    private fun renderInlineTodoEditor(draft: OverlayTodoDraft, focusSubId: Long? = null) {
        val previousPanel = panel
        unregisterPanelBackCallback()
        panelScroll = null
        dismissingPanel = false

        val closeEditor: () -> Unit = {
            scheduleInlineEditorSave(immediate = true)
            dismissPanel(1f)
        }

        val root = EdgeDismissFrame(this).apply {
            isFocusableInTouchMode = true
            setBackgroundColor(0x735F6872)
            onDismiss = { closeEditor() }
            isDismissInProgress = { dismissingPanel }
        }

        // 点击编辑卡片以外的灰色区域直接保存并退出，避免悬浮编辑器把用户困住。
        root.addView(View(this).apply {
            isClickable = true
            contentDescription = "关闭待办编辑器"
            setOnClickListener { closeEditor() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val sheetScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            background = rounded(Color.WHITE, 25f)
            isClickable = true
            contentDescription = "待办编辑区域，点击空白处关闭"
            setOnClickListener { closeEditor() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(24), dip(20), dip(18))
        }
        sheetScroll.addView(
            sheet,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val parentCheck = NativeTodoCheckbox(this, draft.done, 22f).apply {
            setOnClickListener {
                draft.done = !draft.done
                setChecked(draft.done)
                draft.subs.forEach { it.done = draft.done }
                scheduleInlineEditorSave()
            }
        }
        titleRow.addView(parentCheck, LinearLayout.LayoutParams(dip(40), dip(55)))

        fun syncParentCheckFromSubs() {
            val materialSubs = draft.subs.filter { it.text.isNotBlank() }
            if (materialSubs.isNotEmpty()) {
                draft.done = materialSubs.all { it.done }
                parentCheck.setChecked(draft.done)
            }
        }

        val titleInput = inlineEditText(
            text = draft.title,
            hint = "待办清单",
            sizeSp = 18f,
        ).apply {
            imeOptions = EditorInfo.IME_ACTION_NEXT
            doAfterTextChanged {
                draft.title = it?.toString().orEmpty()
                scheduleInlineEditorSave()
            }
        }
        titleRow.addView(titleInput, LinearLayout.LayoutParams(0, dip(55), 1f))
        sheet.addView(titleRow)
        sheet.addView(label("回车后转到第一条子待办", 12.5f, 0xFFBDBDBD.toInt(), false).apply {
            setPadding(dip(40), 0, 0, dip(5))
        })

        val subsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(subsContainer)

        lateinit var rebuildSubRows: (Long?) -> Unit
        rebuildSubRows = { focusKey ->
            subsContainer.removeAllViews()
            var focusInput: EditText? = null

            draft.subs.forEachIndexed { index, sub ->
                if (index > 0) {
                    subsContainer.addView(View(this).apply {
                        setBackgroundColor(0xFFF0F0F0.toInt())
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(1)).apply {
                        marginStart = dip(40)
                    })
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val check = NativeTodoCheckbox(this, sub.done, 18f).apply {
                    setOnClickListener {
                        sub.done = !sub.done
                        setChecked(sub.done)
                        syncParentCheckFromSubs()
                        scheduleInlineEditorSave()
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(dip(40), dip(54)))

                val input = inlineEditText(sub.text, "待办内容", 15.5f).apply {
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    doAfterTextChanged {
                        sub.text = it?.toString().orEmpty()
                        syncParentCheckFromSubs()
                        scheduleInlineEditorSave()
                    }
                    setOnEditorActionListener { _, actionId, event ->
                        val enter = actionId == EditorInfo.IME_ACTION_NEXT ||
                            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                        if (!enter) return@setOnEditorActionListener false
                        val current = draft.subs.indexOfFirst { it.key == sub.key }.coerceAtLeast(0)
                        val next = OverlaySubDraft(nextOverlayDraftKey(), null, "", false)
                        draft.subs.add(current + 1, next)
                        syncParentCheckFromSubs()
                        rebuildSubRows(next.key)
                        scheduleInlineEditorSave(immediate = true)
                        true
                    }
                }
                if (sub.key == focusKey) focusInput = input
                row.addView(input, LinearLayout.LayoutParams(0, dip(54), 1f))
                row.addView(label("×", 21f, 0xFFCCCCCC.toInt(), false).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "删除子待办"
                    setOnClickListener {
                        draft.subs.removeAll { it.key == sub.key }
                        syncParentCheckFromSubs()
                        rebuildSubRows(null)
                        scheduleInlineEditorSave(immediate = true)
                    }
                }, LinearLayout.LayoutParams(dip(34), dip(54)))
                subsContainer.addView(row)
            }

            subsContainer.addView(label("＋  添加子待办", 15f, 0xFFB98200.toInt(), false).apply {
                setPadding(dip(4), dip(13), 0, dip(14))
                setOnClickListener {
                    val next = OverlaySubDraft(nextOverlayDraftKey(), null, "", false)
                    draft.subs.add(next)
                    syncParentCheckFromSubs()
                    rebuildSubRows(next.key)
                }
            })

            focusInput?.let { target ->
                target.post {
                    target.requestFocus()
                    target.setSelection(target.text.length)
                    showKeyboard(target)
                }
            }
        }

        titleInput.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_NEXT ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (!enter) return@setOnEditorActionListener false
            val first = draft.subs.firstOrNull()
                ?: OverlaySubDraft(nextOverlayDraftKey(), null, "", false).also { draft.subs.add(it) }
            syncParentCheckFromSubs()
            rebuildSubRows(first.key)
            scheduleInlineEditorSave(immediate = true)
            true
        }

        val initialFocusKey = focusSubId?.let { requested ->
            draft.subs.firstOrNull { it.sourceId == requested }?.key
        }
        rebuildSubRows(initialFocusKey)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(4), 0, 0)
        }
        val reminder = label(
            if (draft.dueAt == null) "◴  设置提醒" else "◴  已设置提醒",
            13.5f,
            0xFF777777.toInt(),
            false,
        ).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFF0F0F0.toInt(), 12f)
            setOnClickListener {
                if (draft.dueAt == null) {
                    draft.dueAt = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 9)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                    }.timeInMillis
                    text = "◴  已设置提醒"
                } else {
                    draft.dueAt = null
                    text = "◴  设置提醒"
                }
                scheduleInlineEditorSave(immediate = true)
            }
        }
        footer.addView(reminder, LinearLayout.LayoutParams(dip(126), dip(40)))
        footer.addView(space(1), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(label("自动保存", 13f, 0xFFAAAAAA.toInt(), false).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dip(72), dip(40)))
        sheet.addView(footer)

        val sheetHeight = (resources.displayMetrics.heightPixels * 0.64f).toInt()
        root.addView(
            sheetScroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, sheetHeight, Gravity.BOTTOM),
        )
        root.addView(View(this).apply {
            background = rounded(0xFFFFB800.toInt(), 12f)
        }, FrameLayout.LayoutParams(dip(8), dip(88), Gravity.END or Gravity.CENTER_VERTICAL))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            applyGlassBlur(this)
        }
        panel = root
        wm.addView(root, params)
        // 保持旧侧栏作为过渡底层，直到编辑器已经成功挂载，避免切换时背景闪白。
        previousPanel?.takeIf { it !== root }?.let { old ->
            runCatching { wm.removeView(old) }
        }
        root.requestFocus()
        when {
            Build.VERSION.SDK_INT >= 34 -> registerAnimatedPredictiveBack(root)
            Build.VERSION.SDK_INT >= 33 -> registerPredictiveBack(root)
        }

        if (initialFocusKey == null) {
            titleInput.post {
                titleInput.requestFocus()
                titleInput.setSelection(titleInput.text.length)
                showKeyboard(titleInput)
            }
        }
    }

    private fun inlineEditText(text: String, hint: String, sizeSp: Float): EditText = EditText(this).apply {
        setText(text)
        this.hint = hint
        setTextSize(sizeSp)
        setTextColor(0xFF171717.toInt())
        setHintTextColor(0xFFCBCBCB.toInt())
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, 0, 0, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun showKeyboard(view: View?) {
        if (view == null) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun scheduleInlineEditorSave(immediate: Boolean = false) {
        val draft = inlineEditorDraft ?: return
        val revision = ++inlineSaveRevision
        scope.launch {
            if (!immediate) delay(350)
            if (!immediate && revision != inlineSaveRevision) return@launch
            inlineSaveMutex.withLock { persistInlineEditor(draft) }
        }
    }

    private suspend fun persistInlineEditor(draft: OverlayTodoDraft) {
        val title = draft.title.trim()
        val cleanSubs = draft.subs.filter { it.text.isNotBlank() }.map { it.text.trim() to it.done }
        if (draft.id <= 0 && title.isBlank() && cleanSubs.isEmpty()) return

        val repo = (application as PureNoteApp).repository
        val finalTitle = title.ifBlank { "待办清单" }
        if (draft.id <= 0) {
            draft.id = repo.createTodo(null, finalTitle, draft.dueAt, draft.allDay, draft.repeat.ordinal)
        } else {
            repo.updateTodo(draft.id, finalTitle, draft.dueAt, draft.allDay, draft.repeat.ordinal)
        }
        repo.replaceSubs(draft.id, cleanSubs)
        if (cleanSubs.isEmpty()) {
            // 普通单项待办由标题前的勾选框决定状态。
            repo.getTodo(draft.id)?.let { saved ->
                if (saved.done != draft.done) repo.setTodoDone(saved, draft.done)
            }
        } else {
            // 清单父项状态由子项推导，不能再用旧 draft 状态把未完成子项重新全部勾上。
            draft.done = cleanSubs.all { it.second }
        }
        if (draft.dueAt == null) {
            Reminders.cancel(this, Reminders.KIND_TODO, draft.id)
        } else {
            Reminders.schedule(this, Reminders.KIND_TODO, draft.id, draft.dueAt!!)
        }
        DataChanges.notifyChanged()
    }

    private fun nextOverlayDraftKey(): Long = ++overlayDraftKeyCounter

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

    private fun dismissPanel(direction: Float) {
        if (dismissingPanel) return
        val current = panel ?: return
        dismissingPanel = true
        current.animate().cancel()
        current.isClickable = false
        val width = current.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val target = if (direction >= 0f) width.toFloat() else -width.toFloat()
        current.animate()
            .translationX(target)
            .alpha(0f)
            .setDuration(PANEL_EXIT_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .withEndAction {
                // 先隐藏再从 WindowManager 移除，防止 OEM 在窗口销毁前重绘一次原始位置。
                current.visibility = View.INVISIBLE
                if (panel === current) hidePanel()
            }
            .start()
    }

    @RequiresApi(33)
    private fun registerPredictiveBack(root: View) {
        root.post {
            if (panel !== root) return@post
            unregisterPanelBackCallback()
            val dispatcher = root.findOnBackInvokedDispatcher() ?: return@post
            val callback = android.window.OnBackInvokedCallback {
                scheduleInlineEditorSave(immediate = true)
                dismissPanel(1f)
            }
            dispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback,
            )
            registeredBackDispatcher = dispatcher
            registeredBackCallback = callback
        }
    }

    @RequiresApi(34)
    private fun registerAnimatedPredictiveBack(root: View) {
        root.post {
            if (panel !== root) return@post
            unregisterPanelBackCallback()
            val dispatcher = root.findOnBackInvokedDispatcher() ?: return@post
            val callback = object : android.window.OnBackAnimationCallback {
                    private var direction = 1f

                    override fun onBackStarted(backEvent: android.window.BackEvent) {
                        direction = if (backEvent.swipeEdge == android.window.BackEvent.EDGE_RIGHT) -1f else 1f
                        root.animate().cancel()
                    }

                    override fun onBackProgressed(backEvent: android.window.BackEvent) {
                        root.translationX = direction * root.width * backEvent.progress * 0.34f
                        root.alpha = (1f - backEvent.progress * PANEL_DRAG_FADE).coerceAtLeast(MIN_PANEL_DRAG_ALPHA)
                    }

                    override fun onBackCancelled() {
                        if (dismissingPanel) return
                        val moved = abs(root.translationX)
                        if (moved > root.width * 0.08f) {
                            dismissPanel(direction)
                        } else {
                            root.animate().translationX(0f).alpha(1f).setDuration(120L).start()
                        }
                    }

                    override fun onBackInvoked() {
                        scheduleInlineEditorSave(immediate = true)
                        dismissPanel(direction)
                    }
                }
            dispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback,
            )
            registeredBackDispatcher = dispatcher
            registeredBackCallback = callback
        }
    }

    private fun unregisterPanelBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = registeredBackDispatcher as? android.window.OnBackInvokedDispatcher
        val callback = registeredBackCallback as? android.window.OnBackInvokedCallback
        if (dispatcher != null && callback != null) {
            runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        registeredBackDispatcher = null
        registeredBackCallback = null
    }

    private fun hidePanel() {
        scheduleInlineEditorSave(immediate = true)
        unregisterPanelBackCallback()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelScroll = null
        panelScrollY = 0
        dismissingPanel = false
        inlineEditorDraft = null
        collapsedTodoIds.clear()
        panelLoading = false
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

    /**
     * Android 12 及以上让 WindowManager 对悬浮窗背后的桌面/应用执行跨窗口虚化。
     * 若系统或省电模式关闭了该能力，View 自身的半透明底色仍提供可读的柔和降级层。
     */
    @Suppress("DEPRECATION")
    private fun applyGlassBlur(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 31) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.setBlurBehindRadius(dip(PANEL_BLUR_RADIUS_DP))
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun postToMain(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private data class OverlayTodoDraft(
        var id: Long,
        var title: String,
        var done: Boolean,
        var dueAt: Long?,
        var allDay: Boolean,
        var repeat: RepeatRule,
        val subs: MutableList<OverlaySubDraft>,
    )

    private data class OverlaySubDraft(
        val key: Long,
        val sourceId: Long?,
        var text: String,
        var done: Boolean,
    )

    /** 原生悬浮窗版本的小米待办复选框，与 Compose 主界面的视觉保持一致。 */
    private class NativeTodoCheckbox(
        context: Context,
        checked: Boolean,
        boxSizeDp: Float,
    ) : View(context) {
        private var checked = checked
        private val density = resources.displayMetrics.density
        private val boxSize = boxSizeDp * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val checkPath = Path()

        init {
            isClickable = true
            isFocusable = true
            contentDescription = if (checked) "取消完成" else "标记完成"
        }

        fun setChecked(value: Boolean) {
            if (checked == value) return
            checked = value
            contentDescription = if (checked) "取消完成" else "标记完成"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = (width - boxSize) / 2f
            val top = (height - boxSize) / 2f
            val rect = RectF(left, top, left + boxSize, top + boxSize)
            val radius = 5f * density

            if (checked) {
                paint.style = Paint.Style.FILL
                paint.color = 0xFF111111.toInt()
                canvas.drawRoundRect(rect, radius, radius, paint)

                paint.style = Paint.Style.STROKE
                paint.color = Color.WHITE
                paint.strokeWidth = 1.8f * density
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                checkPath.reset()
                checkPath.moveTo(left + boxSize * 0.25f, top + boxSize * 0.52f)
                checkPath.lineTo(left + boxSize * 0.43f, top + boxSize * 0.69f)
                checkPath.lineTo(left + boxSize * 0.76f, top + boxSize * 0.34f)
                canvas.drawPath(checkPath, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.color = 0xFFD0D0D0.toInt()
                paint.strokeWidth = 1.6f * density
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private class FloatingHandleView(context: Context) : FrameLayout(context) {
        override fun performClick(): Boolean = super.performClick()
    }

    /**
     * 桌面悬浮面板支持从任意空白/卡片区域向右跟手滑动收起。横向笔记卡片和待办
     * 输入框会被排除，以免与它们自己的交互冲突；竖向滚动仍由子视图处理。
     */
    @SuppressLint("ClickableViewAccessibility")
    private class EdgeDismissFrame(context: Context) : FrameLayout(context) {
        var onDismiss: ((Float) -> Unit)? = null
        var isDismissInProgress: (() -> Boolean)? = null

        /** 左侧这一小段完全交给 Android 的系统返回手势，避免两套动画同时抢占。 */
        private val systemBackEdge = 48f * resources.displayMetrics.density
        private val dismissDistance = 68f * resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val horizontalGestureExclusions = mutableListOf<View>()
        private var downX = 0f
        private var downY = 0f
        private var gestureCandidate = false
        private var dragging = false

        fun excludeHorizontalGesture(view: View) {
            horizontalGestureExclusions += view
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    gestureCandidate = event.x > systemBackEdge &&
                        horizontalGestureExclusions.none { isPointInside(it, event.rawX, event.rawY) }
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gestureCandidate) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx > touchSlop && dx > abs(dy) * 1.15f) {
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dx = (event.rawX - downX).coerceAtLeast(0f)
                    translationX = dx
                    val progress = if (width > 0) (dx / width).coerceIn(0f, 1f) else 0f
                    alpha = (1f - progress * PANEL_DRAG_FADE).coerceAtLeast(MIN_PANEL_DRAG_ALPHA)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    val shouldDismiss = translationX >= dismissDistance
                    if (shouldDismiss) {
                        onDismiss?.invoke(1f)
                    } else {
                        animate().translationX(0f).alpha(1f).setDuration(140L).start()
                    }
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        when {
                            isDismissInProgress?.invoke() == true -> Unit
                            translationX > touchSlop * 2f -> onDismiss?.invoke(1f)
                            else -> animate().translationX(0f).alpha(1f).setDuration(140L).start()
                        }
                    }
                    dragging = false
                    return true
                }
            }
            return true
        }

        private fun isPointInside(view: View, rawX: Float, rawY: Float): Boolean {
            if (!view.isShown) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return rawX >= location[0] && rawX < location[0] + view.width &&
                rawY >= location[1] && rawY < location[1] + view.height
        }

        override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onDismiss?.invoke(1f)
                return true
            }
            return super.dispatchKeyEventPreIme(event)
        }
    }

    companion object {
        const val CHANNEL_ID = "quick_capture"
        const val NOTIFICATION_ID = 42

        private const val HANDLE_PREFERENCES = "quick_capture_handle"
        private const val HANDLE_Y_FRACTION = "handle_y_fraction"
        private const val DEFAULT_HANDLE_Y_FRACTION = 0.4f
        private const val HANDLE_HEIGHT_DP = 94
        private const val HANDLE_TOUCH_WIDTH_DP = 22
        private const val HANDLE_VISIBLE_WIDTH_DP = 7
        private const val HANDLE_SWIPE_PREVIEW_DP = 72
        private const val HANDLE_OPEN_THRESHOLD_DP = 26
        private const val HANDLE_GESTURE_UNDECIDED = 0
        private const val HANDLE_GESTURE_OPEN = 1
        private const val HANDLE_GESTURE_MOVE = 2
        private const val PANEL_BLUR_RADIUS_DP = 32
        private const val PANEL_ENTER_DURATION_MS = 230L
        private const val PANEL_EXIT_DURATION_MS = 180L
        private const val PANEL_DRAG_FADE = 0.62f
        private const val MIN_PANEL_DRAG_ALPHA = 0.38f

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
