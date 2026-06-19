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

        return displayManager.displays.any { display ->
            display.displayId != Display.DEFAULT_DISPLAY && display.state != Display.STATE_OFF
        }
    }
}
