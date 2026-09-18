package com.odininputmirror.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.odininputmirror.domain.repository.DockStateRepository

internal class AndroidDisplayDockStateRepository(
    context: Context,
    private val forceDockMode: Boolean = false,
) : DockStateRepository {
    private val displayManager = context.applicationContext.getSystemService(DisplayManager::class.java)

    override fun isDockActive(): Boolean {
        if (forceDockMode) {
            return true
        }

        return displayManager.displays.any(::isActiveExternalDisplay)
    }
}

private val EXTERNAL_DISPLAY_NAME =
    Regex("(?:\\bdp\\b|display\\s*port|hdmi|external)", RegexOption.IGNORE_CASE)

/**
 * The Thor exposes its lower built-in panel as a secondary presentation display named `Screen-2`.
 * Counting every non-default display therefore leaves it permanently docked. Its USB-C video output
 * is exposed as `DP Screen`; common Android HDMI outputs also identify themselves by name.
 */
internal fun isActiveExternalDisplay(display: Display): Boolean =
    display.displayId != Display.DEFAULT_DISPLAY &&
        display.state != Display.STATE_OFF &&
        isExternalDisplayIdentity(display.name.orEmpty())

internal fun isExternalDisplayIdentity(name: String): Boolean =
    EXTERNAL_DISPLAY_NAME.containsMatchIn(name)
