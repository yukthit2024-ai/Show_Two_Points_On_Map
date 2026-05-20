package com.vypeensoft.friendtracker.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vypeensoft.friendtracker.MapSettingsActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;

public class SettingsPersistenceManager {

    private static final String SETTINGS_DIR_PATH = getSettingsDirPath();

    private static String getSettingsDirPath() {
        File dir = new File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings");
        if (dir.exists()) {
            return "/sdcard/Vypeensoft/Friends_Location_Tracker/settings";
        }
        File envDir = new File(Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/settings");
        if (envDir.exists()) {
            return envDir.getAbsolutePath();
        }
        if (dir.mkdirs()) {
            return "/sdcard/Vypeensoft/Friends_Location_Tracker/settings";
        }
        return envDir.getAbsolutePath();
    }
    
    private static final String FILE_MAP_SETTINGS = "map_settings.json";
    private static final String FILE_MATRIX_CREDENTIALS = "matrix_credentials.json";
    private static final String FILE_MATRIX_CREDENTIALS_ALT = "matrix_credentails.json";
    private static final String FILE_ROOMS_SETTINGS = "rooms_settings.json";

    public static void exportSettings(Context context) {
        if (!hasStoragePermission(context)) {
            return;
        }

        try {
            File dir = new File(SETTINGS_DIR_PATH);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            SharedPreferences prefs = context.getSharedPreferences(MapSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
            Map<String, ?> allEntries = prefs.getAll();

            // Category 1: Map Settings
            JSONObject mapJson = new JSONObject();
            mapJson.put(MapSettingsActivity.KEY_STYLE_URL, prefs.getString(MapSettingsActivity.KEY_STYLE_URL, ""));
            saveToFile(new File(dir, FILE_MAP_SETTINGS), mapJson.toString(4));

            // Category 2: Matrix Credentials
            JSONObject matrixJson = new JSONObject();
            matrixJson.put(MapSettingsActivity.KEY_MATRIX_HOMESERVER, prefs.getString(MapSettingsActivity.KEY_MATRIX_HOMESERVER, ""));
            matrixJson.put(MapSettingsActivity.KEY_MATRIX_TOKEN, prefs.getString(MapSettingsActivity.KEY_MATRIX_TOKEN, ""));
            matrixJson.put(MapSettingsActivity.KEY_MATRIX_USERNAME, prefs.getString(MapSettingsActivity.KEY_MATRIX_USERNAME, ""));
            matrixJson.put(MapSettingsActivity.KEY_MATRIX_PASSWORD, prefs.getString(MapSettingsActivity.KEY_MATRIX_PASSWORD, ""));
            matrixJson.put(MapSettingsActivity.KEY_MATRIX_DISPLAY_NAME, prefs.getString(MapSettingsActivity.KEY_MATRIX_DISPLAY_NAME, ""));
            matrixJson.put(MapSettingsActivity.KEY_MATRIX_POLLING_PERIOD, prefs.getLong(MapSettingsActivity.KEY_MATRIX_POLLING_PERIOD, 10000L));
            saveToFile(new File(dir, FILE_MATRIX_CREDENTIALS), matrixJson.toString(4));
            saveToFile(new File(dir, FILE_MATRIX_CREDENTIALS_ALT), matrixJson.toString(4));

            // Category 3: Rooms Settings
            JSONObject roomsJson = new JSONObject();
            roomsJson.put("matrix_rooms", prefs.getString("matrix_rooms", "[]"));
            saveToFile(new File(dir, FILE_ROOMS_SETTINGS), roomsJson.toString(4));
            AppLogger.log(context, "SettingsPersistence", "Settings exported successfully to " + SETTINGS_DIR_PATH);
        } catch (Exception e) {
            AppLogger.logError(context, "SettingsPersistence", "Error exporting settings", e);
            Toast.makeText(context, "Error exporting settings: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void importSettings(Context context) {
        if (!hasStoragePermission(context)) {
            return;
        }

        File dir = new File(SETTINGS_DIR_PATH);
        if (!dir.exists()) return;

        SharedPreferences prefs = context.getSharedPreferences(MapSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        try {
            // Import Map Settings
            File mapFile = new File(dir, FILE_MAP_SETTINGS);
            if (mapFile.exists()) {
                String content = readFile(mapFile);
                JSONObject json = new JSONObject(content);
                if (json.has(MapSettingsActivity.KEY_STYLE_URL))
                    editor.putString(MapSettingsActivity.KEY_STYLE_URL, json.getString(MapSettingsActivity.KEY_STYLE_URL));
            }

            // Import Matrix Credentials
            File matrixFile = new File(dir, FILE_MATRIX_CREDENTIALS);
            if (!matrixFile.exists()) {
                matrixFile = new File(dir, FILE_MATRIX_CREDENTIALS_ALT);
            }
            if (matrixFile.exists()) {
                String content = readFile(matrixFile);
                JSONObject json = new JSONObject(content);
                if (json.has(MapSettingsActivity.KEY_MATRIX_HOMESERVER))
                    editor.putString(MapSettingsActivity.KEY_MATRIX_HOMESERVER, json.getString(MapSettingsActivity.KEY_MATRIX_HOMESERVER));
                if (json.has(MapSettingsActivity.KEY_MATRIX_TOKEN))
                    editor.putString(MapSettingsActivity.KEY_MATRIX_TOKEN, json.getString(MapSettingsActivity.KEY_MATRIX_TOKEN));
                if (json.has(MapSettingsActivity.KEY_MATRIX_USERNAME))
                    editor.putString(MapSettingsActivity.KEY_MATRIX_USERNAME, json.getString(MapSettingsActivity.KEY_MATRIX_USERNAME));
                if (json.has(MapSettingsActivity.KEY_MATRIX_PASSWORD))
                    editor.putString(MapSettingsActivity.KEY_MATRIX_PASSWORD, json.getString(MapSettingsActivity.KEY_MATRIX_PASSWORD));
                if (json.has(MapSettingsActivity.KEY_MATRIX_DISPLAY_NAME))
                    editor.putString(MapSettingsActivity.KEY_MATRIX_DISPLAY_NAME, json.getString(MapSettingsActivity.KEY_MATRIX_DISPLAY_NAME));
                if (json.has(MapSettingsActivity.KEY_MATRIX_POLLING_PERIOD))
                    editor.putLong(MapSettingsActivity.KEY_MATRIX_POLLING_PERIOD, json.getLong(MapSettingsActivity.KEY_MATRIX_POLLING_PERIOD));
            }

            // Import Rooms Settings
            File roomsFile = new File(dir, FILE_ROOMS_SETTINGS);
            if (roomsFile.exists()) {
                String content = readFile(roomsFile);
                JSONObject json = new JSONObject(content);
                if (json.has("matrix_rooms"))
                    editor.putString("matrix_rooms", json.getString("matrix_rooms"));
            }

            editor.apply();
            AppLogger.log(context, "SettingsPersistence", "Settings imported successfully from " + SETTINGS_DIR_PATH);
        } catch (Exception e) {
            AppLogger.logError(context, "SettingsPersistence", "Error importing settings", e);
        }
    }

    private static void saveToFile(File file, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        OutputStreamWriter writer = new OutputStreamWriter(fos);
        writer.write(content);
        writer.close();
        fos.close();
    }

    private static String readFile(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        InputStreamReader reader = new InputStreamReader(fis);
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
        }
        reader.close();
        fis.close();
        return sb.toString();
    }

    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return true;
        }
    }

    public static void requestStoragePermission(android.app.Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivity(intent);
            }
        }
    }
}
