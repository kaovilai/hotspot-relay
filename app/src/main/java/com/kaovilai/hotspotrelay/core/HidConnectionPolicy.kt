package com.kaovilai.hotspotrelay.core

/**
 * Timing rules for the Bluetooth HID keyboard link.
 *
 * The app connects to the iPhone as a HID keyboard because that is the only
 * public way for an Android app to start the link that fires the iOS shortcut.
 * The cost is that iOS hides its on-screen keyboard while a hardware keyboard is
 * connected, and it may re-connect a remembered keyboard by itself. So a link is
 * only allowed inside a window opened by an explicit reconnect attempt (or by
 * pairing), and every link is dropped once that window has passed — without
 * removing the bond, so no re-pairing is needed for the next trigger.
 *
 * Pure so the timing is unit-tested instead of only observed on a device.
 */
object HidConnectionPolicy {

    /** How long one reconnect attempt may keep the link allowed. */
    const val TRIGGER_WINDOW_MS = 60_000L

    /** How long pairing may keep the link allowed (discoverability is 120 s). */
    const val PAIRING_WINDOW_MS = 120_000L

    /** How long a fresh link is left alone before the first window check. */
    const val GRACE_MS = 2_000L

    /** Delay after the bond completes before the pairing link is dropped. */
    const val PAIRING_DROP_DELAY_MS = 3_000L

    /** Re-check at least this often while a link is up in an open window. */
    const val MIN_CHECK_INTERVAL_MS = 5_000L

    /**
     * Bounded wake lock held while a window check is pending. Covers the longest
     * window (pairing) plus grace, so the drop lands even if the phone sleeps;
     * it is released as soon as the check finishes.
     */
    const val ENFORCEMENT_WAKE_LOCK_MS = 180_000L

    fun allowedUntil(nowMs: Long, durationMs: Long): Long = nowMs + durationMs

    fun isAllowed(nowMs: Long, allowedUntilMs: Long): Boolean = nowMs <= allowedUntilMs

    /**
     * Delay until the next enforcement check: immediately (0) once the window
     * has passed, otherwise the remaining window plus the grace period.
     */
    fun nextCheckDelayMs(nowMs: Long, allowedUntilMs: Long): Long =
        if (!isAllowed(nowMs, allowedUntilMs)) {
            0L
        } else {
            (allowedUntilMs - nowMs + GRACE_MS).coerceAtLeast(MIN_CHECK_INTERVAL_MS)
        }
}
