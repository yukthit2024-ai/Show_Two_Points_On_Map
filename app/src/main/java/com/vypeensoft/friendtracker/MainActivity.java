package com.vypeensoft.friendtracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.plugins.annotation.SymbolManager;
import org.maplibre.android.plugins.annotation.SymbolOptions;

public class MainActivity extends AppCompatActivity {

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private SymbolManager symbolManager;

    // Coordinate state & Symbol references
    private LatLng redLatLng;
    private LatLng greenLatLng;
    private org.maplibre.android.plugins.annotation.Symbol redSymbol;
    private org.maplibre.android.plugins.annotation.Symbol greenSymbol;

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

                // 1. Add Red and Green tinted marker images using the existing project marker asset
                style.addImage("red-marker", getTintedMarkerBitmap(R.drawable.ic_friend_marker, Color.parseColor("#E53935"))); // Crimson Red Tint
                style.addImage("green-marker", getTintedMarkerBitmap(R.drawable.ic_friend_marker, Color.parseColor("#4CAF50"))); // Vibrant Green Tint

                // 2. Setup the SymbolManager for adding markers
                symbolManager = new SymbolManager(mapView, map, style);
                symbolManager.setIconAllowOverlap(true);
                symbolManager.setTextAllowOverlap(true);
                symbolManager.setIconIgnorePlacement(true);
                symbolManager.setTextIgnorePlacement(true);

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

                // Create the Red Point marker
                SymbolOptions redOptions = new SymbolOptions()
                        .withLatLng(redLatLng)
                        .withIconImage("red-marker")
                        .withIconSize(1.0f)
                        .withTextField("Red Point (~1km)")
                        .withTextColor("red")
                        .withTextSize(12f)
                        .withTextOffset(new Float[]{0f, 1.8f});

                // Create the Green Point marker
                SymbolOptions greenOptions = new SymbolOptions()
                        .withLatLng(greenLatLng)
                        .withIconImage("green-marker")
                        .withIconSize(1.0f)
                        .withTextField("Green Point (~1km)")
                        .withTextColor("green")
                        .withTextSize(12f)
                        .withTextOffset(new Float[]{0f, 1.8f});

                redSymbol = symbolManager.create(redOptions);
                greenSymbol = symbolManager.create(greenOptions);

                // Calculate the midpoint between the points
                LatLng midpoint = new LatLng(
                        (redLatLng.getLatitude() + greenLatLng.getLatitude()) / 2.0,
                        (redLatLng.getLongitude() + greenLatLng.getLongitude()) / 2.0
                );

                // Safe Initial Camera Centering: moveCamera(newLatLngZoom) is synchronous, layout size-independent,
                // and 100% immune to IllegalArgumentExceptions from zero-measured layout passes.
                mapLibreMap.moveCamera(CameraUpdateFactory.newLatLngZoom(midpoint, 14.5));

                // Start the loop immediately
                startMovementLoop();
            });
        });
    }

    /**
     * Spawns a repeating 1-second loop that moves both coordinates by exactly 1 meter
     * in a random direction, maintaining their 1km relative separation.
     */
    private void startMovementLoop() {
        if (movementRunnable != null) return; // Already running

        movementRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || redSymbol == null || greenSymbol == null) return;

                // Move by 1 meter in a random direction
                double angle = Math.random() * 2 * Math.PI;

                // 1 meter = 0.001 kilometer
                double distanceKm = 0.001; 
                double avgLat = (redLatLng.getLatitude() + greenLatLng.getLatitude()) / 2.0;
                double deltaLat = (distanceKm / 111.12) * Math.cos(angle);
                double deltaLon = ((distanceKm / 111.12) * Math.sin(angle)) / Math.cos(Math.toRadians(avgLat));

                // Translate both coordinates by the exact same delta vector
                redLatLng = new LatLng(redLatLng.getLatitude() + deltaLat, redLatLng.getLongitude() + deltaLon);
                greenLatLng = new LatLng(greenLatLng.getLatitude() + deltaLat, greenLatLng.getLongitude() + deltaLon);

                // Update marker positions on the Map
                redSymbol.setLatLng(redLatLng);
                greenSymbol.setLatLng(greenLatLng);
                symbolManager.update(redSymbol);
                symbolManager.update(greenSymbol);

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
     * Programmatically tints an existing project drawable asset to a custom color
     * and converts it to a high-resolution Bitmap.
     */
    private Bitmap getTintedMarkerBitmap(int drawableId, int tintColor) {
        Drawable drawable = ContextCompat.getDrawable(this, drawableId);
        if (drawable == null) {
            return createMarkerBitmap(tintColor); // Fallback to programmatic circle if resource missing
        }

        // Wrap and mutate the drawable to safely apply the tint color
        drawable = DrawableCompat.wrap(drawable).mutate();
        DrawableCompat.setTint(drawable, tintColor);

        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 128;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 128;

        // Bound dimensions to optimized standard size (128x128 max)
        int maxSize = 128;
        if (width > maxSize || height > maxSize) {
            float ratio = Math.min((float) maxSize / width, (float) maxSize / height);
            width = Math.round(width * ratio);
            height = Math.round(height * ratio);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    /**
     * Programmatically generates a circular pin/marker bitmap as fallback.
     */
    private Bitmap createMarkerBitmap(int color) {
        int size = 128;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setAntiAlias(true);

        // 1. Shadow (Soft Black)
        paint.setColor(Color.parseColor("#40000000"));
        canvas.drawCircle(size / 2f, size / 2f + 8, size / 2.4f, paint);

        // 2. Outer border (Pure White)
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size / 2.4f, paint);

        // 3. Colored Core
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 3.4f, paint);

        // 4. Center Dot (Pure White for premium glowing appearance)
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size / 8f, paint);

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
        if (redSymbol != null && greenSymbol != null) {
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
