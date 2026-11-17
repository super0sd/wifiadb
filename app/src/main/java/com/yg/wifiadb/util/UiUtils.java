package com.yg.wifiadb.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

public final class UiUtils {

    private UiUtils() {
    }

    public static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context ctx, String text) {
        if (ctx == null || text == null) {
            return;
        }
        Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    public static void copy(Context ctx, String label, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        if (Build.VERSION.SDK_INT <= 32) {
            toast(ctx, "已复制");
        }
    }

    public static int color(Context ctx, int res) {
        return ContextCompat.getColor(ctx, res);
    }

    public static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
