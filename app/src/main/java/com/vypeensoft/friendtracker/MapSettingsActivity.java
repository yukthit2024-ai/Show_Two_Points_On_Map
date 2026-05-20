package com.vypeensoft.friendtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;
import com.vypeensoft.friendtracker.util.SettingsPersistenceManager;

public class MapSettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "AppConfig";
    public static final String KEY_STYLE_URL = "map_style_url";
    public static final String KEY_GPS_REFRESH_INTERVAL = "gps_refresh_interval";
    public static final String KEY_MATRIX_HOMESERVER = "matrix_homeserver";
    public static final String KEY_MATRIX_TOKEN = "matrix_token";
    public static final String KEY_MATRIX_ROOM_ID = "matrix_room_id";
    public static final String KEY_MATRIX_USERNAME = "matrix_username";
    public static final String KEY_MATRIX_PASSWORD = "matrix_password";
    public static final String KEY_MATRIX_ROOM_ALIAS = "matrix_room_alias";
    public static final String KEY_MATRIX_DISPLAY_NAME = "matrix_display_name";
    public static final String KEY_MATRIX_POLLING_PERIOD = "matrix_polling_period";

    private TextInputEditText editStyleUrl;
    private TextInputEditText editGpsInterval;
    private Button btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        editStyleUrl = findViewById(R.id.edit_mapbox_token);
        editGpsInterval = findViewById(R.id.edit_gps_interval);
        btnSave = findViewById(R.id.btn_save);

        loadConfig();

        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        editStyleUrl.setText(prefs.getString(KEY_STYLE_URL, "https://tiles.openfreemap.org/styles/liberty"));
        long intervalSec = prefs.getLong(KEY_GPS_REFRESH_INTERVAL, 10L);
        editGpsInterval.setText(String.valueOf(intervalSec));
    }

    private void saveConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString(KEY_STYLE_URL, editStyleUrl.getText().toString().trim());

        String intervalStr = editGpsInterval.getText().toString().trim();
        long intervalSec = 10L;
        try {
            intervalSec = Long.parseLong(intervalStr);
            if (intervalSec < 1L) intervalSec = 1L;
        } catch (NumberFormatException ignored) {}
        editor.putLong(KEY_GPS_REFRESH_INTERVAL, intervalSec);

        if (editor.commit()) {
            SettingsPersistenceManager.exportSettings(this);
            
            // Restart location tracking service to apply the new interval
            try {
                android.content.Intent serviceIntent = new android.content.Intent(this, com.vypeensoft.friendtracker.service.LocationService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } catch (Exception e) {
                android.util.Log.e("FriendTracker", "Error restarting LocationService with new interval", e);
            }

            Toast.makeText(this, "Configuration Saved", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Failed to Save Configuration", Toast.LENGTH_SHORT).show();
        }
    }
}
