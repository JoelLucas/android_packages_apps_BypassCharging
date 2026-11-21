package dev.fakhrads.bypasscharging;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Signature-protected IPC to the system-UID charging bridge. */
final class McaBridge {
    private static final String TAG = "McaBridge";
    private static final Uri URI = Uri.parse("content://dev.fakhrads.bypasschargebridge");
    private static Context sContext;

    private McaBridge() {}
    static void init(Context context) { sContext = context.getApplicationContext(); }

    private static Bundle call(String method, String command) {
        if (sContext == null) {
            Log.e(TAG, "Not initialized");
            return null;
        }
        Bundle extras = new Bundle();
        if (command != null) extras.putString("command", command);
        try {
            return sContext.getContentResolver().call(URI, method, null, extras);
        } catch (RuntimeException e) {
            Log.e(TAG, "Bridge call failed: " + method, e);
            return null;
        }
    }

    static boolean canCharge() { return success("check_charge", null); }
    static boolean canCurrent() { return success("check_current", null); }
    static boolean writeCharge(String cmd) { return success("write_charge", cmd); }
    static boolean writeCurrent(String cmd) { return success("write_current", cmd); }
    static String readCharge() {
        Bundle b = call("read_charge", null);
        return b != null && b.getBoolean("ok", false) ? b.getString("text") : null;
    }
    private static boolean success(String method, String cmd) {
        Bundle b = call(method, cmd);
        return b != null && b.getBoolean("ok", false);
    }
}
