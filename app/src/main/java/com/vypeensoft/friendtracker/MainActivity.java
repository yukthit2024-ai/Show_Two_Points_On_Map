package com.vypeensoft.friendtracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

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

                // Setup the SymbolManager for adding markers
                symbolManager = new SymbolManager(mapView, map, style);
                symbolManager.setIconAllowOverlap(true);
                symbolManager.setTextAllowOverlap(true);
                symbolManager.setIconIgnorePlacement(true);
                symbolManager.setTextIgnorePlacement(true);

                // Add Red and Green marker images programmatically
                style.addImage("red-marker", createMarkerBitmap(Color.parseColor("#E53935"))); // Crimson Red
                style.addImage("green-marker", createMarkerBitmap(Color.parseColor("#4CAF50"))); // Vibrant Green

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

                double redLat = baseLat;
                double redLon = baseLon;
                double greenLat = baseLat + deltaLat;
                double greenLon = baseLon + deltaLon;

                LatLng redLatLng = new LatLng(redLat, redLon);
                LatLng greenLatLng = new LatLng(greenLat, greenLon);

                // Create the Red Point marker
                SymbolOptions redOptions = new SymbolOptions()
                        .withLatLng(redLatLng)
                        .withIconImage("red-marker")
                        .withIconSize(1.0f)
                        .withTextField("Red Point (~1km)")
                        .withTextColor("#E53935")
                        .withTextSize(12f)
                        .withTextOffset(new Float[]{0f, 1.5f});

                // Create the Green Point marker
                SymbolOptions greenOptions = new SymbolOptions()
                        .withLatLng(greenLatLng)
                        .withIconImage("green-marker")
                        .withIconSize(1.0f)
                        .withTextField("Green Point (~1km)")
                        .withTextColor("#4CAF50")
                        .withTextSize(12f)
                        .withTextOffset(new Float[]{0f, 1.5f});

                symbolManager.create(redOptions);
                symbolManager.create(greenOptions);

                // Adjust camera view bounds to perfectly fit both markers on screen with 150px padding
                LatLngBounds bounds = new LatLngBounds.Builder()
                        .include(redLatLng)
                        .include(greenLatLng)
                        .build();

                mapView.postDelayed(() -> {
                    if (!isDestroyed()) {
                        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150));
                    }
                }, 500); // Slight delay to ensure layout is complete
            });
        });
    }

    /**
     * Programmatically generates a clean, premium, high-resolution circular pin/marker bitmap
     * with a smooth white border and a soft drop shadow.
     */
    private Bitmap createMarkerBitmap(int color) {
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setAntiAlias(true);

        // Draw soft drop shadow
        paint.setColor(Color.parseColor("#33000000"));
        canvas.drawCircle(size / 2f, size / 2f + 4, size / 2.4f, paint);

        // Draw outer white circle
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size / 2.4f, paint);

        // Draw inner colored circle
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 3.4f, paint);

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
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
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
