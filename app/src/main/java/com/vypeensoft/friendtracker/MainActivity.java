package com.vypeensoft.friendtracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;

import com.vypeensoft.friendtracker.model.GroupRoom;
import com.vypeensoft.friendtracker.model.LocationMessage;
import com.vypeensoft.friendtracker.network.MatrixClient;

public class MainActivity extends AppCompatActivity {

    private MapView mapView;
    private MapLibreMap mapLibreMap;

    // Dynamic tracking maps
    private final java.util.Map<String, Marker> activeMarkers = new java.util.HashMap<>();
    private final java.util.Map<String, String> activeMarkerColors = new java.util.HashMap<>();
    private final java.util.Map<String, UserLocation> cachedLocations = new java.util.HashMap<>();
    private final java.util.Map<String, Long> fileLastModifiedMap = new java.util.HashMap<>();
    private boolean isFirstCameraCenter = true;

    // Navigation Drawer views
    private androidx.drawerlayout.widget.DrawerLayout drawerLayout;
    private android.widget.ImageButton btnMenu;
    private android.view.View menuSettings;
    private android.view.View menuMatrixCredentials;
    private android.view.View menuMatrixRooms;
    private android.view.View menuHelp;
    private android.view.View menuAbout;

    // Movement Loop Handler
    private final android.os.Handler movementHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable movementRunnable;

    // Matrix Polling
    private MatrixClient matrixClient;
    private final android.os.Handler matrixHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable matrixRunnable;
    private final java.util.Map<String, Long> lastRoomEventTimestamps = new java.util.HashMap<>();

    private static class UserLocation {
        final String username;
        final double latitude;
        final double longitude;
        final int color;
        final String colorName;

        UserLocation(String username, double latitude, double longitude, int color, String colorName) {
            this.username = username;
            this.latitude = latitude;
            this.longitude = longitude;
            this.color = color;
            this.colorName = colorName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize MapLibre engine
        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);

        // Setup Drawer Controls
        drawerLayout = findViewById(R.id.drawer_layout);
        btnMenu = findViewById(R.id.btn_menu);
        menuSettings = findViewById(R.id.menu_settings);
        menuMatrixCredentials = findViewById(R.id.menu_matrix_credentials);
        menuMatrixRooms = findViewById(R.id.menu_matrix_rooms);
        menuHelp = findViewById(R.id.menu_help);
        menuAbout = findViewById(R.id.menu_about);

        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(android.view.Gravity.LEFT));

        menuSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new android.content.Intent(this, MapSettingsActivity.class));
        });

        menuMatrixCredentials.setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new android.content.Intent(this, MatrixCredentialsActivity.class));
        });

        menuMatrixRooms.setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new android.content.Intent(this, GroupsRoomsActivity.class));
        });

        menuHelp.setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new android.content.Intent(this, HelpActivity.class));
        });

        menuAbout.setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new android.content.Intent(this, AboutActivity.class));
        });

        matrixClient = new MatrixClient(this);
        loadMatrixCredentialsOnStartup();

        mapView.getMapAsync(map -> {
            this.mapLibreMap = map;

            // Load style (using OpenFreeMap Liberty style which has detailed streets and requires no API key)
            String styleUrl = "https://tiles.openfreemap.org/styles/liberty";
            map.setStyle(new Style.Builder().fromUri(styleUrl), style -> {
                // Request permissions right away
                checkAndRequestStoragePermissions();

                // Start the polling loop immediately
                startMovementLoop();
            });
        });
    }

    private boolean checkAndRequestStoragePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    android.net.Uri uri = android.net.Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                    android.widget.Toast.makeText(this, "Please grant All Files Access permission for location tracker", android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
                return false;
            }
            return true;
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 100);
                return false;
            }
            return true;
        }
    }

    /**
     * Spawns a repeating 1-second loop that reads dynamic location coordinates from files
     * in /sdcard/Vypeensoft/Friends_Location_Tracker/sessions/ and maps them on screen.
     */
    private void startMovementLoop() {
        if (movementRunnable != null) return; // Already running

        movementRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || mapLibreMap == null) return;

                // 1. Ensure permissions are granted before reading files
                boolean hasPermission = false;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    hasPermission = android.os.Environment.isExternalStorageManager();
                } else {
                    hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            MainActivity.this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                }

                if (hasPermission) {
                    // 2. Read and parse location files
                    java.util.List<UserLocation> loadedLocations = readUserLocationsFromFiles();

                    // 3. Keep track of active usernames
                    java.util.Set<String> newUsernames = new java.util.HashSet<>();
                    for (UserLocation loc : loadedLocations) {
                        newUsernames.add(loc.username);
                    }

                    // 4. Remove markers for users who are no longer present
                    java.util.Iterator<java.util.Map.Entry<String, Marker>> it = activeMarkers.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, Marker> entry = it.next();
                        String username = entry.getKey();
                        if (!newUsernames.contains(username)) {
                            mapLibreMap.removeMarker(entry.getValue());
                            it.remove();
                            activeMarkerColors.remove(username);
                        }
                    }

                    // 5. Add or update markers
                    IconFactory iconFactory = IconFactory.getInstance(MainActivity.this);
                    for (UserLocation loc : loadedLocations) {
                        LatLng position = new LatLng(loc.latitude, loc.longitude);
                        Marker marker = activeMarkers.get(loc.username);

                        String oldColor = activeMarkerColors.get(loc.username);
                        boolean colorChanged = oldColor == null || !oldColor.equalsIgnoreCase(loc.colorName);

                        if (marker == null) {
                            // Create new marker
                            Icon icon = iconFactory.fromBitmap(
                                    createTeardropMarkerBitmap(loc.color, loc.username));
                            Marker newMarker = mapLibreMap.addMarker(new MarkerOptions()
                                    .position(position)
                                    .title(loc.username)
                                    .icon(icon));
                            activeMarkers.put(loc.username, newMarker);
                            activeMarkerColors.put(loc.username, loc.colorName);
                        } else {
                            // Update position
                            marker.setPosition(position);
                            // Update icon if color changed
                            if (colorChanged) {
                                Icon icon = iconFactory.fromBitmap(
                                        createTeardropMarkerBitmap(loc.color, loc.username));
                                marker.setIcon(icon);
                                activeMarkerColors.put(loc.username, loc.colorName);
                            }
                        }
                    }

                    // 6. Camera centering on first successful load
                    if (isFirstCameraCenter && !loadedLocations.isEmpty()) {
                        isFirstCameraCenter = false;
                        double totalLat = 0;
                        double totalLon = 0;
                        for (UserLocation loc : loadedLocations) {
                            totalLat += loc.latitude;
                            totalLon += loc.longitude;
                        }
                        double avgLat = totalLat / loadedLocations.size();
                        double avgLon = totalLon / loadedLocations.size();
                        LatLng center = new LatLng(avgLat, avgLon);
                        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 14.5));
                    }

                    // Force redraw
                    mapView.invalidate();
                }

                // Repeat according to saved polling_interval
                android.content.SharedPreferences prefs = getSharedPreferences("friend_tracker_prefs", MODE_PRIVATE);
                int intervalSec = prefs.getInt("polling_interval", 1);
                if (intervalSec < 1) intervalSec = 1;
                movementHandler.postDelayed(this, intervalSec * 1000L);
            }
        };

        movementHandler.post(movementRunnable);
    }

    private java.util.List<UserLocation> readUserLocationsFromFiles() {
        java.io.File dir = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/sessions");
        if (!dir.exists()) {
            dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/sessions");
        }

        // Auto-create directory and create sample files if empty so it works out of the box
        //createSampleFilesIfEmpty(dir);

        java.util.Set<String> currentFilePaths = new java.util.HashSet<>();

        if (dir.exists() && dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isFile()) {
                        String filePath = file.getAbsolutePath();
                        currentFilePaths.add(filePath);

                        long lastModified = file.lastModified();
                        Long previousModified = fileLastModifiedMap.get(filePath);

                        // If file is new or modified, re-read and parse it
                        if (previousModified == null || lastModified > previousModified) {
                            UserLocation loc = parseLocationFile(file);
                            if (loc != null) {
                                cachedLocations.put(filePath, loc);
                                fileLastModifiedMap.put(filePath, lastModified);
                                android.util.Log.d("FriendTracker", "File changed: " + file.getName() + ", re-reading and updating map.");
                            }
                        }
                    }
                }
            }
        }

        // Clean up cached data for files that were deleted
        java.util.Iterator<java.util.Map.Entry<String, UserLocation>> locIt = cachedLocations.entrySet().iterator();
        while (locIt.hasNext()) {
            java.util.Map.Entry<String, UserLocation> entry = locIt.next();
            if (!currentFilePaths.contains(entry.getKey())) {
                locIt.remove();
            }
        }

        java.util.Iterator<java.util.Map.Entry<String, Long>> modIt = fileLastModifiedMap.entrySet().iterator();
        while (modIt.hasNext()) {
            java.util.Map.Entry<String, Long> entry = modIt.next();
            if (!currentFilePaths.contains(entry.getKey())) {
                modIt.remove();
            }
        }

        // Return a list of all current locations
        return new java.util.ArrayList<>(cachedLocations.values());
    }

    private UserLocation parseLocationFile(java.io.File file) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    String username = parts[0].trim();
                    double latitude = Double.parseDouble(parts[1].trim());
                    double longitude = Double.parseDouble(parts[2].trim());
                    String colorName = parts[3].trim();
                    int color = parseColor(colorName);
                    return new UserLocation(username, latitude, longitude, color, colorName);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error parsing location file: " + file.getName(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        return null;
    }

    private int parseColor(String colorStr) {
        if (colorStr == null) return Color.RED;
        colorStr = colorStr.trim().toLowerCase();
        switch (colorStr) {
            case "red": return Color.parseColor("#E53935");
            case "green": return Color.parseColor("#4CAF50");
            case "blue": return Color.parseColor("#2196F3");
            case "yellow": return Color.parseColor("#FFEB3B");
            case "orange": return Color.parseColor("#FF9800");
            case "purple": return Color.parseColor("#9C27B0");
            case "pink": return Color.parseColor("#E91E63");
            case "teal": return Color.parseColor("#009688");
            case "cyan": return Color.parseColor("#00BCD4");
            case "magenta": return Color.parseColor("#FF00FF");
            case "black": return Color.BLACK;
            case "white": return Color.WHITE;
            case "gray":
            case "grey": return Color.GRAY;
            default:
                try {
                    if (!colorStr.startsWith("#")) {
                        return Color.parseColor("#" + colorStr);
                    }
                    return Color.parseColor(colorStr);
                } catch (Exception e) {
                    return Color.RED; // fallback
                }
        }
    }

    private void createSampleFilesIfEmpty(java.io.File dir) {
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                // Write 3 sample files for Red, Green, and Blue friends
                writeSampleFile(new java.io.File(dir, "friend_red.txt"), "Red Friend|9.9312|76.2673|Red");
                writeSampleFile(new java.io.File(dir, "friend_green.txt"), "Green Friend|9.9412|76.2773|Green");
                writeSampleFile(new java.io.File(dir, "friend_blue.txt"), "Blue Friend|9.9212|76.2573|Blue");
            }
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error creating sample files", e);
        }
    }

    private void writeSampleFile(java.io.File file, String content) {
        java.io.FileWriter writer = null;
        try {
            writer = new java.io.FileWriter(file);
            writer.write(content);
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error writing sample file: " + file.getName(), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Removes the loop execution task safely.
     */
    private void stopMovementLoop() {
        if (movementRunnable != null) {
            movementHandler.removeCallbacks(movementRunnable);
            movementRunnable = null;
        }
    }

    /**
     * Programmatically draws a beautiful classic teardrop vector map pin with a dynamic floating name badge.
     */
    private Bitmap createTeardropMarkerBitmap(int color, String text) {
        int width = 160;
        int height = 192;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);

        // 1. Draw soft drop shadow under the tip
        paint.setColor(Color.parseColor("#22000000"));
        canvas.drawOval(new RectF(80 - 18, 160 - 4, 80 + 18, 160 + 4), paint);

        // 2. Draw white outer pin border
        paint.setColor(Color.WHITE);
        Path outerPath = new Path();
        outerPath.moveTo(80, 160); // Tip at bottom
        outerPath.lineTo(46, 100); // Left tangent
        outerPath.arcTo(new RectF(46, 62, 114, 130), 150, 240, false); // Top circle
        outerPath.close();
        canvas.drawPath(outerPath, paint);

        // 3. Draw colored inner pin core
        paint.setColor(color);
        Path innerPath = new Path();
        innerPath.moveTo(80, 152); // Tip at bottom
        innerPath.lineTo(52, 102); // Left tangent
        innerPath.arcTo(new RectF(52, 68, 108, 124), 150, 240, false); // Top circle
        innerPath.close();
        canvas.drawPath(innerPath, paint);

        // 4. Draw central white glowing dot inside the head of the pin
        paint.setColor(Color.WHITE);
        canvas.drawCircle(80, 96, 12, paint);

        // 5. Draw connecting line/stem from capsule to the pin head top
        Paint connectorPaint = new Paint();
        connectorPaint.setAntiAlias(true);
        connectorPaint.setStyle(Paint.Style.STROKE);
        connectorPaint.setStrokeWidth(4.0f);
        connectorPaint.setColor(color);
        canvas.drawLine(80, 48, 80, 64, connectorPaint);

        // 6. Draw name tag capsule
        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(20);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        float textWidth = textPaint.measureText(text);
        float capsuleWidth = textWidth + 28; // 14px padding on each side
        float top = 12;
        float bottom = 48;
        float left = 80 - capsuleWidth / 2;
        float right = 80 + capsuleWidth / 2;

        // Capsule Shadow
        Paint shadowPaint = new Paint();
        shadowPaint.setAntiAlias(true);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.parseColor("#25000000"));
        canvas.drawRoundRect(left + 2, top + 2, right + 2, bottom + 2, 18, 18, shadowPaint);

        // Capsule Background (Premium Dark Charcoal)
        Paint bgPaint = new Paint();
        bgPaint.setAntiAlias(true);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.parseColor("#1E1E24"));
        canvas.drawRoundRect(left, top, right, bottom, 18, 18, bgPaint);

        // Capsule Border (Accent colored matching the pin)
        Paint borderPaint = new Paint();
        borderPaint.setAntiAlias(true);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3.0f);
        borderPaint.setColor(color);
        canvas.drawRoundRect(left, top, right, bottom, 18, 18, borderPaint);

        // Capsule Text (White, perfectly vertically centered)
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = (top + bottom) / 2 - (fm.ascent + fm.descent) / 2;
        canvas.drawText(text, 80, textY, textPaint);

        return bitmap;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        // Import settings in case they were modified/saved in Settings Activities
        com.vypeensoft.friendtracker.util.SettingsPersistenceManager.importSettings(this);
        loadMatrixCredentialsOnStartup();
        if (matrixClient != null) {
            matrixClient.loadConfig(this);
        }
        if (mapLibreMap != null) {
            startMovementLoop();
        }
        restartMatrixPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        stopMovementLoop();
        stopMatrixPolling();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
        stopMovementLoop();
        stopMatrixPolling();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    // ==========================================
    // MATRIX INTEGRATION HELPER METHODS & WIDGETS
    // ==========================================

    private int getUniqueColorForUser(String userId) {
        if (userId == null || userId.isEmpty()) {
            return Color.parseColor("#00E5FF"); // Neon Cyan fallback
        }
        int hash = userId.hashCode();
        float hue = 60f + Math.abs(hash % 240);
        float saturation = 0.90f;
        float value = 0.85f;
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    private void updateFriendMarker(String username, double latitude, double longitude) {
        if (mapLibreMap == null) return;
        LatLng position = new LatLng(latitude, longitude);
        Marker marker = activeMarkers.get(username);
        IconFactory iconFactory = IconFactory.getInstance(MainActivity.this);
        int color = getUniqueColorForUser(username);

        if (marker == null) {
            Icon icon = iconFactory.fromBitmap(createTeardropMarkerBitmap(color, username));
            Marker newMarker = mapLibreMap.addMarker(new MarkerOptions()
                    .position(position)
                    .title(username)
                    .icon(icon));
            activeMarkers.put(username, newMarker);
            activeMarkerColors.put(username, "MatrixUnique");
        } else {
            marker.setPosition(position);
        }
        mapView.invalidate();
    }

    private java.util.List<GroupRoom> loadMatrixRoomsFromFile() {
        java.io.File file = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings/rooms_settings.json");
        if (!file.exists()) {
            file = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/settings/rooms_settings.json");
        }

        if (file.exists()) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();

                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                if (obj.has("matrix_rooms")) {
                    String roomsStr = obj.getString("matrix_rooms");
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.ArrayList<GroupRoom>>() {}.getType();
                    String sanitized = roomsStr.replace('\'', '"');
                    java.util.List<GroupRoom> loaded = gson.fromJson(sanitized, type);
                    if (loaded != null && !loaded.isEmpty()) {
                        return loaded;
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("FriendTracker", "Error loading matrix rooms from JSON file", e);
            }
        }
        return null;
    }

    private void saveMatrixRoomsToFile(java.util.List<GroupRoom> rooms) {
        try {
            java.io.File dir = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File file = new java.io.File(dir, "rooms_settings.json");

            com.google.gson.Gson gson = new com.google.gson.Gson();
            String roomsJsonStr = gson.toJson(rooms);

            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("matrix_rooms", roomsJsonStr);

            java.io.FileWriter writer = new java.io.FileWriter(file);
            writer.write(obj.toString(4));
            writer.flush();
            writer.close();
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error saving matrix rooms to JSON file", e);
        }
    }

    private java.util.List<GroupRoom> loadMatrixRooms() {
        java.util.List<GroupRoom> fileRooms = loadMatrixRoomsFromFile();
        if (fileRooms != null) {
            try {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                String jsonStr = gson.toJson(fileRooms);
                getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(MatrixClient.KEY_MATRIX_ROOMS, jsonStr)
                        .apply();
            } catch (Exception e) {
                // ignore
            }
            return fileRooms;
        }

        java.util.List<GroupRoom> rooms = new java.util.ArrayList<>();
        android.content.SharedPreferences prefs = getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE);
        String jsonStr = prefs.getString(MatrixClient.KEY_MATRIX_ROOMS, null);
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            rooms.add(new GroupRoom("General Room", "!sample_room_id:matrix.org", true));
            saveMatrixRooms(rooms);
            return rooms;
        }

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.ArrayList<GroupRoom>>() {}.getType();
            java.util.List<GroupRoom> loaded = gson.fromJson(jsonStr, type);
            if (loaded != null) {
                rooms.addAll(loaded);
            }
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error loading matrix rooms", e);
        }
        return rooms;
    }

    private void saveMatrixRooms(java.util.List<GroupRoom> rooms) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            String jsonStr = gson.toJson(rooms);
            getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(MatrixClient.KEY_MATRIX_ROOMS, jsonStr)
                    .apply();
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error saving matrix rooms", e);
        }
        saveMatrixRoomsToFile(rooms);
    }

    private void showSettingsDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Application Settings");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 36, 48, 36);

        // Section Title: "GPS Configuration" styled like reference sub-heading
        android.widget.TextView sectionTitle = new android.widget.TextView(this);
        sectionTitle.setText("GPS Configuration");
        sectionTitle.setTextSize(16);
        sectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionTitle.setTextColor(Color.parseColor("#1976D2"));
        sectionTitle.setPadding(0, 0, 0, 16);
        layout.addView(sectionTitle);

        // Create TextInputLayout and TextInputEditText programmatically to mirror reference styling
        com.google.android.material.textfield.TextInputLayout textInputLayout = 
            new com.google.android.material.textfield.TextInputLayout(this);
        textInputLayout.setHint("GPS Files Polling Interval (seconds)");
        textInputLayout.setHelperText("Enter a Polling Interval (e.g. 1, 5 or 10 seconds).");
        textInputLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        textInputLayout.setBoxStrokeColor(Color.parseColor("#1976D2"));
        
        com.google.android.material.textfield.TextInputEditText input = 
            new com.google.android.material.textfield.TextInputEditText(textInputLayout.getContext());
        android.content.SharedPreferences prefs = getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE);
        int currentInterval = prefs.getInt("polling_interval", 1);
        input.setText(String.valueOf(currentInterval));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        
        textInputLayout.addView(input);
        layout.addView(textInputLayout);

        builder.setView(layout);
        builder.setPositiveButton("Save", (dialog, which) -> {
            try {
                int newVal = Integer.parseInt(input.getText().toString().trim());
                if (newVal < 1) newVal = 1;
                prefs.edit().putInt("polling_interval", newVal).apply();
                android.widget.Toast.makeText(this, "Settings saved successfully", android.widget.Toast.LENGTH_SHORT).show();
                stopMovementLoop();
                startMovementLoop();
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Invalid interval", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private android.widget.TextView createLabel(String text) {
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(text);
        label.setTextColor(Color.parseColor("#1976D2"));
        label.setTextSize(14);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, 16, 0, 4);
        return label;
    }

    private android.widget.EditText createInput(String value) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(value);
        input.setTextSize(16);
        return input;
    }

    private void loadMatrixCredentialsOnStartup() {
        java.io.File file = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings/matrix_credentials.json");
        if (!file.exists()) {
            file = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings/matrix_credentails.json");
        }
        if (!file.exists()) {
            file = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/settings/matrix_credentials.json");
        }
        if (!file.exists()) {
            file = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/settings/matrix_credentails.json");
        }

        if (file.exists()) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();

                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                android.content.SharedPreferences prefs = getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE);
                android.content.SharedPreferences.Editor editor = prefs.edit();

                if (obj.has("matrix_homeserver")) {
                    try { editor.putString(MatrixClient.KEY_MATRIX_HOMESERVER, obj.getString("matrix_homeserver")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_username")) {
                    try { editor.putString(MatrixClient.KEY_MATRIX_USERNAME, obj.getString("matrix_username")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_password")) {
                    try { editor.putString(MatrixClient.KEY_MATRIX_PASSWORD, obj.getString("matrix_password")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_token")) {
                    try { editor.putString(MatrixClient.KEY_MATRIX_TOKEN, obj.getString("matrix_token")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_display_name")) {
                    try { editor.putString(MatrixClient.KEY_MATRIX_DISPLAY_NAME, obj.getString("matrix_display_name")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_polling_period")) {
                    try {
                        editor.putLong(MatrixClient.KEY_MATRIX_POLLING_PERIOD, obj.getLong("matrix_polling_period"));
                    } catch (Exception e) {
                        try {
                            String periodStr = obj.getString("matrix_polling_period");
                            editor.putLong(MatrixClient.KEY_MATRIX_POLLING_PERIOD, Long.parseLong(periodStr));
                        } catch (Exception ignored) {}
                    }
                }
                editor.apply();
                if (matrixClient != null) {
                    matrixClient.loadConfig(this);
                }
            } catch (Exception e) {
                android.util.Log.e("FriendTracker", "Error loading credentials from JSON file on startup", e);
            }
        }
    }

    private void loadMatrixCredentialsFromFile(
            android.widget.EditText editHomeserver,
            android.widget.EditText editUsername,
            android.widget.EditText editPassword,
            android.widget.EditText editToken,
            android.widget.EditText editDisplayName,
            android.widget.EditText editPollPeriod) {
        java.io.File file = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings/matrix_credentials.json");
        if (!file.exists()) {
            file = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings/matrix_credentails.json");
        }
        if (!file.exists()) {
            file = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/settings/matrix_credentials.json");
        }
        if (!file.exists()) {
            file = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/settings/matrix_credentails.json");
        }

        if (file.exists()) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();

                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                if (obj.has("matrix_homeserver")) {
                    try { editHomeserver.setText(obj.getString("matrix_homeserver")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_username")) {
                    try { editUsername.setText(obj.getString("matrix_username")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_password")) {
                    try { editPassword.setText(obj.getString("matrix_password")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_token")) {
                    try { editToken.setText(obj.getString("matrix_token")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_display_name")) {
                    try { editDisplayName.setText(obj.getString("matrix_display_name")); } catch (Exception ignored) {}
                }
                if (obj.has("matrix_polling_period")) {
                    try {
                        long periodMs = obj.getLong("matrix_polling_period");
                        editPollPeriod.setText(String.valueOf(periodMs / 1000L));
                    } catch (Exception e) {
                        try {
                            String periodStr = obj.getString("matrix_polling_period");
                            long periodMs = Long.parseLong(periodStr);
                            editPollPeriod.setText(String.valueOf(periodMs / 1000L));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("FriendTracker", "Error loading credentials from JSON file", e);
            }
        }
    }

    private void saveMatrixCredentialsToFile(
            String homeserver,
            String username,
            String password,
            String token,
            String displayName,
            long pollingPeriodMs) {
        try {
            java.io.File dir = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/settings");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File file = new java.io.File(dir, "matrix_credentials.json");
            java.io.File fileAlt = new java.io.File(dir, "matrix_credentails.json");

            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("matrix_homeserver", homeserver);
            obj.put("matrix_username", username);
            obj.put("matrix_password", password);
            obj.put("matrix_token", token);
            obj.put("matrix_display_name", displayName);
            obj.put("matrix_polling_period", pollingPeriodMs);

            String jsonStr = obj.toString(4);

            java.io.FileWriter writer1 = new java.io.FileWriter(file);
            writer1.write(jsonStr);
            writer1.flush();
            writer1.close();

            java.io.FileWriter writer2 = new java.io.FileWriter(fileAlt);
            writer2.write(jsonStr);
            writer2.flush();
            writer2.close();
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error saving credentials to JSON file", e);
        }
    }

    private void showMatrixCredentialsDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = 
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("Matrix Credentials");

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 36, 48, 36);
        scrollView.addView(layout);

        android.content.SharedPreferences prefs = getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE);

        // Connection & Account Setup Section Title
        android.widget.TextView secTitle = new android.widget.TextView(this);
        secTitle.setText("Connection & Account Setup");
        secTitle.setTextSize(15);
        secTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        secTitle.setTextColor(Color.parseColor("#1976D2"));
        secTitle.setPadding(0, 0, 0, 20);
        layout.addView(secTitle);

        // Material Form Fields
        com.google.android.material.textfield.TextInputLayout lHomeserver = new com.google.android.material.textfield.TextInputLayout(this);
        lHomeserver.setHint("Homeserver URL");
        lHomeserver.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lHomeserver.setBoxStrokeColor(Color.parseColor("#1976D2"));
        com.google.android.material.textfield.TextInputEditText editHomeserver = new com.google.android.material.textfield.TextInputEditText(lHomeserver.getContext());
        editHomeserver.setText(prefs.getString(MatrixClient.KEY_MATRIX_HOMESERVER, "https://matrix-client.matrix.org"));
        lHomeserver.addView(editHomeserver);
        lHomeserver.setPadding(0, 0, 0, 16);
        layout.addView(lHomeserver);

        com.google.android.material.textfield.TextInputLayout lUsername = new com.google.android.material.textfield.TextInputLayout(this);
        lUsername.setHint("Username");
        lUsername.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lUsername.setBoxStrokeColor(Color.parseColor("#1976D2"));
        com.google.android.material.textfield.TextInputEditText editUsername = new com.google.android.material.textfield.TextInputEditText(lUsername.getContext());
        editUsername.setText(prefs.getString(MatrixClient.KEY_MATRIX_USERNAME, ""));
        lUsername.addView(editUsername);
        lUsername.setPadding(0, 0, 0, 16);
        layout.addView(lUsername);

        com.google.android.material.textfield.TextInputLayout lPassword = new com.google.android.material.textfield.TextInputLayout(this);
        lPassword.setHint("Password");
        lPassword.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lPassword.setBoxStrokeColor(Color.parseColor("#1976D2"));
        lPassword.setPasswordVisibilityToggleEnabled(true);
        com.google.android.material.textfield.TextInputEditText editPassword = new com.google.android.material.textfield.TextInputEditText(lPassword.getContext());
        editPassword.setText(prefs.getString(MatrixClient.KEY_MATRIX_PASSWORD, ""));
        editPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        lPassword.addView(editPassword);
        lPassword.setPadding(0, 0, 0, 16);
        layout.addView(lPassword);

        com.google.android.material.textfield.TextInputLayout lToken = new com.google.android.material.textfield.TextInputLayout(this);
        lToken.setHint("Access Token (Cached)");
        lToken.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lToken.setBoxStrokeColor(Color.parseColor("#1976D2"));
        com.google.android.material.textfield.TextInputEditText editToken = new com.google.android.material.textfield.TextInputEditText(lToken.getContext());
        editToken.setText(prefs.getString(MatrixClient.KEY_MATRIX_TOKEN, ""));
        lToken.addView(editToken);
        lToken.setPadding(0, 0, 0, 16);
        layout.addView(lToken);

        com.google.android.material.textfield.TextInputLayout lDisplayName = new com.google.android.material.textfield.TextInputLayout(this);
        lDisplayName.setHint("Display Name");
        lDisplayName.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lDisplayName.setBoxStrokeColor(Color.parseColor("#1976D2"));
        com.google.android.material.textfield.TextInputEditText editDisplayName = new com.google.android.material.textfield.TextInputEditText(lDisplayName.getContext());
        editDisplayName.setText(prefs.getString(MatrixClient.KEY_MATRIX_DISPLAY_NAME, ""));
        lDisplayName.addView(editDisplayName);
        lDisplayName.setPadding(0, 0, 0, 16);
        layout.addView(lDisplayName);

        com.google.android.material.textfield.TextInputLayout lPollPeriod = new com.google.android.material.textfield.TextInputLayout(this);
        lPollPeriod.setHint("Polling Period (seconds)");
        lPollPeriod.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lPollPeriod.setBoxStrokeColor(Color.parseColor("#1976D2"));
        lPollPeriod.setHelperText("Select interval to query room messages.");
        com.google.android.material.textfield.TextInputEditText editPollPeriod = new com.google.android.material.textfield.TextInputEditText(lPollPeriod.getContext());
        long currentPeriodMs = prefs.getLong(MatrixClient.KEY_MATRIX_POLLING_PERIOD, 5000L);
        editPollPeriod.setText(String.valueOf(currentPeriodMs / 1000L));
        editPollPeriod.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        lPollPeriod.addView(editPollPeriod);
        lPollPeriod.setPadding(0, 0, 0, 16);
        layout.addView(lPollPeriod);

        // Load values from JSON file if it exists, over-writing SharedPreferences defaults
        loadMatrixCredentialsFromFile(editHomeserver, editUsername, editPassword, editToken, editDisplayName, editPollPeriod);

        builder.setView(scrollView);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String homeserver = editHomeserver.getText().toString().trim();
            String username = editUsername.getText().toString().trim();
            String password = editPassword.getText().toString().trim();
            String token = editToken.getText().toString().trim();
            String displayName = editDisplayName.getText().toString().trim();
            String pollStr = editPollPeriod.getText().toString().trim();

            if (homeserver.isEmpty()) {
                android.widget.Toast.makeText(this, "Homeserver is required", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            long pollPeriodMs = 5000L;
            try {
                long pollSec = Long.parseLong(pollStr);
                if (pollSec < 1) pollSec = 1;
                pollPeriodMs = pollSec * 1000L;
            } catch (Exception e) {
                // use default
            }

            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putString(MatrixClient.KEY_MATRIX_HOMESERVER, homeserver);
            editor.putString(MatrixClient.KEY_MATRIX_USERNAME, username);
            editor.putString(MatrixClient.KEY_MATRIX_PASSWORD, password);
            editor.putString(MatrixClient.KEY_MATRIX_DISPLAY_NAME, displayName);
            editor.putLong(MatrixClient.KEY_MATRIX_POLLING_PERIOD, pollPeriodMs);

            // If credentials changed, clear old cached token so it triggers login
            String oldUser = prefs.getString(MatrixClient.KEY_MATRIX_USERNAME, "");
            String oldPass = prefs.getString(MatrixClient.KEY_MATRIX_PASSWORD, "");
            if (!oldUser.equals(username) || !oldPass.equals(password)) {
                editor.remove(MatrixClient.KEY_MATRIX_TOKEN);
            } else if (!token.isEmpty()) {
                editor.putString(MatrixClient.KEY_MATRIX_TOKEN, token);
            }

            editor.apply();

            // Save to JSON file as well to keep them synchronized
            saveMatrixCredentialsToFile(homeserver, username, password, token, displayName, pollPeriodMs);

            matrixClient.loadConfig(this);
            restartMatrixPolling();
            android.widget.Toast.makeText(this, "Credentials saved successfully", android.widget.Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showMatrixRoomsDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = 
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("Matrix Rooms Manager");

        android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(this);
        rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        rootLayout.setPadding(48, 36, 48, 36);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout listLayout = new android.widget.LinearLayout(this);
        listLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        scrollView.addView(listLayout);

        java.util.List<GroupRoom> rooms = loadMatrixRooms();

        android.widget.LinearLayout headers = new android.widget.LinearLayout(this);
        headers.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headers.setPadding(0, 0, 0, 16);

        android.widget.TextView headActive = new android.widget.TextView(this);
        headActive.setText("Active");
        headActive.setTextSize(14);
        headActive.setTextColor(Color.parseColor("#1976D2"));
        headActive.setTypeface(null, android.graphics.Typeface.BOLD);
        headActive.setLayoutParams(new android.widget.LinearLayout.LayoutParams(120, -2));
        headers.addView(headActive);

        android.widget.TextView headName = new android.widget.TextView(this);
        headName.setText("Room Name");
        headName.setTextSize(14);
        headName.setTextColor(Color.parseColor("#1976D2"));
        headName.setTypeface(null, android.graphics.Typeface.BOLD);
        headName.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1.0f));
        headers.addView(headName);

        android.widget.TextView headId = new android.widget.TextView(this);
        headId.setText("Room ID");
        headId.setTextSize(14);
        headId.setTextColor(Color.parseColor("#1976D2"));
        headId.setTypeface(null, android.graphics.Typeface.BOLD);
        headId.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1.2f));
        headers.addView(headId);

        android.view.View deleteHeadSpacer = new android.view.View(this);
        deleteHeadSpacer.setLayoutParams(new android.widget.LinearLayout.LayoutParams(80, 1));
        headers.addView(deleteHeadSpacer);

        listLayout.addView(headers);

        populateRoomsList(listLayout, rooms);

        rootLayout.addView(scrollView, new android.widget.LinearLayout.LayoutParams(-1, 500));

        com.google.android.material.button.MaterialButton btnAdd = new com.google.android.material.button.MaterialButton(this);
        btnAdd.setText("+ Add Matrix Room");
        btnAdd.setTextColor(Color.WHITE);
        btnAdd.setBackgroundColor(Color.parseColor("#1976D2"));
        btnAdd.setCornerRadius(8);
        android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        btnParams.setMargins(0, 24, 0, 0);
        btnAdd.setLayoutParams(btnParams);
        rootLayout.addView(btnAdd);

        builder.setView(rootLayout);
        builder.setPositiveButton("Close", null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();

        btnAdd.setOnClickListener(v -> {
            showAddRoomDialog(listLayout, dialog);
        });

        dialog.show();
    }

    private void populateRoomsList(android.widget.LinearLayout listLayout, java.util.List<GroupRoom> rooms) {
        int childCount = listLayout.getChildCount();
        if (childCount > 1) {
            listLayout.removeViews(1, childCount - 1);
        }

        if (rooms.isEmpty()) {
            android.widget.TextView emptyText = new android.widget.TextView(this);
            emptyText.setText("No registered rooms found.");
            emptyText.setPadding(0, 32, 0, 32);
            emptyText.setGravity(android.view.Gravity.CENTER);
            emptyText.setTextColor(Color.GRAY);
            listLayout.addView(emptyText);
            return;
        }

        for (int i = 0; i < rooms.size(); i++) {
            final GroupRoom room = rooms.get(i);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 12, 0, 12);
            row.setBackground(createRowDividerDrawable());

            android.widget.RadioButton rbActive = new android.widget.RadioButton(this);
            rbActive.setChecked(room.isActive());
            rbActive.setLayoutParams(new android.widget.LinearLayout.LayoutParams(120, -2));
            rbActive.setOnClickListener(v -> {
                for (GroupRoom r : rooms) {
                    r.setActive(r == room);
                }
                saveMatrixRooms(rooms);
                matrixClient.loadConfig(this);
                populateRoomsList(listLayout, rooms);
                restartMatrixPolling();
                android.widget.Toast.makeText(this, "Active room set to: " + room.getName(), android.widget.Toast.LENGTH_SHORT).show();
            });
            row.addView(rbActive);

            android.widget.TextView textName = new android.widget.TextView(this);
            textName.setText(room.getName());
            textName.setTextSize(14);
            textName.setTypeface(null, android.graphics.Typeface.BOLD);
            textName.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1.0f));
            row.addView(textName);

            android.widget.TextView textId = new android.widget.TextView(this);
            textId.setText(room.getRoomId());
            textId.setTextSize(13);
            textId.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1.2f));
            row.addView(textId);

            android.widget.ImageButton btnDelete = new android.widget.ImageButton(this);
            btnDelete.setImageResource(android.R.drawable.ic_menu_delete);
            btnDelete.setBackground(null);
            btnDelete.setLayoutParams(new android.widget.LinearLayout.LayoutParams(80, 80));
            btnDelete.setOnClickListener(v -> {
                boolean wasActive = room.isActive();
                rooms.remove(room);
                if (wasActive && !rooms.isEmpty()) {
                    rooms.get(0).setActive(true);
                }
                saveMatrixRooms(rooms);
                matrixClient.loadConfig(this);
                populateRoomsList(listLayout, rooms);
                restartMatrixPolling();
                android.widget.Toast.makeText(this, "Room removed: " + room.getName(), android.widget.Toast.LENGTH_SHORT).show();
            });
            row.addView(btnDelete);

            listLayout.addView(row);
        }
    }

    private android.graphics.drawable.Drawable createRowDividerDrawable() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setStroke(2, Color.parseColor("#ECEFF1"));
        return gd;
    }

    private void showAddRoomDialog(android.widget.LinearLayout listLayout, androidx.appcompat.app.AlertDialog parentDialog) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = 
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("Add Matrix Room");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 36, 48, 36);

        com.google.android.material.textfield.TextInputLayout lName = new com.google.android.material.textfield.TextInputLayout(this);
        lName.setHint("Room Display Name");
        lName.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lName.setBoxStrokeColor(Color.parseColor("#1976D2"));
        com.google.android.material.textfield.TextInputEditText editName = new com.google.android.material.textfield.TextInputEditText(lName.getContext());
        lName.addView(editName);
        lName.setPadding(0, 0, 0, 16);
        layout.addView(lName);

        com.google.android.material.textfield.TextInputLayout lId = new com.google.android.material.textfield.TextInputLayout(this);
        lId.setHint("Room Identifier (e.g. !room:matrix.org)");
        lId.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        lId.setBoxStrokeColor(Color.parseColor("#1976D2"));
        com.google.android.material.textfield.TextInputEditText editId = new com.google.android.material.textfield.TextInputEditText(lId.getContext());
        lId.addView(editId);
        lId.setPadding(0, 0, 0, 16);
        layout.addView(lId);

        builder.setView(layout);
        builder.setPositiveButton("Add", (dialog, which) -> {
            String name = editName.getText().toString().trim();
            String id = editId.getText().toString().trim();

            if (!name.isEmpty() && !id.isEmpty()) {
                java.util.List<GroupRoom> rooms = loadMatrixRooms();
                boolean isFirst = rooms.isEmpty();
                rooms.add(new GroupRoom(name, id, isFirst));
                saveMatrixRooms(rooms);
                matrixClient.loadConfig(this);
                populateRoomsList(listLayout, rooms);
                restartMatrixPolling();
                android.widget.Toast.makeText(this, "Room added: " + name, android.widget.Toast.LENGTH_SHORT).show();
            } else {
                android.widget.Toast.makeText(this, "Both fields are required", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showHelpDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = 
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("Integration Help Guide");

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 36, 48, 36);
        scrollView.addView(layout);

        android.widget.TextView text = new android.widget.TextView(this);
        text.setTextColor(Color.parseColor("#37474F"));
        text.setTextSize(15);
        text.setLineSpacing(6f, 1.2f);
        text.setText(
                "Welcome to Friend Location Tracker with Matrix Protocol!\n\n" +
                "1. Storage Sessions:\n" +
                "The app polls the external folder `/sdcard/Vypeensoft/Friends_Location_Tracker/sessions/` " +
                "every second to read location files formatted as `Username|Latitude|Longitude|Color` and display them on the map.\n\n" +
                "2. Matrix Chat Integration:\n" +
                "You can configure Matrix Homeserver credentials in the drawer. The app polls the registered rooms on a background network thread " +
                "using standard sync/messages APIs and detects incoming chat events in real-time.\n\n" +
                "3. Rooms List:\n" +
                "You can register and manage any number of Matrix rooms in the 'Matrix Rooms' screen using a dual-column layout."
        );
        layout.addView(text);

        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showAboutDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = 
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("About App");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Friend Location Tracker");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1E1E24"));
        title.setPadding(0, 0, 0, 8);
        layout.addView(title);

        android.widget.TextView version = new android.widget.TextView(this);
        version.setText("Version 1.0 (PRO Edition)");
        version.setTextSize(14);
        version.setTextColor(Color.parseColor("#78909C"));
        version.setPadding(0, 0, 0, 24);
        layout.addView(version);

        android.widget.TextView buildInfo = new android.widget.TextView(this);
        buildInfo.setText(
                "Build Time: " + BuildConfig.BUILD_TIMESTAMP + "\n" +
                "Git SHA: " + BuildConfig.GIT_SHA + "\n" +
                "Build Type: Debug"
        );
        buildInfo.setTextSize(12);
        buildInfo.setTextColor(Color.parseColor("#90A4AE"));
        buildInfo.setGravity(android.view.Gravity.CENTER);
        layout.addView(buildInfo);

        builder.setView(layout);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    // ==========================================
    // MATRIX BACKGROUND POLLING IMPLEMENTATION
    // ==========================================

    private void startMatrixPolling() {
        if (matrixRunnable != null) return;

        android.content.SharedPreferences prefs = getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE);
        long pollPeriodMs = prefs.getLong(MatrixClient.KEY_MATRIX_POLLING_PERIOD, 5000L);

        matrixRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed()) return;

                if (matrixClient != null && matrixClient.isConfigured()) {
                    matrixClient.fetchMessages(new MatrixClient.MatrixListener() {
                        @Override
                        public void onNewMessagesReceived(String rawJson) {
                            runOnUiThread(() -> parseAndShowFriends(rawJson));
                        }
                    });
                }

                android.content.SharedPreferences currentPrefs = getSharedPreferences(MatrixClient.PREFS_NAME, MODE_PRIVATE);
                long period = currentPrefs.getLong(MatrixClient.KEY_MATRIX_POLLING_PERIOD, 5000L);
                matrixHandler.postDelayed(this, period);
            }
        };

        matrixHandler.post(matrixRunnable);
    }

    private void stopMatrixPolling() {
        if (matrixRunnable != null) {
            matrixHandler.removeCallbacks(matrixRunnable);
            matrixRunnable = null;
        }
    }

    private void restartMatrixPolling() {
        stopMatrixPolling();
        startMatrixPolling();
    }

    private void parseAndShowFriends(String rawJson) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(rawJson);
            org.json.JSONArray chunk = obj.optJSONArray("chunk");
            if (chunk == null) return;

            for (int i = chunk.length() - 1; i >= 0; i--) {
                org.json.JSONObject event = chunk.getJSONObject(i);
                String type = event.optString("type");
                
                if ("m.room.message".equals(type)) {
                    final String sender = event.optString("sender");
                    long originServerTs = event.optLong("origin_server_ts");
                    org.json.JSONObject content = event.optJSONObject("content");
                    
                    if (content != null) {
                        final String body = content.optString("body");
                        
                        Long lastTs = lastRoomEventTimestamps.get(matrixClient.getRoomId());
                        if (lastTs == null || originServerTs > lastTs) {
                            lastRoomEventTimestamps.put(matrixClient.getRoomId(), originServerTs);
                            
                            // Check if this is a location update message body format: userId|lat|lon|ts
                            if (body != null && body.contains("|")) {
                                String[] parts = body.split("\\|");
                                if (parts.length >= 3) {
                                    try {
                                        String senderId = parts[0];
                                        String myDisplayName = matrixClient.getDisplayName();
                                        String myDefaultId = "user_" + android.os.Build.ID;
                                        String myEffectiveId = (myDisplayName != null && !myDisplayName.isEmpty())
                                                ? myDisplayName : myDefaultId;

                                        if (!senderId.equals(myEffectiveId)) {
                                            double friendLat = Double.parseDouble(parts[1]);
                                            double friendLon = Double.parseDouble(parts[2]);
                                            runOnUiThread(() -> {
                                                updateFriendMarker(senderId, friendLat, friendLon);
                                            });
                                        }
                                    } catch (Exception e) {
                                        android.util.Log.e("FriendTracker", "Error parsing pipe delimited location", e);
                                    }
                                }
                            }
                            
                            if (lastTs != null) {
                                runOnUiThread(() -> {
                                    // Find current room display name
                                    java.util.List<GroupRoom> rooms = loadMatrixRooms();
                                    String roomName = "Unknown";
                                    for (GroupRoom r : rooms) {
                                        if (r.getRoomId().equals(matrixClient.getRoomId())) {
                                            roomName = r.getName();
                                            break;
                                        }
                                    }
                                    dispatchMatrixMessage(roomName, sender, body);
                                });
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("FriendTracker", "Error parsing messages response", e);
        }
    }

    private void dispatchMatrixMessage(String roomName, String sender, String body) {
        android.util.Log.i("FriendTracker", "Matrix Message Received in room [" + roomName + "] from " + sender + ": " + body);

        String cleanSender = sender;
        if (sender.contains(":")) {
            cleanSender = sender.split(":")[0];
        }
        if (cleanSender.startsWith("@")) {
            cleanSender = cleanSender.substring(1);
        }

        if (body != null && body.contains("|")) {
            String[] colors = {
                "#E53935", "#D81B60", "#8E24AA", "#5E35B1", "#3949AB", 
                "#1E88E5", "#039BE5", "#00ACC1", "#00897B", "#43A047", 
                "#7CB342", "#FDD835", "#FFB300", "#F4511E"
            };
            int randomIndex = new java.util.Random().nextInt(colors.length);
            String randomColor = colors[randomIndex];

            String content = body.trim() + "|" + randomColor;
            
            java.io.File dir = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/sessions");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (!dir.exists()) {
                dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/sessions");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
            }

            java.io.File file = new java.io.File(dir, cleanSender + ".txt");
            try {
                java.io.FileWriter writer = new java.io.FileWriter(file, false);
                writer.write(content);
                writer.close();
                android.util.Log.i("FriendTracker", "Successfully saved session to " + file.getAbsolutePath());
            } catch (Exception e) {
                android.util.Log.e("FriendTracker", "Error writing session file", e);
            }
        }
    }
}
