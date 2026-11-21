package dev.fakhrads.bypasschargebridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Tiny system-UID, signature-protected bridge. No arbitrary paths or arbitrary commands. */
public final class ChargeProvider extends ContentProvider {
    private static final String TAG = "BypassChargeBridge";
    private static final String BASE = "/sys/devices/platform/soc/soc:mca_charge_interface/";
    private static final String CHARGE = BASE + "charge_enable";
    private static final String CURRENT = BASE + "ichg_limit";

    @Override public boolean onCreate() { return true; }

    private void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && getContext().getPackageManager()
                .checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Invalid charging bridge caller: " + uid);
        }
    }

    private static boolean validCharge(String cmd) {
        return "bypasscharging all 0".equals(cmd)
                || "bypasscharging all 1".equals(cmd);
    }

    private static boolean validCurrent(String cmd) {
        if (cmd == null) return false;
        String[] a = cmd.split(" ");
        if (a.length != 3 || !(a[0].equals("bypasscharging")
                || a[0].equals("coolcharging"))) return false;
        String[] values = a[2].split("#", -1);
        if (a[1].equals("buck") && values.length != 1) return false;
        if (a[1].equals("quick") && values.length != 2) return false;
        if (!a[1].equals("buck") && !a[1].equals("quick")) return false;
        for (String s : values) {
            try {
                int v = Integer.parseInt(s);
                if (v < 0 || v > 30000) return false;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private static String read(String path) throws IOException {
        // Enough for the short output of the MCA charge_enable sysfs node.
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            return n < 0 ? "" : new String(buf, 0, n, StandardCharsets.US_ASCII);
        }
    }

    private static void write(String path, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        Bundle out = new Bundle();
        try {
            String cmd = extras == null ? null : extras.getString("command");
            switch (method) {
                case "check_charge":
                    out.putBoolean("ok", new File(CHARGE).canRead()
                            && new File(CHARGE).canWrite());
                    break;
                case "check_current":
                    out.putBoolean("ok", new File(CURRENT).canWrite());
                    break;
                case "read_charge":
                    out.putString("text", read(CHARGE));
                    out.putBoolean("ok", true);
                    break;
                case "write_charge":
                    if (!validCharge(cmd)) throw new SecurityException("Invalid charge command");
                    write(CHARGE, cmd);
                    out.putBoolean("ok", true);
                    break;
                case "write_current":
                    if (!validCurrent(cmd)) throw new SecurityException("Invalid current command");
                    write(CURRENT, cmd);
                    out.putBoolean("ok", true);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown bridge operation: " + method);
            }
        } catch (IOException e) {
            Log.e(TAG, "MCA operation failed: " + method, e);
            out.putBoolean("ok", false);
            out.putString("error", e.toString());
        }
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] args) { return 0; }
}
