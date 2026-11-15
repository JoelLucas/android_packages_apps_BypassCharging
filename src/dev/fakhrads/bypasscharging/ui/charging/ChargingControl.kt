/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging.ui.charging

/** What the charging page shows; ZeroEngine gets the same through ControlProvider. */
data class ChargingState(
    val installed: Boolean = false,
    val supported: Boolean = false,
    val active: Boolean = false,
    val mode: Int = MODE_SMART,
    val activeLimit: Int = -1,
    val smartLimit: Int = 80,
    val minSmartLimit: Int = 60,
    val maxSmartLimit: Int = 95,
    val stepSmartLimit: Int = 5,
    /** The level ZeroEngine's game hold keeps, on top of the user's setting; -1 if none. */
    val gameLimit: Int = -1,
    /** Read back from the kernel: our vote stops charging, and the driver took it. */
    val holding: Boolean = false,
    /** Read back from the kernel: plugged in and we allow charging, but another voter stops it. */
    val blockedByOther: Boolean = false,
    /** Read back from the kernel: we vote to stop charging, but the driver still allows it. */
    val holdIgnored: Boolean = false,
    val coolEnabled: Boolean = false,
    /** The heat pause is on, and whether it stops charging now. */
    val tempPauseEnabled: Boolean = false,
    val tempPaused: Boolean = false,
    val limitSupported: Boolean = false,
    val limitEnabled: Boolean = false,
    val watts: Int = 30,
    val minWatts: Int = 5,
    val maxWatts: Int = 60,
    val stepWatts: Int = 5
) {
    companion object {
        const val MODE_SMART = 0
        const val MODE_BYPASS = 1
    }
}

/** Charging control; each call blocks on the app that owns it, so call it off the main thread. */
interface ChargingControl {
    fun state(): ChargingState

    /** These return null when the change was applied, otherwise why it was not. */
    fun setBypass(enabled: Boolean): String?

    fun setMode(mode: Int): String?

    fun setSmartLimit(limit: Int): String?

    fun setCool(enabled: Boolean): String?

    fun setTempPause(enabled: Boolean): String?

    fun setLimit(enabled: Boolean, watts: Int): String?
}
