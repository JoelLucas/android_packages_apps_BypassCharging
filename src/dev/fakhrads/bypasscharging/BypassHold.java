/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Holds the battery at the level BypassCharging set: at or above it the battery is not charged
 * and the phone runs from the charger; once the phone has drawn it RESUME_MARGIN lower (a load
 * above what the charger gives, or time unplugged) it charges back up to the level. Below
 * ALWAYS_CHARGE_BELOW it always charges.
 *
 * Two holds can be set, and this is the one place that combines them: the user's (bypass on, in
 * Smart or Bypass mode) and the game hold ZeroEngine sets while a game runs. The lower level wins,
 * so the battery charges only when neither wants it held.
 *
 * The heat pause (opt-in) also stops charging while the battery is at TEMP_PAUSE_AT or warmer,
 * until it is below TEMP_RESUME_BELOW, whatever is held. Below ALWAYS_CHARGE_BELOW the battery
 * still charges, so a hot phone on a charger does not run empty.
 *
 * It follows ACTION_BATTERY_CHANGED in the app's persistent process, like CoolCharging, and the
 * first update after the process starts always writes, so the kernel vote matches the saved
 * state even after the process died.
 *
 * It is the only writer of the vote. That is what lets it skip a write when the vote it last
 * wrote is already the one it wants: nothing else changes the vote behind its back.
 */
final class BypassHold {
    private static final String TAG = "BypassCharging";

    static final String NODE =
            "/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable";
    // Voter name in the MCA votables, next to ChargeLimit's; "all" covers buck, quick and wireless
    private static final String VOTE_PREFIX = "bypasscharging all ";

    private static final String PREFS = "bypass_charging";
    private static final String KEY_ACTIVE_LIMIT = "active_limit";
    // Present only while ZeroEngine holds the battery for a game: the level it holds, and the
    // elapsedRealtime it was set at, to tell a hold from an earlier boot
    static final String KEY_GAME_HOLD = "game_hold";
    static final String KEY_GAME_HOLD_SINCE = "game_hold_since";
    static final String KEY_TEMP_PAUSE = "temp_pause";

    // Battery temperature, in tenths of °C, at which the heat pause stops charging and below which
    // it resumes: above where cool charging's last step (43 °C, 2.5 A) has already slowed it
    static final int TEMP_PAUSE_AT = 430;
    static final int TEMP_RESUME_BELOW = 400;

    private static BypassHold sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;

    // Guarded by this; null until the first write
    private Boolean mCharging;
    // Guarded by this: the heat pause stops charging now
    private boolean mTempPaused;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            update(intent);
        }
    };

    static synchronized BypassHold get(Context context) {
        if (sInstance == null) {
            sInstance = new BypassHold(context.getApplicationContext());
        }
        return sInstance;
    }

    private BypassHold(Context context) {
        mContext = context;
        // Device protected storage: the process starts before the first unlock
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    void start() {
        mContext.registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                null, mHandler);
    }

    /** The vote last written: true for charging, false for holding; null before the first. */
    synchronized Boolean getVote() {
        return mCharging;
    }

    /** The heat pause stops charging now. */
    synchronized boolean isTempPaused() {
        return mTempPaused;
    }

    /** Applies the saved state now; @return false if the kernel vote could not be written. */
    boolean update() {
        return update(mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    /** The level to hold: the lower of the user's hold and the game hold; -1 if neither is on. */
    private int holdLevel() {
        int user = mPrefs.getInt(KEY_ACTIVE_LIMIT, -1);
        int game = gameHoldLevel(mPrefs);
        int limit = user < 0 ? game : game < 0 ? user : Math.min(user, game);
        // Earlier versions saved any level, down to 1%, where the resume level is never reached
        return limit < 0 ? -1 : Math.max(limit, BypassCharging.MIN_HOLD_LEVEL);
    }

    /** The level the game hold keeps; -1 if there is none. */
    static int gameHoldLevel(SharedPreferences prefs) {
        // Set before this boot: the boot receiver drops it, but the process starts earlier
        if (prefs.getLong(KEY_GAME_HOLD_SINCE, Long.MAX_VALUE) > SystemClock.elapsedRealtime()) {
            return -1;
        }
        return prefs.getInt(KEY_GAME_HOLD, -1);
    }

    private synchronized boolean update(Intent battery) {
        int limit = holdLevel();
        int level = levelOf(battery);
        int temp = battery == null ? Integer.MIN_VALUE
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (!mPrefs.getBoolean(KEY_TEMP_PAUSE, false) || temp == Integer.MIN_VALUE) {
            mTempPaused = false;
        } else if (temp >= TEMP_PAUSE_AT) {
            mTempPaused = true;
        } else if (temp < TEMP_RESUME_BELOW) {
            mTempPaused = false;
        }
        boolean charging;
        if (level >= 0 && level < BypassCharging.ALWAYS_CHARGE_BELOW) {
            charging = true;
        } else if (mTempPaused) {
            charging = false;
        } else if (limit < 0 || level < 0) {
            charging = true;
        } else if (level >= limit) {
            charging = false;
        } else if (level <= limit - BypassCharging.RESUME_MARGIN || mCharging == null) {
            charging = true;
        } else {
            // Between the resume level and the held one: keep going the way it was
            charging = mCharging;
        }
        if (mCharging != null && mCharging == charging) {
            return true;
        }
        if (!setCharging(charging)) {
            return false;
        }
        Log.i(TAG, "Battery " + level + "% " + temp / 10f + " °C, hold " + limit + "%"
                + (mTempPaused ? ", paused for heat: " : ": ")
                + (charging ? "charging" : "running from the charger"));
        mCharging = charging;
        return true;
    }

    /** Votes for charging the battery or not; the charger keeps running the phone either way. */
    private static boolean setCharging(boolean charging) {
        // One vote per write: the driver parses a single "user type value" per store
        return McaBridge.writeCharge(VOTE_PREFIX + (charging ? 1 : 0));
    }

    private static int levelOf(Intent battery) {
        if (battery == null) {
            return -1;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 ? -1 : level * 100 / scale;
    }
}
