package com.vypeensoft.friendtracker.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.vypeensoft.friendtracker.model.GroupRoom;
import com.vypeensoft.friendtracker.model.LocationMessage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MatrixClient {
    private static final String TAG = "MatrixClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    // Aligned Settings Keys matching the peer project exactly
    public static final String PREFS_NAME = "AppConfig";
    public static final String KEY_MATRIX_HOMESERVER = "matrix_homeserver";
    public static final String KEY_MATRIX_TOKEN = "matrix_token";
    public static final String KEY_MATRIX_ROOM_ID = "matrix_room_id";
    public static final String KEY_MATRIX_USERNAME = "matrix_username";
    public static final String KEY_MATRIX_PASSWORD = "matrix_password";
    public static final String KEY_MATRIX_DISPLAY_NAME = "matrix_display_name";
    public static final String KEY_MATRIX_POLLING_PERIOD = "matrix_polling_period";
    public static final String KEY_MATRIX_ROOMS = "matrix_rooms";
    
    private String homeserverUrl;
    private String accessToken;
    private String roomId;
    private String username;
    private String password;
    private String displayName;
    
    private final OkHttpClient client;
    private final Gson gson;
    private final Context context;
    private boolean isConnecting = false;

    public MatrixClient(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient();
        this.gson = new Gson();
        loadConfig(this.context);
    }

    public void loadConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.homeserverUrl = prefs.getString(KEY_MATRIX_HOMESERVER, "https://matrix-client.matrix.org");
        this.accessToken = prefs.getString(KEY_MATRIX_TOKEN, "");
        this.username = prefs.getString(KEY_MATRIX_USERNAME, "");
        this.password = prefs.getString(KEY_MATRIX_PASSWORD, "");
        this.displayName = prefs.getString(KEY_MATRIX_DISPLAY_NAME, "");
        
        // Find active room ID from list
        this.roomId = "";
        String roomsJson = prefs.getString(KEY_MATRIX_ROOMS, "");
        if (!roomsJson.isEmpty()) {
            try {
                Type type = new TypeToken<ArrayList<GroupRoom>>() {}.getType();
                List<GroupRoom> rooms = gson.fromJson(roomsJson, type);
                if (rooms != null) {
                    for (GroupRoom room : rooms) {
                        if (room.isActive()) {
                            this.roomId = room.getRoomId();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing rooms JSON: " + e.getMessage());
            }
        }
        Log.i(TAG, String.format("Config loaded: Homeserver=%s, ActiveRoom=%s, User=%s", homeserverUrl, roomId, username));
    }

    public boolean isConfigured() {
        return (accessToken != null && !accessToken.isEmpty() && roomId != null && !roomId.isEmpty()) ||
               (username != null && !username.isEmpty() && password != null && !password.isEmpty() && roomId != null && !roomId.isEmpty());
    }

    private void ensureReady(final Runnable onReady) {
        if (roomId == null || roomId.isEmpty()) {
            Log.w(TAG, "Matrix Message Send Status: SKIPPED (No active room selected)");
            return;
        }

        if (accessToken != null && !accessToken.isEmpty()) {
            onReady.run();
            return;
        }

        if (username.isEmpty() || password.isEmpty()) {
            Log.w(TAG, "Matrix Message Send Status: SKIPPED (Insufficient credentials for lazy login)");
            return;
        }

        isConnecting = true;
        performLogin(onReady);
    }

    private void performLogin(final Runnable onReady) {
        if (accessToken == null || accessToken.isEmpty()) {
            login((token) -> {
                this.accessToken = token;
                saveToPrefs(KEY_MATRIX_TOKEN, token);
                Log.i(TAG, "Matrix Login Status: SUCCESS for user: " + username);
                isConnecting = false;
                onReady.run();
            });
            return;
        }

        isConnecting = false;
        onReady.run();
    }

    private void login(final java.util.function.Consumer<String> callback) {
        Log.i(TAG, "Attempting login to " + homeserverUrl + " for user: " + username);
        String url = homeserverUrl + "/_matrix/client/r0/login";
        
        java.util.Map<String, Object> bodyMap = new java.util.HashMap<>();
        bodyMap.put("type", "m.login.password");
        bodyMap.put("user", username);
        bodyMap.put("password", password);
        
        String json = gson.toJson(bodyMap);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder().url(url).post(body).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Matrix Login Status: FAILED with exception", e);
                isConnecting = false;
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        java.util.Map<String, Object> resp = gson.fromJson(response.body().string(), java.util.Map.class);
                        callback.accept((String) resp.get("access_token"));
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing login response: " + e.getMessage());
                        isConnecting = false;
                    }
                } else {
                    Log.e(TAG, "Matrix Login Status: FAILED (Code: " + response.code() + ")");
                    isConnecting = false;
                }
                response.close();
            }
        });
    }

    private void saveToPrefs(String key, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(key, value).apply();
    }

    public void sendLocation(LocationMessage message) {
        ensureReady(() -> {
            String txnId = "m" + System.currentTimeMillis();
            String url = homeserverUrl + "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + txnId;
            java.util.Map<String, String> content = new java.util.HashMap<>();
            content.put("msgtype", "m.text");
            content.put("body", message.toPipeString());
            String json = gson.toJson(content);
            
            Log.i(TAG, "Sending Matrix message to Room: " + roomId);
            Log.i(TAG, "Message Content: " + json);

            RequestBody body = RequestBody.create(json, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .put(body)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Matrix Message Send Status: FAILED with exception for room: " + roomId, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Matrix Message Send Status: FAILED (Code: " + response.code() + ") for room: " + roomId);
                    } else {
                        Log.i(TAG, "Matrix Message Send Status: SUCCESS for room: " + roomId);
                    }
                    response.close();
                }
            });
        });
    }

    public void fetchMessages(final MatrixListener listener) {
        ensureReady(() -> {
            String url = homeserverUrl + "/_matrix/client/r0/rooms/" + roomId + "/messages?limit=10&dir=b";
            
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to fetch messages for room: " + roomId, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        String rawJson = response.body().string();
                        Log.i(TAG, "Successfully fetched messages. Response length: " + rawJson.length());
                        listener.onNewMessagesReceived(rawJson);
                    } else {
                        Log.e(TAG, "Failed to fetch messages. Code: " + response.code());
                    }
                    response.close();
                }
            });
        });
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRoomId() {
        return roomId;
    }

    public interface MatrixListener {
        void onNewMessagesReceived(String rawJson);
    }
}
