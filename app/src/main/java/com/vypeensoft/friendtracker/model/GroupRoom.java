package com.vypeensoft.friendtracker.model;

public class GroupRoom {
    private String name;
    private String roomId;
    private boolean isActive;

    public GroupRoom(String name, String roomId, boolean isActive) {
        this.name = name;
        this.roomId = roomId;
        this.isActive = isActive;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
}
