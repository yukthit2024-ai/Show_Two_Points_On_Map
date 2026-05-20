package com.vypeensoft.friendtracker.util;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLogger {
    private static final String TAG = "AppLogger";
    public static final String ACTION_LOG_UPDATE = "com.vypeensoft.friendtracker.LOG_UPDATE";
    private static final String LOG_DIR_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Vypeensoft/Friends_Location_Tracker/logs";
    private static String currentLogFileName = null;

    private static String getLogFileName() {
        if (currentLogFileName == null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault());
            currentLogFileName = "app-" + sdf.format(new Date()) + ".log";
        }
        return currentLogFileName;
    }

    public static void log(Context context, String tag, String message) {
        logToFile(context, tag, "INFO", message, null);
    }

    public static void logError(Context context, String tag, String message, Throwable t) {
        logToFile(context, tag, "ERROR", message, t);
    }

    private static synchronized void logToFile(Context context, String tag, String level, String message, Throwable t) {
        // Always log to logcat as well
        if ("ERROR".equals(level)) {
            Log.e(tag, message, t);
        } else {
            Log.i(tag, message);
        }

        if (context == null || !SettingsPersistenceManager.hasStoragePermission(context)) {
            return;
        }

        try {
            File dir = new File(LOG_DIR_PATH);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }

            File logFile = new File(dir, getLogFileName());
            FileWriter writer = new FileWriter(logFile, true);
            
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = String.format("%s [%s] [%s] %s\n", timestamp, level, tag, message);
            writer.write(logEntry);
            
            if (t != null) {
                writer.write(Log.getStackTraceString(t) + "\n");
            }
            
            writer.close();

            // Send local broadcast for UI updates
            Intent intent = new Intent(ACTION_LOG_UPDATE);
            intent.putExtra("message", logEntry);
            context.sendBroadcast(intent);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to log file", e);
        }
    }
}
