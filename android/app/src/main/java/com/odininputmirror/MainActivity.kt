package com.odininputmirror

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odininputmirror.ui.MirrorScreen
import com.odininputmirror.ui.MirrorViewModel
import com.odininputmirror.ui.theme.DockingEnhancerTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    // The Compose screen registers how to scroll itself; the right stick feeds it pixel deltas.
    var scrollConsumer: ((Float) -> Unit)? = null

    // The Compose screen registers how to anchor gamepad focus. We invoke it once the window gains
    // focus because FocusRequester.requestFocus() silently no-ops while the window is unfocused —
    // which is why focus never landed on launch until a button press handed it out by default.
    var onWindowFocused: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var stickY = 0f
    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (abs(stickY) >= STICK_DEADZONE) {
                scrollConsumer?.invoke(stickY * SCROLL_SPEED_PX)
                handler.postDelayed(this, SCROLL_FRAME_MS)
            }
        }
    }

    private var hatDirection = 0
    private val hatRepeat = object : Runnable {
        override fun run() {
            if (hatDirection != 0) {
                sendDpadTap(hatDirection)
                handler.postDelayed(this, HAT_REPEAT_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DockingEnhancerTheme {
                val vm: MirrorViewModel = viewModel(factory = MirrorViewModel.factory(applicationContext))
                MirrorScreen(viewModel = vm)
            }
        }
    }

    // Make the app fully gamepad-drivable, the way a handheld user expects:
    //  - The LEFT analog stick moves focus between the interactive controls (push it in any of the
    //    four directions to walk through the cards, switches, and button). Compose auto-scrolls the
    //    focused control into view, so focus navigation also handles most of the scrolling.
    //  - The D-pad (reported as hat axes by most controllers) does the same thing, so either input
    //    works. Both are translated into D-pad key events that Compose's focus system understands.
    //  - The RIGHT analog stick free-scrolls the page. This keeps non-focusable areas — the header
    //    and status text — reachable, and keeps the page scrollable even while the mirror locks
    //    every control except the primary button (when focus navigation has nowhere to go).
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            handleStickScroll(event.getAxisValue(MotionEvent.AXIS_RZ))
            handleFocusNav(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleStickScroll(y: Float) {
        val wasActive = abs(stickY) >= STICK_DEADZONE
        stickY = y
        val isActive = abs(y) >= STICK_DEADZONE
        if (isActive && !wasActive) {
            handler.post(scrollRunnable)
        } else if (!isActive && wasActive) {
            handler.removeCallbacks(scrollRunnable)
        }
    }

    // Drive focus from whichever directional input is engaged — the D-pad hat or the left stick
    // pushed past its (larger) travel threshold. A direction only fires once per "push": it taps
    // immediately, then auto-repeats while held, and resets when the input returns to center.
    private fun handleFocusNav(event: MotionEvent) {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        // Prefer the hat when it is engaged; otherwise read the left stick past its threshold.
        val x = if (abs(hatX) >= 0.5f) hatX else leftX.takeIf { abs(it) >= STICK_FOCUS_THRESHOLD } ?: 0f
        val y = if (abs(hatY) >= 0.5f) hatY else leftY.takeIf { abs(it) >= STICK_FOCUS_THRESHOLD } ?: 0f
        val direction = when {
            y <= -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y >= 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            x <= -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x >= 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
        if (direction == hatDirection) return
        handler.removeCallbacks(hatRepeat)
        hatDirection = direction
        if (direction != 0) {
            sendDpadTap(direction)
            handler.postDelayed(hatRepeat, HAT_INITIAL_MS)
        }
    }

    private fun sendDpadTap(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) onWindowFocused?.invoke()
    }

    // Tell the supervisor whether the device list is being watched. Visible (started) → it keeps
    // enumerating controllers so the UI stays live; hidden (stopped) → undocked, it can skip that
    // privileged PServer call. onStart/onStop (not onResume/onPause) so a transient dialog or the
    // recents overlay doesn't blank the list.
    override fun onStart() {
        super.onStart()
        MirrorStateStore.setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        MirrorStateStore.setUiVisible(false)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(scrollRunnable)
        handler.removeCallbacks(hatRepeat)
        stickY = 0f
        hatDirection = 0
    }

    companion object {
        private const val STICK_DEADZONE = 0.25f
        // Focus navigation needs a firmer push than scrolling so a resting/drifting stick can't
        // skip focus between controls; the scroll deadzone stays loose for smooth scrolling.
        private const val STICK_FOCUS_THRESHOLD = 0.6f
        private const val SCROLL_SPEED_PX = 34f
        private const val SCROLL_FRAME_MS = 16L
        private const val HAT_INITIAL_MS = 350L
        private const val HAT_REPEAT_MS = 140L
    }
}
