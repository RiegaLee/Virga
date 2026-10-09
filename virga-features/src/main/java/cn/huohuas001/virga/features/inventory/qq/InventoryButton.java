package cn.huohuas001.virga.features.inventory.qq;

import java.util.Objects;

/** One Inventory-owned QQ callback button. */
public final class InventoryButton {
    public static final int MAX_LABEL_CODE_POINTS = 18;

    private final String label;
    private final String visitedLabel;
    private final String data;
    private final String allowedUserOpenId;
    private final int style;

    public InventoryButton(
        String label,
        String visitedLabel,
        String data,
        String allowedUserOpenId,
        int style
    ) {
        this.label = requireNonBlank(label, "label");
        this.visitedLabel = requireNonBlank(visitedLabel, "visitedLabel");
        this.data = requireNonBlank(data, "data");
        this.allowedUserOpenId = requireNonBlank(allowedUserOpenId, "allowedUserOpenId");
        this.style = style;
        if (label.codePointCount(0, label.length()) > MAX_LABEL_CODE_POINTS) {
            throw new IllegalArgumentException(
                "QQ account button label must not exceed " + MAX_LABEL_CODE_POINTS + " characters"
            );
        }
    }

    public String getLabel() { return label; }
    public String getVisitedLabel() { return visitedLabel; }
    public String getData() { return data; }
    public String getAllowedUserOpenId() { return allowedUserOpenId; }
    public int getStyle() { return style; }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
