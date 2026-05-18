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

    // Coordinate state & standard Marker references
    private LatLng redLatLng;
    private LatLng greenLatLng;
    private LatLng blueLatLng;
    
    private Marker redMarker;
    private Marker greenMarker;
    private Marker blueMarker;

    // Movement Loop Handler
    private final android.os.Handler movementHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable movementRunnable;

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

                // Define Base Point: Cochin, Kerala, India (beautiful area with clear map layers)
                double baseLat = 9.93123;
                double baseLon = 76.26730;

                // Random angle/bearing in radians
                double angle = Math.random() * 2 * Math.PI;

                // 1.0 Kilometer offset calculation:
                // 1 degree latitude is exactly ~111.12 km.
                // 1 km latitude = 1.0 / 111.12 degrees.
                // 1 km longitude = (1.0 / 111.12) / cos(latitude) degrees.
                double distanceKm = 1.0;
                double deltaLat = (distanceKm / 111.12) * Math.cos(angle);
                double deltaLon = ((distanceKm / 111.12) * Math.sin(angle)) / Math.cos(Math.toRadians(baseLat));

                redLatLng = new LatLng(baseLat, baseLon);
                greenLatLng = new LatLng(baseLat + deltaLat, baseLon + deltaLon);

                // Math for Blue Point: Place it perpendicular to the Red-Green line from their midpoint
                // by 600 meters. The distance to both Red and Green will be sqrt(500^2 + 600^2) = ~781 meters
                // (which is perfectly between 500 meters and 1000 meters!).
                double midLat = baseLat + deltaLat / 2.0;
                double midLon = baseLon + deltaLon / 2.0;

                double perpDistanceKm = 0.6; // 600 meters
                double perpAngle = angle + Math.PI / 2.0; // Perpendicular bearing (rotated by 90 degrees)
                double perpLat = (perpDistanceKm / 111.12) * Math.cos(perpAngle);
                double perpLon = ((perpDistanceKm / 111.12) * Math.sin(perpAngle)) / Math.cos(Math.toRadians(midLat));

                blueLatLng = new LatLng(midLat + perpLat, midLon + perpLon);

                // Create custom icons from our programmatically generated teardrop pin vector bitmaps
                IconFactory iconFactory = IconFactory.getInstance(MainActivity.this);
                Icon redIcon = iconFactory.fromBitmap(createTeardropMarkerBitmap(Color.parseColor("#E53935"))); // Crimson Red Tint
                Icon greenIcon = iconFactory.fromBitmap(createTeardropMarkerBitmap(Color.parseColor("#4CAF50"))); // Vibrant Green Tint
                Icon blueIcon = iconFactory.fromBitmap(createTeardropMarkerBitmap(Color.parseColor("#2196F3"))); // Blue Tint

                // Add standard built-in Markers to the Map
                redMarker = mapLibreMap.addMarker(new MarkerOptions()
                        .position(redLatLng)
                        .title("Red Point (~1km)")
                        .icon(redIcon));

                greenMarker = mapLibreMap.addMarker(new MarkerOptions()
                        .position(greenLatLng)
                        .title("Green Point (~1km)")
                        .icon(greenIcon));

                blueMarker = mapLibreMap.addMarker(new MarkerOptions()
                        .position(blueLatLng)
                        .title("Blue Point (~781m)")
                        .icon(blueIcon));

                // Calculate the midpoint between the points for the camera positioning
                LatLng cameraMidpoint = new LatLng(
                        (redLatLng.getLatitude() + greenLatLng.getLatitude() + blueLatLng.getLatitude()) / 3.0,
                        (redLatLng.getLongitude() + greenLatLng.getLongitude() + blueLatLng.getLongitude()) / 3.0
                );

                // Safe Initial Camera Centering: moveCamera(newLatLngZoom) is synchronous, layout size-independent
                mapLibreMap.moveCamera(CameraUpdateFactory.newLatLngZoom(cameraMidpoint, 14.5));

                // Start the loop immediately
                startMovementLoop();
            });
        });
    }

    /**
     * Spawns a repeating 1-second loop that moves all three coordinates by exactly 1 meter
     * in a random direction, maintaining their relative separations.
     */
    private void startMovementLoop() {
        if (movementRunnable != null) return; // Already running

        movementRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || redMarker == null || greenMarker == null || blueMarker == null) return;

                // Move by 1 meter in a random direction
                double angle = Math.random() * 2 * Math.PI;

                // 1 meter = 0.001 kilometer
                double distanceKm = 0.001; 
                double avgLat = (redLatLng.getLatitude() + greenLatLng.getLatitude()) / 2.0;
                double deltaLat = (distanceKm / 111.12) * Math.cos(angle);
                double deltaLon = ((distanceKm / 111.12) * Math.sin(angle)) / Math.cos(Math.toRadians(avgLat));

                // Translate all three coordinates by the exact same delta vector to preserve their relative distances
                redLatLng = new LatLng(redLatLng.getLatitude() + deltaLat, redLatLng.getLongitude() + deltaLon);
                greenLatLng = new LatLng(greenLatLng.getLatitude() + deltaLat, greenLatLng.getLongitude() + deltaLon);
                blueLatLng = new LatLng(blueLatLng.getLatitude() + deltaLat, blueLatLng.getLongitude() + deltaLon);

                // Update marker positions instantly on the Map
                redMarker.setPosition(redLatLng);
                greenMarker.setPosition(greenLatLng);
                blueMarker.setPosition(blueLatLng);

                // Repeat every 1 second
                movementHandler.postDelayed(this, 1000L);
            }
        };

        movementHandler.post(movementRunnable);
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
     * Programmatically draws a beautiful classic teardrop vector map pin with:
     * 1. A soft drop shadow under the pointer tip.
     * 2. A crisp white outer pointer border.
     * 3. A solid colored inner pointer core.
     * 4. A glowing white central dot inside the head of the pin.
     */
    private Bitmap createTeardropMarkerBitmap(int color) {
        int size = 128;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);

        // 1. Draw soft drop shadow under the tip
        paint.setColor(Color.parseColor("#33000000"));
        canvas.drawCircle(64, 110, 14, paint);

        // 2. Draw white outer pin border
        paint.setColor(Color.WHITE);
        Path outerPath = new Path();
        outerPath.moveTo(64, 110); // Tip at bottom
        outerPath.lineTo(30, 50); // Left tangent
        outerPath.arcTo(new RectF(30, 12, 98, 80), 150, 240, false); // Top circle
        outerPath.close();
        canvas.drawPath(outerPath, paint);

        // 3. Draw colored inner pin core
        paint.setColor(color);
        Path innerPath = new Path();
        innerPath.moveTo(64, 102); // Tip at bottom
        innerPath.lineTo(36, 52); // Left tangent
        innerPath.arcTo(new RectF(36, 18, 92, 74), 150, 240, false); // Top circle
        innerPath.close();
        canvas.drawPath(innerPath, paint);

        // 4. Draw central white glowing dot inside the head of the pin
        paint.setColor(Color.WHITE);
        canvas.drawCircle(64, 46, 12, paint);

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
        if (redMarker != null && greenMarker != null && blueMarker != null) {
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
}
