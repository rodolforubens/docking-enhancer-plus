package com.odininputmirror.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.odininputmirror.R

/**
 * PromptFont, the controller-glyph font bundled at `res/font/promptfont.ttf`.
 *
 * See `third-party/promptfont/NOTICE.md` — the attribution notice there is a condition of the licence.
 */
@Composable
fun promptFontFamily(): FontFamily = remember { FontFamily(Font(R.font.promptfont)) }

/**
 * The glyphs this app uses, taken from the release's `glyphs.json` rather than guessed: they are the
 * entries tagged `xbox` and `analog`, which is what the mirror's target reports itself to be.
 *
 * Written as escapes rather than as the characters themselves for two reasons. The codepoints are
 * ordinary BMP characters the font remaps, not private-use ones, so pasted literally every one of
 * these looks like an unrelated arrow — unreadable in a diff and impossible to check. And spelling
 * out the number lets the glyph's real name sit beside it.
 */
object PromptGlyph {
    // Face buttons. evdev's BTN_NORTH is the TOP button and BTN_WEST the LEFT one, which on an Xbox
    // pad are Y and X — the reverse of the Nintendo lettering, and easy to wire up backwards.
    const val A = "\u21D3"              // xbox-a
    const val B = "\u21D2"              // xbox-b
    const val X = "\u21D0"              // xbox-x
    const val Y = "\u21D1"              // xbox-y

    const val LEFT_BUMPER = "\u2198"    // xbox-left-shoulder
    const val RIGHT_BUMPER = "\u2199"   // xbox-right-shoulder
    const val LEFT_TRIGGER = "\u2196"   // xbox-left-trigger
    const val RIGHT_TRIGGER = "\u2197"  // xbox-right-trigger

    const val DPAD_UP = "\u227B"        // xbox-dpad-up
    const val DPAD_DOWN = "\u227D"      // xbox-dpad-down
    const val DPAD_LEFT = "\u227A"      // xbox-dpad-left
    const val DPAD_RIGHT = "\u227C"     // xbox-dpad-right

    const val LEFT_STICK_UP = "\u21BE"     // analog-l-up
    const val LEFT_STICK_DOWN = "\u21C2"   // analog-l-down
    const val LEFT_STICK_LEFT = "\u21BC"   // analog-l-left
    const val LEFT_STICK_RIGHT = "\u21C0"  // analog-l-right
    const val LEFT_STICK_CLICK = "\u21BA"  // analog-l-click

    const val RIGHT_STICK_UP = "\u21BF"     // analog-r-up
    const val RIGHT_STICK_DOWN = "\u21C3"   // analog-r-down
    const val RIGHT_STICK_LEFT = "\u21BD"   // analog-r-left
    const val RIGHT_STICK_RIGHT = "\u21C1"  // analog-r-right
    const val RIGHT_STICK_CLICK = "\u21BB"  // analog-r-click

    const val VIEW = "\u21FA"           // xbox-view
    const val MENU = "\u21FB"           // xbox-menu
}
