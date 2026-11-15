/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The kernel forgets every vote of this app on reboot, so they are written again here.
 *
 * Smart mode stays on: BypassHold holds the battery at its limit again. A Bypass mode hold is
 * turned off, since it holds whatever level it was turned on at, and so is ZeroEngine's game
 * hold. The fast charging limit stays on and is written again.
 *
 * init makes the charge interface nodes writable "on boot", before LOCKED_BOOT_COMPLETED, so
 * everything is done there, before the first unlock. BOOT_COMPLETED only writes the votes again
 * in case a write failed, and leaves a bypass that was turned on in between alone.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BypassCharging";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            BypassCharging bypass = new BypassCharging(context);
            Log.i(TAG, "Boot: " + (bypass.getMode() == BypassCharging.MODE_BYPASS
                    ? "turning bypass charging off" : "keeping smart charging"));
            if (!bypass.onBoot()) {
                Log.w(TAG, "Boot: " + bypass.getLastError());
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            BypassHold.get(context).update();
        } else {
            return;
        }

        ChargeLimit limit = new ChargeLimit(context);
        if (limit.isEnabled() && !limit.apply()) {
            Log.w(TAG, "Boot: fast charging limit not set (" + action + ")");
        }
    }
}
