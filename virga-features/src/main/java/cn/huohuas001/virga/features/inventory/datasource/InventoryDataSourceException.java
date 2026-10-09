package cn.huohuas001.virga.features.inventory.datasource;

/** Expected online-source failures that the command layer can map to safe user messages. */
public final class InventoryDataSourceException extends RuntimeException {
    public enum Reason {
        PLAYER_OFFLINE,
        PLAYER_STATE_CHANGED,
        SOURCE_UNAVAILABLE
    }

    private final Reason reason;

    public InventoryDataSourceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public InventoryDataSourceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
