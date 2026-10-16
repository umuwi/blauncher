package com.example.launcher.model;

/**
 * An invitation from a friend into a ZeroTier room, as delivered by the friends API
 * ({"action":"sync"} → "invites" in server/blauncher.ru/api/friends.php).
 *
 * <p>{@link #networkId} is the 16-hex ZeroTier network ID the receiving launcher joins;
 * {@link #networkName} is what the room was called on the inviter's launcher, shown to the
 * recipient so an invitation reads as «Моя комната» rather than as a raw hex string.
 */
public class FriendInvite {
    private long id;
    private String fromCode;
    private String fromNick;
    private String networkId;
    private String networkName;
    private String createdAt;

    public FriendInvite() {
    }

    /** How the room should be named in the UI: its name, or the bare ID when it has none. */
    public String roomLabel() {
        return networkName != null && !networkName.isBlank() ? networkName : networkId;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getFromCode() {
        return fromCode;
    }

    public void setFromCode(String fromCode) {
        this.fromCode = fromCode;
    }

    public String getFromNick() {
        return fromNick;
    }

    public void setFromNick(String fromNick) {
        this.fromNick = fromNick;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getNetworkName() {
        return networkName;
    }

    public void setNetworkName(String networkName) {
        this.networkName = networkName;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
