package com.vypeensoft.friendtracker.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.vypeensoft.friendtracker.model.LocationMessage;
import com.vypeensoft.friendtracker.network.MatrixClient;
import com.vypeensoft.friendtracker.MapSettingsActivity;
import com.vypeensoft.friendtracker.util.AppLogger;
import android.content.SharedPreferences;
import android.content.Context;

public class LocationService extends Service {
    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "LocationServiceChannel";
    private static final int NOTIFICATION_ID = 123;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private MatrixClient matrixClient;
    private String userId = "user_" + Build.ID; // Simple default userId

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.log(this, TAG, "LocationService created");
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        matrixClient = new MatrixClient(this);
        createNotificationChannel();
        
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    onLocationUpdated(location);
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLogger.log(this, TAG, "LocationService starting...");
        startForeground(NOTIFICATION_ID, getNotification());
        requestLocationUpdates();
        return START_STICKY;
    }

    private void requestLocationUpdates() {
        SharedPreferences prefs = getSharedPreferences(MapSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        long gpsIntervalSec = prefs.getLong(MapSettingsActivity.KEY_GPS_REFRESH_INTERVAL, 10L);
        long gpsIntervalMs = gpsIntervalSec * 1000L;
        if (gpsIntervalMs < 1000L) {
            gpsIntervalMs = 10000L;
        }
        
        Log.d(TAG, "Requesting location updates with customized GPS refresh interval: " + gpsIntervalMs + "ms");

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, gpsIntervalMs)
                .setMinUpdateIntervalMillis(gpsIntervalMs / 2)
                .build();

        try {
            // Remove previous updates before requesting new ones to prevent overlaps
            fusedLocationClient.removeLocationUpdates(locationCallback);
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing: " + e.getMessage());
        }
    }

    private void onLocationUpdated(Location location) {
        AppLogger.log(this, TAG, "GPS Polled - Coordinates: " + location.getLatitude() + ", " + location.getLongitude());
        
        // Refresh config to pick up the latest active room choice
        matrixClient.loadConfig(this);
        
        String currentUserId = userId;
        String displayName = matrixClient.getDisplayName();
        if (displayName != null && !displayName.isEmpty()) {
            currentUserId = displayName;
        }
        
        LocationMessage message = new LocationMessage(currentUserId, location.getLatitude(), location.getLongitude());
        matrixClient.sendLocation(message);
        
        // Clean sender for session file name
        String cleanSender = currentUserId;
        if (cleanSender.contains(":")) {
            cleanSender = cleanSender.split(":")[0];
        }
        if (cleanSender.startsWith("@")) {
            cleanSender = cleanSender.substring(1);
        }
        cleanSender = cleanSender.replaceAll("[^a-zA-Z0-9_.-]", "_");
        
        // Write our own GPS location to sessions folder
        writeSelfLocationToSessions(cleanSender, currentUserId, location.getLatitude(), location.getLongitude());
        
        // Broadcast to Activity if it's running
        Intent intent = new Intent("com.vypeensoft.friendtracker.LOCATION_UPDATE");
        intent.putExtra("latitude", location.getLatitude());
        intent.putExtra("longitude", location.getLongitude());
        sendBroadcast(intent);
    }

    private void writeSelfLocationToSessions(String cleanSender, String displayName, double lat, double lon) {
        String[] paths = {
            "/sdcard/Vypeensoft/Friends_Location_Tracker/sessions",
            "/sdcard/Vypeensoft/Freinds_Location_Tracker/sessions"
        };

        for (String path : paths) {
            java.io.File dir = new java.io.File(path);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (!dir.exists()) {
                dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), path.replace("/sdcard/", ""));
                if (!dir.exists()) {
                    dir.mkdirs();
                }
            }

            java.io.File file = new java.io.File(dir, cleanSender + ".txt");
            try {
                String content = displayName + "|" + lat + "|" + lon + "|#1976D2";
                java.io.FileWriter writer = new java.io.FileWriter(file, false); // false to overwrite
                writer.write(content);
                writer.close();
                Log.i(TAG, "Successfully wrote self location to " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Error writing self location to sessions folder: " + path, e);
            }
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Friend Tracker")
                .setContentText("Tracking location in background...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLogger.log(this, TAG, "LocationService destroyed");
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
