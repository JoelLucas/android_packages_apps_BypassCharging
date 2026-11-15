/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import dev.fakhrads.bypasscharging.ui.charging.ChargingControl
import dev.fakhrads.bypasscharging.ui.charging.ChargingState
import java.io.File
import java.io.IOException

/**
 * Charging control in this app's process: the settings page and ControlProvider (which
 * ZeroEngine's charging page asks) both go through here, so they change the same state.
 */
class LocalChargingControl(context: Context) : ChargingControl {
    private val context = context.applicationContext
    private val bypass = BypassCharging(this.context)
    private val limit = ChargeLimit(this.context)
    private val cool = CoolCharging.get(this.context)

    override fun state(): ChargingState {
        val supported = bypass.isSupported()
        // What the kernel does, not only what is saved: the effective vote of each charging path
        // and the vote BypassHold last wrote
        val paths = readChargeEnable()
        val vote = BypassHold.get(context).vote
        val plugged = (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return ChargingState(
            installed = true,
            supported = supported,
            active = supported && bypass.isActive(),
            mode = bypass.getMode(),
            activeLimit = bypass.getActiveLimit(),
            smartLimit = BypassCharging.SMART_LIMIT,
            gameLimit = bypass.getGameHoldLevel(),
            holding = vote == false && !paths.isNullOrEmpty() && paths.values.all { it == 0 },
            // The buck path charges the battery on any charger; quick only adds the charge pumps
            blockedByOther = plugged && vote != false && paths?.get("buck") == 0,
            holdIgnored = vote == false && paths != null && paths.values.any { it != 0 },
            coolEnabled = cool.isEnabled(),
            limitSupported = limit.isSupported(),
            limitEnabled = limit.isEnabled(),
            watts = limit.getWatts(),
            minWatts = ChargeLimit.MIN_WATTS,
            maxWatts = ChargeLimit.MAX_WATTS,
            stepWatts = ChargeLimit.STEP_WATTS
        )
    }

    override fun setBypass(enabled: Boolean): String? {
        val ok = bypass.isSupported() && bypass.setActive(enabled)
        return if (ok) null else details(bypass.getLastError())
    }

    override fun setMode(mode: Int): String? {
        val ok = bypass.setMode(mode)
        return if (ok) null else details(bypass.getLastError())
    }

    /** ZeroEngine's game hold: see BypassCharging.setGameHold. Not on the settings page. */
    fun setGameHold(enabled: Boolean): String? {
        val ok = bypass.setGameHold(enabled)
        return if (ok) null else details(bypass.getLastError())
    }

    override fun setCool(enabled: Boolean): String? =
        if (cool.setEnabled(enabled)) null else context.getString(R.string.limit_failed)

    override fun setLimit(enabled: Boolean, watts: Int): String? =
        if (limit.set(enabled, watts)) null else context.getString(R.string.limit_failed)

    /**
     * The charge_enable node: one "<path> eff_client:<voter> <value>" line per charging path that
     * has votes (buck, quick), with the AND of every voter's vote as the value. The voter is the
     * last one to vote, not the one that decided, so only the values are used. Null if the node
     * cannot be read.
     */
    private fun readChargeEnable(): Map<String, Int>? =
        try {
            File(BypassHold.NODE).readLines().mapNotNull { line ->
                val fields = line.trim().split(' ')
                val value = fields.lastOrNull()?.toIntOrNull()
                if (fields.size >= 3 && fields[1].startsWith("eff_client:") && value != null) {
                    fields[0] to value
                } else {
                    null
                }
            }.toMap()
        } catch (e: IOException) {
            null
        }

    /** The reason and the grant state of each requested permission, to copy into a report. */
    private fun details(error: String?): String {
        val sb = StringBuilder(error ?: "unknown error")
        try {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.forEach { perm ->
                    sb.append('\n').append(perm).append(": ").append(
                        if (context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) "granted"
                        else "DENIED"
                    )
                }
        } catch (e: PackageManager.NameNotFoundException) {
            // Not possible for our own package
        }
        return sb.toString()
    }
}
