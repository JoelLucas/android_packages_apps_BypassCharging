/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * Turns bypass charging on and off through the MCA charge interface (charge_enable).
 *
 * A vote of 0 from this app to the charge enable votables (an AND of every voter) stops the
 * charger from charging the battery, and nothing else: the charger keeps its input current and
 * the adapter keeps its voltage, so the phone runs from USB and the battery current stays near
 * zero. Lineage Health charging control is not used for it: on onyx it stops charging through
 * the smart_chg SOC limit, which also drops the input to 5 V and 0.5 to 1 A, so a game draws
 * the rest from the battery.
 *
 * Two modes, both held by BypassHold in the app's persistent process:
 * - smart: charges to a limit (80% unless set, 60% to 95%), then the phone runs from the
 *   charger; charging resumes 3% lower
 * - bypass: holds the battery level it was turned on at, but never below MIN_HOLD_LEVEL
 * Below ALWAYS_CHARGE_BELOW the battery always charges, whatever is held.
 *
 * ZeroEngine can also hold the battery while a game runs (setGameHold), on top of the user's
 * setting and without changing it; BypassHold combines the two.
 *
 * The vote is gone after a reboot. BootReceiver keeps Smart mode on (BypassHold votes again) and
 * turns a Bypass mode hold off, since that holds whatever level it was turned on at. Versions
 * before this one used Lineage charging control; the settings they saved are put back once.
 *
 * BypassHold is the only writer of the vote: this class saves the state and asks it to apply it.
 * Every change goes through LOCK, since the tile, the settings page, ControlProvider and the boot
 * receiver each have their own instance in the same process.
 *
 * The node is labeled vendor_sysfs_charge_limit and made writable by init.onyx.rc, in the
 * device tree, like ichg_limit.
 */
final class BypassCharging {
    private static final String TAG = "BypassCharging";

    static final int MODE_SMART = 0;
    static final int MODE_BYPASS = 1;

    static final int SMART_LIMIT = 80;
    static final int MIN_SMART_LIMIT = 60;
    static final int MAX_SMART_LIMIT = 95;
    static final int STEP_SMART_LIMIT = 5;
    // Charging resumes this many percent below the held level
    static final int RESUME_MARGIN = 3;
    // No hold is set lower: from a nearly empty battery it charges up to this first
    static final int MIN_HOLD_LEVEL = 20;
    // Below this the battery charges whatever is held, so a phone left on a hold never runs empty
    static final int ALWAYS_CHARGE_BELOW = 15;

    private static final String NODE = BypassHold.NODE;

    // Every change of the saved state and the vote that follows it, across all instances
    private static final Object LOCK = new Object();

    private static final String PREFS = "bypass_charging";
    private static final String KEY_MODE = "mode";
    // The level Smart mode charges to
    private static final String KEY_SMART_LIMIT = "smart_limit";
    // Present only while bypass is on: the level it holds
    private static final String KEY_ACTIVE_LIMIT = "active_limit";
    // Lineage charging control settings saved by earlier versions
    private static final String KEY_SAVED_ENABLED = "saved_enabled";
    private static final String KEY_SAVED_MODE = "saved_mode";
    private static final String KEY_SAVED_LIMIT = "saved_limit";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final SharedPreferences mPrefs;
    private String mLastError;

    BypassCharging(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        // Device protected storage: usable before the first unlock (tile, boot receiver)
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Why the last setActive/setMode failed, for the toast; null if unknown. */
    String getLastError() {
        return mLastError;
    }

    boolean isSupported() {
        return new File(NODE).canWrite();
    }

    int getMode() {
        return mPrefs.getInt(KEY_MODE, MODE_SMART);
    }

    /** Selects the mode; if bypass is on, it is applied again with the new mode. */
    boolean setMode(int mode) {
        synchronized (LOCK) {
            mLastError = null;
            mPrefs.edit().putInt(KEY_MODE, mode).commit();
            return !isActive() || apply();
        }
    }

    int getSmartLimit() {
        return mPrefs.getInt(KEY_SMART_LIMIT, SMART_LIMIT);
    }

    /** Sets the level Smart mode charges to; if it holds now, it holds the new level. */
    boolean setSmartLimit(int limit) {
        synchronized (LOCK) {
            mLastError = null;
            limit = Math.max(MIN_SMART_LIMIT, Math.min(limit, MAX_SMART_LIMIT));
            mPrefs.edit().putInt(KEY_SMART_LIMIT, limit).commit();
            return !isActive() || getMode() != MODE_SMART || apply();
        }
    }

    /** The level held while active, otherwise -1. */
    int getActiveLimit() {
        return mPrefs.getInt(KEY_ACTIVE_LIMIT, -1);
    }

    boolean isActive() {
        return mPrefs.contains(KEY_ACTIVE_LIMIT);
    }

    /** The level the game hold keeps; -1 if there is none. */
    int getGameHoldLevel() {
        return BypassHold.gameHoldLevel(mPrefs);
    }

    /**
     * Holds the battery at its current level (never below MIN_HOLD_LEVEL) while a game runs, or
     * lets it go. It is set on top of the user's setting: the mode and bypass stay as they are,
     * and the lower of the two holds wins. Holding again while held keeps the level it holds, so
     * a game that runs the battery down does not lower it. It is gone after a reboot.
     *
     * @return true if the new state was applied
     */
    boolean setGameHold(boolean hold) {
        synchronized (LOCK) {
            mLastError = null;
            if (!hold) {
                // Released even when the node is not writable, so nothing is left behind
                mPrefs.edit()
                        .remove(BypassHold.KEY_GAME_HOLD)
                        .remove(BypassHold.KEY_GAME_HOLD_SINCE)
                        .commit();
                if (!BypassHold.get(mContext).update()) {
                    mLastError = "writing " + NODE + " failed";
                    return false;
                }
                return true;
            }
            if (!isSupported()) {
                mLastError = NODE + " is not writable";
                return false;
            }
            if (getGameHoldLevel() >= 0) {
                return true;
            }
            int level = batteryLevel();
            if (level < 1) {
                Log.e(TAG, "Unknown battery level");
                mLastError = "battery level " + level;
                return false;
            }
            // commit: a process restart during the game keeps it until ZeroEngine lets it go
            mPrefs.edit()
                    .putInt(BypassHold.KEY_GAME_HOLD,
                            Math.max(MIN_HOLD_LEVEL, Math.min(level, 100)))
                    .putLong(BypassHold.KEY_GAME_HOLD_SINCE, SystemClock.elapsedRealtime())
                    .commit();
            if (!BypassHold.get(mContext).update()) {
                mLastError = "writing " + NODE + " failed";
                mPrefs.edit()
                        .remove(BypassHold.KEY_GAME_HOLD)
                        .remove(BypassHold.KEY_GAME_HOLD_SINCE)
                        .commit();
                BypassHold.get(mContext).update();
                return false;
            }
            return true;
        }
    }

    boolean isTempPauseEnabled() {
        return mPrefs.getBoolean(BypassHold.KEY_TEMP_PAUSE, false);
    }

    /**
     * Turns the heat pause on or off: no charging from TEMP_PAUSE_AT until the battery is below
     * TEMP_RESUME_BELOW (BypassHold). It stays on after a reboot.
     *
     * @return true if the new state was applied
     */
    boolean setTempPause(boolean enabled) {
        synchronized (LOCK) {
            mLastError = null;
            if (enabled && !isSupported()) {
                mLastError = NODE + " is not writable";
                return false;
            }
            mPrefs.edit().putBoolean(BypassHold.KEY_TEMP_PAUSE, enabled).commit();
            if (!BypassHold.get(mContext).update()) {
                mLastError = "writing " + NODE + " failed";
                return false;
            }
            return true;
        }
    }

    /** @return true if the new state was applied */
    boolean setActive(boolean active) {
        synchronized (LOCK) {
            mLastError = null;
            if (!isSupported()) {
                mLastError = NODE + " is not writable";
                return false;
            }
            if (active == isActive()) {
                return true;
            }
            if (!active) {
                return restore();
            }
            return apply();
        }
    }

    /**
     * After a reboot, before the first unlock: Smart mode stays on and BypassHold holds its limit
     * again; a Bypass mode hold and the game hold are dropped.
     *
     * @return false if the vote could not be written
     */
    boolean onBoot() {
        synchronized (LOCK) {
            mLastError = null;
            restoreLegacySettings();
            SharedPreferences.Editor editor = mPrefs.edit()
                    .remove(BypassHold.KEY_GAME_HOLD)
                    .remove(BypassHold.KEY_GAME_HOLD_SINCE);
            if (getMode() == MODE_BYPASS) {
                editor.remove(KEY_ACTIVE_LIMIT);
            }
            editor.commit();
            if (!BypassHold.get(mContext).update()) {
                mLastError = "writing " + NODE + " failed";
                return false;
            }
            return true;
        }
    }

    /**
     * Turns bypass off: the held level is forgotten and BypassHold votes for charging again.
     *
     * @return false if the vote could not be written
     */
    private boolean restore() {
        synchronized (LOCK) {
            restoreLegacySettings();
            mPrefs.edit().remove(KEY_ACTIVE_LIMIT).commit();
            if (!BypassHold.get(mContext).update()) {
                mLastError = "writing " + NODE + " failed";
                return false;
            }
            return true;
        }
    }

    // Called with LOCK held
    private boolean apply() {
        int limit = getMode() == MODE_BYPASS ? batteryLevel() : getSmartLimit();
        if (limit < 1) {
            Log.e(TAG, "Unknown battery level");
            mLastError = "battery level " + limit;
            return false;
        }
        restoreLegacySettings();
        limit = Math.max(MIN_HOLD_LEVEL, Math.min(limit, 100));
        // commit: BypassHold reads it back, and a process restart must find it
        mPrefs.edit().putInt(KEY_ACTIVE_LIMIT, limit).commit();
        // BypassHold decides from the level whether the battery charges now
        if (!BypassHold.get(mContext).update()) {
            mLastError = "writing " + NODE + " failed";
            mPrefs.edit().remove(KEY_ACTIVE_LIMIT).commit();
            BypassHold.get(mContext).update();
            return false;
        }
        return true;
    }

    private int batteryLevel() {
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return Math.min(level, 100);
    }

    /** Puts back the charging control settings an earlier version replaced, once. */
    private void restoreLegacySettings() {
        if (!mPrefs.contains(KEY_SAVED_ENABLED)) {
            return;
        }
        try {
            Integer mode = mPrefs.contains(KEY_SAVED_MODE) ? mPrefs.getInt(KEY_SAVED_MODE, 0) : null;
            RomPlatform.restoreChargingControl(mResolver, mode,
                    mPrefs.getInt(KEY_SAVED_LIMIT, SMART_LIMIT),
                    mPrefs.getBoolean(KEY_SAVED_ENABLED, false));
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to restore charging control", e);
        }
        mPrefs.edit()
                .remove(KEY_SAVED_ENABLED)
                .remove(KEY_SAVED_MODE)
                .remove(KEY_SAVED_LIMIT)
                .apply();
    }
}
