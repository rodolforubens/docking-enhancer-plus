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
    // The Compose screen registers how to scroll itself; the left stick feeds it pixel deltas.
    var scrollConsumer: ((Float) -> Unit)? = null

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

    // Make the app fully gamepad-drivable: the left analog stick free-scrolls the page (so even
    // non-focusable areas like the header are reachable, and everything stays reachable while the
    // mirror locks most controls), while the D-pad moves focus between the interactive controls.
    // Most controllers report the physical D-pad as hat axes, so translate those into D-pad key
    // events that Compose's focus system understands.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            handleStickScroll(event.getAxisValue(MotionEvent.AXIS_Y))
            handleHatFocus(event)
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

    private fun handleHatFocus(event: MotionEvent) {
        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
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

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(scrollRunnable)
        handler.removeCallbacks(hatRepeat)
        stickY = 0f
        hatDirection = 0
    }

    companion object {
        private const val STICK_DEADZONE = 0.25f
        private const val SCROLL_SPEED_PX = 34f
        private const val SCROLL_FRAME_MS = 16L
        private const val HAT_INITIAL_MS = 350L
        private const val HAT_REPEAT_MS = 140L
    }
}
