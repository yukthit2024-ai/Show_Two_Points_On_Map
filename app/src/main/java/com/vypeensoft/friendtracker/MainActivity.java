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

public class MainActivity extends AppCompatActivity {

    private MapView mapView;
    private MapLibreMap mapLibreMap;

    // Dynamic tracking maps
    private final java.util.Map<String, Marker> activeMarkers = new java.util.HashMap<>();
    private final java.util.Map<String, String> activeMarkerColors = new java.util.HashMap<>();
    private boolean isFirstCameraCenter = true;

    // Movement Loop Handler
    private final android.os.Handler movementHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable movementRunnable;

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

                // Repeat every 1 second (1000ms)
                movementHandler.postDelayed(this, 1000L);
            }
        };

        movementHandler.post(movementRunnable);
    }

    private java.util.List<UserLocation> readUserLocationsFromFiles() {
        java.util.List<UserLocation> locations = new java.util.ArrayList<>();
        
        java.io.File dir = new java.io.File("/sdcard/Vypeensoft/Friends_Location_Tracker/sessions");
        if (!dir.exists()) {
            dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Vypeensoft/Friends_Location_Tracker/sessions");
        }

        // Auto-create directory and create sample files if empty so it works out of the box
        createSampleFilesIfEmpty(dir);

        if (dir.exists() && dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isFile()) {
                        UserLocation loc = parseLocationFile(file);
                        if (loc != null) {
                            locations.add(loc);
                        }
                    }
                }
            }
        }
        return locations;
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
        if (mapLibreMap != null) {
            startMovementLoop();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        stopMovementLoop();
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

    private static class FamousCity {
        final String name;
        final double latitude;
        final double longitude;

        FamousCity(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final FamousCity[] FAMOUS_CITIES = {
        new FamousCity("New York City, USA", 40.7128, -74.0060),
        new FamousCity("Tokyo, Japan", 35.6762, 139.6503),
        new FamousCity("Paris, France", 48.8566, 2.3522),
        new FamousCity("London, UK", 51.5074, -0.1278),
        new FamousCity("Sydney, Australia", -33.8688, 151.2093),
        new FamousCity("Rio de Janeiro, Brazil", -22.9068, -43.1729),
        new FamousCity("Rome, Italy", 41.9028, 12.4964),
        new FamousCity("Cairo, Egypt", 30.0444, 31.2357),
        new FamousCity("San Francisco, USA", 37.7749, -122.4194),
        new FamousCity("Mumbai, India", 19.0760, 72.8777),
        new FamousCity("Cape Town, South Africa", -33.9249, 18.4241),
        new FamousCity("Cochin, India", 9.9312, 76.2673)
    };
}
