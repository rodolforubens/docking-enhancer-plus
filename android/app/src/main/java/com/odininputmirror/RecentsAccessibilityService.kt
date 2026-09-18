package com.odininputmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.odininputmirror.data.RecentsInputChannel
import com.odininputmirror.data.RecentsInputCommand

/**
 * Makes the AYN Launcher3 overview usable with a controller and labels its existing controls.
 *
 * The OEM RecentsView exposes focusable task cards but does not consistently put one into its
 * controller-navigation state when overview opens. Accessibility focus is the least invasive hook
 * that changes that state. The overlay is deliberately non-interactive: Launcher3 continues to own
 * A (resume), X (close), and all D-pad events.
 */
class RecentsAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private var overviewVisible = false
    private var focusAttempts = 0
    private var overlay: View? = null
    private val inputChannel by lazy { RecentsInputChannel(this) }

    private val refreshRunnable = Runnable {
        refreshScheduled = false
        refreshOverview()
    }
    private val focusRunnable = object : Runnable {
        override fun run() {
            if (overviewVisible) {
                val focused = findOverview()?.let(::focusCenterTask) == true
                if (!focused && ++focusAttempts < MAX_FOCUS_ATTEMPTS) {
                    handler.postDelayed(this, FOCUS_RETRY_MS)
                }
            }
        }
    }
    private val navigationRunnable = object : Runnable {
        override fun run() {
            if (!overviewVisible) return
            inputChannel.readPending().forEach(::handleNavigationCommand)
            handler.postDelayed(this, NAVIGATION_POLL_MS)
        }
    }

    override fun onServiceConnected() {
        inputChannel.deactivate()
        scheduleRefresh(0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Recents can report the package shown in its centred task instead of Launcher3. This is
        // also our package when overview opens from Docking Enhancer, so every event must trigger
        // a fresh window-tree check. showOverlay() is idempotent, which keeps its own event finite.
        scheduleRefresh(if (event.packageName?.toString() == LAUNCHER_PACKAGE) 180L else 0L)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!overviewVisible || !event.isControllerEvent()) return false

        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> event.runOnce { moveTaskFocus(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> event.runOnce { moveTaskFocus(1) }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> event.runOnce(::resumeFocusedTask)
            KeyEvent.KEYCODE_BUTTON_X -> event.runOnce(::dismissFocusedTask)
            else -> false
        }
        if (handled) {
            cancelInitialFocusRetries()
        }
        return handled
    }

    override fun onInterrupt() {
        handler.removeCallbacks(focusRunnable)
        handler.removeCallbacks(navigationRunnable)
        overviewVisible = false
        inputChannel.deactivate()
        hideOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        inputChannel.deactivate()
        hideOverlay()
        super.onDestroy()
    }

    private fun scheduleRefresh(delayMs: Long) {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun refreshOverview() {
        val overview = findOverview()
        if (overview == null) {
            handler.removeCallbacks(focusRunnable)
            handler.removeCallbacks(navigationRunnable)
            overviewVisible = false
            focusAttempts = 0
            inputChannel.deactivate()
            hideOverlay()
            return
        }

        if (!overviewVisible) {
            overviewVisible = true
            focusAttempts = 0
            handler.removeCallbacks(focusRunnable)
            handler.post(focusRunnable)
            inputChannel.activate()
            handler.removeCallbacks(navigationRunnable)
            handler.post(navigationRunnable)
        }
        showOverlay()
    }

    private fun findOverview(): AccessibilityNodeInfo? {
        for (window: AccessibilityWindowInfo in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != LAUNCHER_PACKAGE) continue
            val overview = root
                .findAccessibilityNodeInfosByViewId(OVERVIEW_VIEW_ID)
                .firstOrNull { it.isVisibleToUser }
            if (overview != null) return overview
        }
        return null
    }

    private fun focusCenterTask(overview: AccessibilityNodeInfo): Boolean {
        val overviewBounds = Rect().also(overview::getBoundsInScreen)
        val candidates = taskCandidates(overview)
        val best = candidates.minByOrNull { candidate ->
            val bounds = Rect().also(candidate::getBoundsInScreen)
            val dx = bounds.centerX().toLong() - overviewBounds.centerX()
            val dy = bounds.centerY().toLong() - overviewBounds.centerY()
            dx * dx + dy * dy
        } ?: return false

        val inputFocused = best.isFocused || best.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!best.isAccessibilityFocused) {
            best.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
        // AccessibilityNodeInfo is a snapshot and does not refresh isFocused after performAction.
        // The action's return value is Android's acknowledgement that focus was accepted.
        return inputFocused
    }

    private fun moveTaskFocus(direction: Int) {
        val overview = findOverview() ?: return
        val candidates = taskCandidates(overview)
            .sortedBy { Rect().also(it::getBoundsInScreen).centerX() }
        if (candidates.isEmpty()) return

        val overviewBounds = Rect().also(overview::getBoundsInScreen)
        val currentIndex = candidates.indexOfFirst { it.isFocused || it.isAccessibilityFocused }
            .takeIf { it >= 0 }
            ?: candidates.indices.minBy { index ->
                kotlin.math.abs(
                    Rect().also(candidates[index]::getBoundsInScreen).centerX() - overviewBounds.centerX(),
                )
            }
        val targetIndex = (currentIndex + direction).coerceIn(candidates.indices)
        if (targetIndex != currentIndex) {
            focusTask(candidates[targetIndex])
        } else {
            val scrollAction = if (direction > 0) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (overview.performAction(scrollAction)) {
                handler.postDelayed({ findOverview()?.let(::focusCenterTask) }, FOCUS_RETRY_MS)
            }
        }
    }

    private fun handleNavigationCommand(command: RecentsInputCommand) {
        cancelInitialFocusRetries()
        when (command) {
            RecentsInputCommand.LEFT -> moveTaskFocus(-1)
            RecentsInputCommand.RIGHT -> moveTaskFocus(1)
            RecentsInputCommand.RESUME -> resumeFocusedTask()
            RecentsInputCommand.CLOSE -> dismissFocusedTask()
        }
    }

    private fun cancelInitialFocusRetries() {
        // A late initial-focus retry must never recenter the carousel after the user's first
        // navigation command. That was the tiny right-then-left movement seen on entry.
        handler.removeCallbacks(focusRunnable)
        focusAttempts = MAX_FOCUS_ATTEMPTS
    }

    private fun resumeFocusedTask() {
        val overview = findOverview() ?: return
        currentTask(overview)?.let(::tapTask)
    }

    private fun dismissFocusedTask() {
        val overview = findOverview() ?: return
        val task = currentTask(overview) ?: return
        val closeAction = task.actionList.firstOrNull {
            it.label?.toString()?.equals(CLOSE_ACTION_LABEL, ignoreCase = true) == true
        }
        val closed = closeAction?.let { task.performAction(it.id) } == true ||
            task.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
        if (!closed) {
            swipeTaskAway(task)
        }
        handler.postDelayed({ findOverview()?.let(::focusCenterTask) }, FOCUS_RETRY_MS)
    }

    private fun currentTask(overview: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = taskCandidates(overview)
        return candidates.firstOrNull { it.isFocused || it.isAccessibilityFocused }
            ?: run {
                val overviewBounds = Rect().also(overview::getBoundsInScreen)
                candidates.minByOrNull { candidate ->
                    val bounds = Rect().also(candidate::getBoundsInScreen)
                    kotlin.math.abs(bounds.centerX() - overviewBounds.centerX())
                }
            }
    }

    private fun taskCandidates(overview: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        buildList { collectFocusableTasks(overview, this) }

    private fun focusTask(task: AccessibilityNodeInfo) {
        if (!task.isFocused) task.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!task.isAccessibilityFocused) {
            task.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
    }

    private fun tapTask(task: AccessibilityNodeInfo) {
        val bounds = Rect().also(task::getBoundsInScreen)
        if (bounds.isEmpty) return
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build(),
            null,
            null,
        )
    }

    private fun swipeTaskAway(task: AccessibilityNodeInfo) {
        val bounds = Rect().also(task::getBoundsInScreen)
        if (bounds.isEmpty) return
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            lineTo(bounds.exactCenterX(), bounds.top.toFloat())
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, DISMISS_DURATION_MS))
                .build(),
            null,
            null,
        )
    }

    private fun KeyEvent.isControllerEvent(): Boolean =
        source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_JOYSTICK) != 0

    private inline fun KeyEvent.runOnce(block: () -> Unit): Boolean {
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) block()
        return true
    }

    private fun collectFocusableTasks(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isVisibleToUser && node.isFocusable && node.className == TASK_VIEW_CLASS) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() >= dp(MIN_TASK_WIDTH_DP) && bounds.height() >= dp(MIN_TASK_HEIGHT_DP)) {
                output += node
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectFocusableTasks(it, output) }
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val legend = buildLegend()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(OVERLAY_SIDE_MARGIN_DP)
            y = dp(OVERLAY_BOTTOM_MARGIN_DP)
            title = "Docking Enhancer Recents controls"
        }

        runCatching { getSystemService(WindowManager::class.java).addView(legend, params) }
            .onSuccess { overlay = legend }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }

    private fun buildLegend(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = roundedBackground(
                color = Color.argb(232, 10, 16, 24),
                strokeColor = Color.rgb(65, 82, 105),
                radiusDp = 8,
            )
            elevation = dp(4).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(legendRow(R.drawable.controller_button_a, "Resume"))
            addView(
                legendRow(R.drawable.controller_button_x, "Close"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(7)
                },
            )
        }
    }

    private fun legendRow(@DrawableRes buttonIcon: Int, label: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(ImageView(context).apply {
                setImageResource(buttonIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(KEY_SIZE_DP), dp(KEY_SIZE_DP)))

            addView(TextView(context).apply {
                text = label
                setTextColor(Color.rgb(237, 243, 250))
                textSize = 14f
                setSingleLine(true)
                minWidth = dp(LABEL_MIN_WIDTH_DP)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(10)
            })
        }
    }

    private fun roundedBackground(color: Int, strokeColor: Int, radiusDp: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LAUNCHER_PACKAGE = "com.android.launcher3"
        const val CLOSE_ACTION_LABEL = "Close"
        const val OVERVIEW_VIEW_ID = "$LAUNCHER_PACKAGE:id/overview_panel"
        const val TASK_VIEW_CLASS = "android.widget.FrameLayout"
        const val MAX_FOCUS_ATTEMPTS = 5
        const val FOCUS_RETRY_MS = 160L
        const val TAP_DURATION_MS = 50L
        const val DISMISS_DURATION_MS = 220L
        const val NAVIGATION_POLL_MS = 24L
        const val MIN_TASK_WIDTH_DP = 180
        const val MIN_TASK_HEIGHT_DP = 120
        const val KEY_SIZE_DP = 26
        const val LABEL_MIN_WIDTH_DP = 60
        const val OVERLAY_SIDE_MARGIN_DP = 22
        const val OVERLAY_BOTTOM_MARGIN_DP = 56
    }
}
