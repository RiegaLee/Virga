package cn.huohuas001.virga.api;

import java.util.Objects;

/** Minimum stable reference needed to reply to a QQ group message. */
public final class MessageReference {
    private final String messageId;
    private final String groupOpenId;
    private final int messageSequence;

    public MessageReference(String messageId, String groupOpenId, int messageSequence) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.groupOpenId = Objects.requireNonNull(groupOpenId, "groupOpenId");
        this.messageSequence = messageSequence;
    }

    public String getMessageId() { return messageId; }
    public String getGroupOpenId() { return groupOpenId; }
    public int getMessageSequence() { return messageSequence; }
}
