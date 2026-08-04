package com.odininputmirror

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * The supervisor's between-tick sleep, and the wake the UI sends it.
 *
 * The bug these guard against: the wake used to be delivered with a bare `notifyAll`, which reaches
 * nobody unless a thread is already inside `wait`. The flip lands while the supervisor is off doing
 * its PServer call far more often than not, so the wake was routinely dropped and the screen sat on
 * an empty device list for the whole idle interval.
 */
class MirrorStateStoreTest {

    @Before
    fun reset() {
        // Shared object, so each test starts from hidden and drains anything a previous test left.
        MirrorStateStore.setUiVisible(false)
        MirrorStateStore.awaitNextTick(1L)
    }

    @Test
    fun `a flip that lands before the wait is not lost`() {
        // Exactly the losing interleaving: the supervisor is still mid-tick, so nobody is waiting.
        MirrorStateStore.setUiVisible(true)

        val elapsed = measureTimeMillis { MirrorStateStore.awaitNextTick(SLEEP_MS) }

        assertTrue("slept ${elapsed}ms, so the pending wake was dropped", elapsed < SLEEP_MS / 2)
    }

    @Test
    fun `a flip delivered while already waiting still cuts the wait short`() {
        val waiter = thread { MirrorStateStore.awaitNextTick(SLEEP_MS) }
        Thread.sleep(150L)

        MirrorStateStore.setUiVisible(true)
        waiter.join(SLEEP_MS / 2)

        assertTrue("the waiter was never woken", !waiter.isAlive)
    }

    @Test
    fun `a pending wake is consumed once, not replayed`() {
        MirrorStateStore.setUiVisible(true)
        MirrorStateStore.awaitNextTick(SLEEP_MS)

        // A second sleep has no wake owed to it and must run its full course.
        val elapsed = measureTimeMillis { MirrorStateStore.awaitNextTick(SHORT_SLEEP_MS) }

        assertTrue("returned after ${elapsed}ms; the wake was replayed", elapsed >= SHORT_SLEEP_MS - GRACE_MS)
    }

    @Test
    fun `staying visible is not a new wake`() {
        MirrorStateStore.setUiVisible(true)
        MirrorStateStore.awaitNextTick(SLEEP_MS)

        // No hidden->visible edge, so nothing is owed: the supervisor should keep its idle cadence
        // rather than spinning every time the Activity reports the same state again.
        MirrorStateStore.setUiVisible(true)
        val elapsed = measureTimeMillis { MirrorStateStore.awaitNextTick(SHORT_SLEEP_MS) }

        assertTrue("returned after ${elapsed}ms; a no-op flip woke the supervisor", elapsed >= SHORT_SLEEP_MS - GRACE_MS)
    }

    private companion object {
        const val SLEEP_MS = 4_000L
        const val SHORT_SLEEP_MS = 400L

        // Object.wait may return marginally early; only a wake shortens it by more than this.
        const val GRACE_MS = 60L
    }
}
