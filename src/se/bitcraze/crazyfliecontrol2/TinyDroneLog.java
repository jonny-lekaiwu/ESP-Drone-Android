package se.bitcraze.crazyfliecontrol2;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent diagnostics for failures that happen before logcat can be collected. */
public final class TinyDroneLog {
    private static final String TAG = "TinyDroneLog";
    private static final long MAX_LOG_BYTES = 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static File sLogFile;
    private static Thread.UncaughtExceptionHandler sPreviousHandler;

    private TinyDroneLog() {
    }

    public static void initialize(Context context) {
        synchronized (LOCK) {
            if (sLogFile != null) return;
            File base = context.getExternalFilesDir("logs");
            if (base == null) base = new File(context.getFilesDir(), "logs");
            if (!base.exists() && !base.mkdirs()) {
                Log.e(TAG, "Cannot create log directory: " + base);
            }
            sLogFile = new File(base, "TinyDrone.log");
            rotateIfNeeded();
            write("APP", "TinyDrone started; Android " + Build.VERSION.RELEASE +
                    " (SDK " + Build.VERSION.SDK_INT + "), device " +
                    Build.MANUFACTURER + " " + Build.MODEL);

            sPreviousHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    write("CRASH", "Uncaught exception on thread " + thread.getName(), throwable);
                    if (sPreviousHandler != null) {
                        sPreviousHandler.uncaughtException(thread, throwable);
                    }
                }
            });
        }
    }

    public static String getPath() {
        synchronized (LOCK) {
            return sLogFile == null ? "not initialized" : sLogFile.getAbsolutePath();
        }
    }

    public static void write(String stage, String message) {
        write(stage, message, null);
    }

    public static void write(String stage, String message, Throwable throwable) {
        Log.i(TAG, stage + ": " + message, throwable);
        synchronized (LOCK) {
            if (sLogFile == null) return;
            rotateIfNeeded();
            FileOutputStream stream = null;
            try {
                stream = new FileOutputStream(sLogFile, true);
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date());
                StringBuilder line = new StringBuilder(timestamp)
                        .append(" [").append(Thread.currentThread().getName()).append("] ")
                        .append(stage).append(": ").append(message).append('\n');
                if (throwable != null) {
                    StringWriter stack = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(stack));
                    line.append(stack.toString());
                }
                stream.write(line.toString().getBytes("UTF-8"));
                stream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Cannot write persistent log", e);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static void rotateIfNeeded() {
        if (sLogFile == null || !sLogFile.exists() || sLogFile.length() < MAX_LOG_BYTES) return;
        File previous = new File(sLogFile.getParentFile(), "TinyDrone.previous.log");
        if (previous.exists() && !previous.delete()) Log.w(TAG, "Cannot delete old log");
        if (!sLogFile.renameTo(previous)) Log.w(TAG, "Cannot rotate log");
    }
}
