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

    // Persistent heading directions for each friend in radians (allows actual traveling across the map)
    private double headingRed = Math.random() * 2 * Math.PI;
    private double headingGreen = Math.random() * 2 * Math.PI;
    private double headingBlue = Math.random() * 2 * Math.PI;

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

                // Choose a random famous city in the world to start the simulation
                FamousCity selectedCity = FAMOUS_CITIES[(int) (Math.random() * FAMOUS_CITIES.length)];
                double baseLat = selectedCity.latitude;
                double baseLon = selectedCity.longitude;

                // Show a nice Toast feedback to inform the user which city loaded
                android.widget.Toast.makeText(MainActivity.this, 
                        "Simulation started in: " + selectedCity.name, 
                        android.widget.Toast.LENGTH_LONG).show();

                // Random angle/bearing in radians
                double angle = Math.random() * 2 * Math.PI;

                // Equilateral Triangle setup for 3 friends with exactly 1050 meters (1.050 km) side length:
                // This targets the exact center of the requested [1000, 1100] meters constraint range!
                double sKm = 1.050; // 1050 meters
                double deltaLat = (sKm / 111.12) * Math.cos(angle);
                double deltaLon = ((sKm / 111.12) * Math.sin(angle)) / Math.cos(Math.toRadians(baseLat));

                redLatLng = new LatLng(baseLat, baseLon);
                greenLatLng = new LatLng(baseLat + deltaLat, baseLon + deltaLon);

                // Math for Blue Point: Place it perpendicular to the Red-Green line from their midpoint
                // at a distance of s * sqrt(3) / 2 (height of equilateral triangle) = ~909.33 meters
                double midLat = baseLat + deltaLat / 2.0;
                double midLon = baseLon + deltaLon / 2.0;

                double perpDistanceKm = sKm * 0.866025; // s * sqrt(3)/2
                double perpAngle = angle + Math.PI / 2.0; // Perpendicular bearing (rotated by 90 degrees)
                double perpLat = (perpDistanceKm / 111.12) * Math.cos(perpAngle);
                double perpLon = ((perpDistanceKm / 111.12) * Math.sin(perpAngle)) / Math.cos(Math.toRadians(midLat));

                blueLatLng = new LatLng(midLat + perpLat, midLon + perpLon);

                // Create custom icons from our programmatically generated teardrop pin vector bitmaps with names
                IconFactory iconFactory = IconFactory.getInstance(MainActivity.this);
                Icon redIcon = iconFactory.fromBitmap(createTeardropMarkerBitmap(Color.parseColor("#E53935"), "Red")); // Crimson Red Tint
                Icon greenIcon = iconFactory.fromBitmap(createTeardropMarkerBitmap(Color.parseColor("#4CAF50"), "Green")); // Vibrant Green Tint
                Icon blueIcon = iconFactory.fromBitmap(createTeardropMarkerBitmap(Color.parseColor("#2196F3"), "Blue")); // Blue Tint

                // Add standard built-in Markers to the Map
                redMarker = mapLibreMap.addMarker(new MarkerOptions()
                        .position(redLatLng)
                        .title("Red Friend")
                        .icon(redIcon));

                greenMarker = mapLibreMap.addMarker(new MarkerOptions()
                        .position(greenLatLng)
                        .title("Green Friend")
                        .icon(greenIcon));

                blueMarker = mapLibreMap.addMarker(new MarkerOptions()
                        .position(blueLatLng)
                        .title("Blue Friend")
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
     * Spawns a repeating 1-second loop where each marker moves in a persistent, correlated
     * random direction by 1 to 5 meters. Dynamic group steering keeps the friends together
     * while PBD inequality range constraints guarantee mutual distances stay strictly in [1000, 1100] meters.
     */
    private void startMovementLoop() {
        if (movementRunnable != null) return; // Already running

        movementRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || redMarker == null || greenMarker == null || blueMarker == null) return;

                // 1. Calculate current group center point
                double centerLat = (redLatLng.getLatitude() + greenLatLng.getLatitude() + blueLatLng.getLatitude()) / 3.0;
                double centerLon = (redLatLng.getLongitude() + greenLatLng.getLongitude() + blueLatLng.getLongitude()) / 3.0;
                LatLng centerPoint = new LatLng(centerLat, centerLon);

                // 2. Red Heading Steering: slightly perturb and steer towards/away from center
                double distR = calculateDistance(redLatLng, centerPoint);
                double bearingR = calculateBearing(redLatLng, centerPoint);
                headingRed += (Math.random() - 0.5) * Math.toRadians(30); // Perturb by +/- 15 degrees
                if (distR > 620.0) {
                    headingRed = blendAngles(headingRed, bearingR, 0.15); // Steer back towards center if too far
                } else if (distR < 580.0) {
                    headingRed = blendAngles(headingRed, bearingR + Math.PI, 0.15); // Steer away if too close
                }

                // 3. Green Heading Steering: slightly perturb and steer towards/away from center
                double distG = calculateDistance(greenLatLng, centerPoint);
                double bearingG = calculateBearing(greenLatLng, centerPoint);
                headingGreen += (Math.random() - 0.5) * Math.toRadians(30); // Perturb by +/- 15 degrees
                if (distG > 620.0) {
                    headingGreen = blendAngles(headingGreen, bearingG, 0.15); // Steer back towards center
                } else if (distG < 580.0) {
                    headingGreen = blendAngles(headingGreen, bearingG + Math.PI, 0.15); // Steer away
                }

                // 4. Blue Heading Steering: slightly perturb and steer towards/away from center
                double distB = calculateDistance(blueLatLng, centerPoint);
                double bearingB = calculateBearing(blueLatLng, centerPoint);
                headingBlue += (Math.random() - 0.5) * Math.toRadians(30); // Perturb by +/- 15 degrees
                if (distB > 620.0) {
                    headingBlue = blendAngles(headingBlue, bearingB, 0.15); // Steer back towards center
                } else if (distB < 580.0) {
                    headingBlue = blendAngles(headingBlue, bearingB + Math.PI, 0.15); // Steer away
                }

                // 5. Move all three friends by a random step of 1 to 5 meters along their persistent headings
                double stepR = 1.0 + Math.random() * 4.0; // 1 to 5 meters
                double deltaLatR = (stepR / 1000.0 / 111.12) * Math.cos(headingRed);
                double deltaLonR = ((stepR / 1000.0 / 111.12) * Math.sin(headingRed)) / Math.cos(Math.toRadians(redLatLng.getLatitude()));
                redLatLng = new LatLng(redLatLng.getLatitude() + deltaLatR, redLatLng.getLongitude() + deltaLonR);

                double stepG = 1.0 + Math.random() * 4.0; // 1 to 5 meters
                double deltaLatG = (stepG / 1000.0 / 111.12) * Math.cos(headingGreen);
                double deltaLonG = ((stepG / 1000.0 / 111.12) * Math.sin(headingGreen)) / Math.cos(Math.toRadians(greenLatLng.getLatitude()));
                greenLatLng = new LatLng(greenLatLng.getLatitude() + deltaLatG, greenLatLng.getLongitude() + deltaLonG);

                double stepB = 1.0 + Math.random() * 4.0; // 1 to 5 meters
                double deltaLatB = (stepB / 1000.0 / 111.12) * Math.cos(headingBlue);
                double deltaLonB = ((stepB / 1000.0 / 111.12) * Math.sin(headingBlue)) / Math.cos(Math.toRadians(blueLatLng.getLatitude()));
                blueLatLng = new LatLng(blueLatLng.getLatitude() + deltaLatB, blueLatLng.getLongitude() + deltaLonB);

                // 6. Apply PBD Inequality Range Constraints (1000 meters to 1100 meters)
                // If distances are inside [1000m, 1100m], the solver does absolutely nothing, letting them wander freely.
                for (int i = 0; i < 3; i++) {
                    LatLng[] rg = adjustPairRange(redLatLng, greenLatLng, 1000.0, 1100.0);
                    redLatLng = rg[0];
                    greenLatLng = rg[1];

                    LatLng[] gb = adjustPairRange(greenLatLng, blueLatLng, 1000.0, 1100.0);
                    greenLatLng = gb[0];
                    blueLatLng = gb[1];

                    LatLng[] br = adjustPairRange(blueLatLng, redLatLng, 1000.0, 1100.0);
                    blueLatLng = br[0];
                    redLatLng = br[1];
                }

                // Update marker positions instantly on the Map
                redMarker.setPosition(redLatLng);
                greenMarker.setPosition(greenLatLng);
                blueMarker.setPosition(blueLatLng);

                // Force layout redraw to render instantly
                mapView.invalidate();

                // Calculate current real-world distances for diagnostic verification
                double dRG = calculateDistance(redLatLng, greenLatLng);
                double dGB = calculateDistance(greenLatLng, blueLatLng);
                double dBR = calculateDistance(blueLatLng, redLatLng);

                android.util.Log.d("FriendTracker", String.format(
                        "Tick distances: Red-Green=%.2fm, Green-Blue=%.2fm, Blue-Red=%.2fm", dRG, dGB, dBR
                ));

                // Repeat every 1 second
                movementHandler.postDelayed(this, 1000L);
            }
        };

        movementHandler.post(movementRunnable);
    }

    /**
     * Blends two angles smoothly in the shortest angular direction, handling boundary wrapping around -PI and +PI.
     */
    private double blendAngles(double current, double target, double ratio) {
        double diff = target - current;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        return current + ratio * diff;
    }

    /**
     * High-Accuracy bearing calculation in radians from p1 to p2.
     */
    private double calculateBearing(LatLng p1, LatLng p2) {
        double lat1 = Math.toRadians(p1.getLatitude());
        double lon1 = Math.toRadians(p1.getLongitude());
        double lat2 = Math.toRadians(p2.getLatitude());
        double lon2 = Math.toRadians(p2.getLongitude());

        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) -
                   Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        return Math.atan2(y, x);
    }

    /**
     * High-Accuracy Haversine formula to compute geodesic distance between two points in meters.
     */
    private double calculateDistance(LatLng p1, LatLng p2) {
        double R = 6371000; // Earth's radius in meters
        double lat1 = Math.toRadians(p1.getLatitude());
        double lon1 = Math.toRadians(p1.getLongitude());
        double lat2 = Math.toRadians(p2.getLatitude());
        double lon2 = Math.toRadians(p2.getLongitude());

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(lat1) * Math.cos(lat2) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    /**
     * PBD Inequality Range constraint relaxation. Adjusts coordinates only if they breach range boundaries.
     */
    private LatLng[] adjustPairRange(LatLng p1, LatLng p2, double minDistance, double maxDistance) {
        double currentDistance = calculateDistance(p1, p2);
        if (currentDistance == 0) return new LatLng[]{p1, p2};

        double diff = 0;
        if (currentDistance < minDistance) {
            diff = currentDistance - minDistance; // Negative diff will push them apart
        } else if (currentDistance > maxDistance) {
            diff = currentDistance - maxDistance; // Positive diff will pull them together
        } else {
            return new LatLng[]{p1, p2}; // Safe range, no action needed!
        }
        
        // We move each coordinate by half the error ratio
        double factor = (diff / 2.0) / currentDistance;

        double dLat = p2.getLatitude() - p1.getLatitude();
        double dLon = p2.getLongitude() - p1.getLongitude();

        LatLng newP1 = new LatLng(p1.getLatitude() + dLat * factor, p1.getLongitude() + dLon * factor);
        LatLng newP2 = new LatLng(p2.getLatitude() - dLat * factor, p2.getLongitude() - dLon * factor);

        return new LatLng[]{newP1, newP2};
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
