package com.odininputmirror.domain.model

/**
 * What the wizard is asking the daemon to listen for.
 *
 * The same physical event means different things depending on this: an axis is a button when a
 * button was asked for, and half of a stick when a stick was. Asking, rather than guessing from what
 * arrives, is what lets a d-pad fill a stick slot and an analog trigger fill a button slot.
 */
enum class CaptureKind { BUTTON, STICK }

/** One control the user pressed, as the daemon saw it on the grabbed controller. */
sealed interface CaptureResult {
    data class Button(val code: Int) : CaptureResult

    /** A control the user's pad reports as an axis where a button was asked for (a trigger). */
    data class AxisButton(val code: Int, val sign: Int) : CaptureResult

    /** Both axes of a stick, captured from one gesture: the user rolls it and two axes move. */
    data class Stick(val axisX: Int, val axisY: Int, val signX: Int, val signY: Int) : CaptureResult
}

/**
 * A read of the capture log.
 *
 * The log is append-only and the caller carries its own [offset], so a fast double press cannot be
 * lost between polls the way it would be if the daemon rewrote a single-value file.
 */
data class CaptureRead(
    val results: List<CaptureResult> = emptyList(),
    val offset: Long = 0L,
)
