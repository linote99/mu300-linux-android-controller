package org.linote.mu300controller;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Private, bounded runtime log for diagnosing app failures. */
final class AppRuntimeLog {
    private static final String FILE_NAME = "mu300-runtime.log";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final int KEEP_BYTES = 256 * 1024;
    private static final int MAX_STACK_CHARS = 48 * 1024;
    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    private static boolean crashHandlerInstalled;

    private AppRuntimeLog() { }

    static void initialize(Context context, String version) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            appContext = app;
            if (!crashHandlerInstalled) {
                final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override public void uncaughtException(Thread thread, Throwable error) {
                        try {
                            recordThrowable("FATAL", "Uncaught exception on thread " + thread.getName(), error);
                        } catch (Throwable ignored) { }
                        if (previous != null && previous != this) previous.uncaughtException(thread, error);
                        else {
                            Process.killProcess(Process.myPid());
                            System.exit(10);
                        }
                    }
                });
                crashHandlerInstalled = true;
            }
        }
        record("APP", "start version=" + version + " android=" + Build.VERSION.RELEASE
                + " sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL);
    }

    static void record(String level, String message) {
        Context context = appContext;
        if (context == null) return;
        String text = message == null ? "(null)" : redact(message);
        String line = "[" + timestamp() + "] [" + level + "] ["
                + Thread.currentThread().getName() + "] " + text + "\n";
        append(context, line);
    }

    static void recordThrowable(String level, String message, Throwable error) {
        StringWriter sw = new StringWriter();
        if (error != null) error.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        if (trace.length() > MAX_STACK_CHARS) trace = trace.substring(0, MAX_STACK_CHARS) + "\n[stack truncated]\n";
        record(level, message + (trace.isEmpty() ? "" : "\n" + trace));
    }

    static byte[] readForExport(Context context) throws IOException {
        File f = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        synchronized (LOCK) {
            if (!f.isFile()) return "运行日志为空。\n".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static String exportFileName() {
        return "MU300-runtime-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
    }

    static long clear(Context context) {
        File f = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        synchronized (LOCK) {
            if (!f.isFile()) return 0;
            long n = f.length();
            return f.delete() ? n : 0;
        }
    }

    private static void append(Context context, String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE_NAME);
                trimIfNeeded(file, bytes.length);
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                    out.flush();
                }
            } catch (IOException | RuntimeException ignored) { }
        }
    }

    private static void trimIfNeeded(File file, int incomingBytes) throws IOException {
        if (!file.isFile() || file.length() + incomingBytes <= MAX_BYTES) return;
        int count = (int) Math.min((long) KEEP_BYTES, file.length());
        byte[] tail = new byte[count];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(file.length() - count);
            in.readFully(tail);
        }
        int start = 0;
        while (start < tail.length && tail[start] != '\n') start++;
        if (start < tail.length) start++;
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(tail, start, tail.length - start);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private static String redact(String value) {
        String x = value.replaceAll("(?i)\\b(IMEI|IMSI|ICCID|serial(?:\\s*number)?|电话号码|序列号)\\s*[:=：]?\\s*[A-Z0-9-]{6,24}", "$1=[已遮盖]");
        x = x.replaceAll("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b", "[MAC 已遮盖]");
        x = x.replaceAll("\\b[0-9]{14,22}\\b", "[数字已遮盖]");
        x = x.replaceAll("(?i)(authorization\\s*:\\s*bearer\\s+)\\S+", "$1[已遮盖]");
        return x.replaceAll("(?i)\\b(password|passwd|passphrase|token|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=[已遮盖]");
    }
}
