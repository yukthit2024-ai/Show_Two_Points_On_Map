package com.vypeensoft.friendtracker.model;

import com.google.gson.annotations.SerializedName;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class LocationMessage {
    @SerializedName("type")
    private String type = "location";

    @SerializedName("msgtype")
    private String msgtype = "m.text";

    @SerializedName("body")
    private String body;
    
    @SerializedName("userId")
    private String userId;
    
    @SerializedName("latitude")
    private double latitude;
    
    @SerializedName("longitude")
    private double longitude;
    
    @SerializedName("timestamp")
    private String timestamp;

    public LocationMessage(String userId, double latitude, double longitude) {
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = getCurrentTimestampISO8601();
        this.body = String.format(Locale.US, "Location Update: %.6f, %.6f", latitude, longitude);
    }

    public String getType() { return type; }
    public String getUserId() { return userId; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getTimestamp() { return timestamp; }

    private String getCurrentTimestampISO8601() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    public static String formatTimestamp(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    public String toPipeString() {
        return String.format(Locale.US, "%s|%.6f|%.6f|%s", userId, latitude, longitude, timestamp);
    }
}
