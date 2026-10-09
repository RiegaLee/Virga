package cn.huohuas001.virga.features.inventory.qq;

/** Official QQ interaction acknowledgement codes used by Inventory. */
public enum InventoryButtonResult {
    SUCCESS(0),
    EXPIRED_INVENTORY(1, "账号选择已超时，请重新发送 /我的背包。"),
    EXPIRED_ENDER_CHEST(1, "账号选择已超时，请重新发送 /我的末影箱。"),
    FAILED(1),
    TOO_FREQUENT(2),
    DUPLICATE(3),
    FORBIDDEN(4),
    NOT_HANDLED(-1);

    private final int platformCode;
    private final String feedbackMessage;

    InventoryButtonResult(int platformCode) { this(platformCode, null); }

    InventoryButtonResult(int platformCode, String feedbackMessage) {
        this.platformCode = platformCode;
        this.feedbackMessage = feedbackMessage;
    }

    public int getPlatformCode() { return platformCode; }
    public String getFeedbackMessage() { return feedbackMessage; }
}
