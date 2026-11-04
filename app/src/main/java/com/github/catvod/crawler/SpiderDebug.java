package com.github.catvod.crawler;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    public static boolean isEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    public static void log(Throwable th) {
        log(TAG, th);
    }

    public static void log(String tag, Throwable th) {
        if (th == null) return;
        StringWriter writer = new StringWriter();
        th.printStackTrace(new PrintWriter(writer));
        Log.d(tag, writer.toString());
    }

    public static void log(String msg) {
        Log.d(TAG, msg == null ? "" : msg);
    }

    public static void log(String tag, String msg, Object... args) {
        Log.d(tag, format(msg, args));
    }

    private static String format(String msg, Object... args) {
        try {
            return args == null || args.length == 0 ? msg : String.format(Locale.US, msg, args);
        } catch (Throwable e) {
            return msg;
        }
    }
}
