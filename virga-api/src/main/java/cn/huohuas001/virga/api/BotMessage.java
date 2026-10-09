package cn.huohuas001.virga.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Cross-platform immutable QQ group message snapshot exposed to addons. */
public final class BotMessage {
    private final String messageId;
    private final String groupOpenId;
    private final String groupId;
    private final SenderSnapshot sender;
    private final String content;
    private final String rawContent;
    private final String timestamp;
    private final int messageSequence;
    private final List<MentionSnapshot> mentions;
    private final List<AttachmentSnapshot> attachments;

    public BotMessage(
        String messageId,
        String groupOpenId,
        String groupId,
        SenderSnapshot sender,
        String content,
        String rawContent,
        String timestamp,
        int messageSequence,
        List<MentionSnapshot> mentions,
        List<AttachmentSnapshot> attachments
    ) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.groupOpenId = Objects.requireNonNull(groupOpenId, "groupOpenId");
        this.groupId = groupId;
        this.sender = Objects.requireNonNull(sender, "sender");
        this.content = Objects.requireNonNull(content, "content");
        this.rawContent = Objects.requireNonNull(rawContent, "rawContent");
        this.timestamp = timestamp;
        this.messageSequence = messageSequence;
        this.mentions = immutableCopy(mentions, "mentions");
        this.attachments = immutableCopy(attachments, "attachments");
    }

    private static <T> List<T> immutableCopy(List<T> values, String field) {
        Objects.requireNonNull(values, field);
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public String getMessageId() { return messageId; }
    public String getGroupOpenId() { return groupOpenId; }
    public String getGroupId() { return groupId; }
    public SenderSnapshot getSender() { return sender; }
    public String getContent() { return content; }
    public String getRawContent() { return rawContent; }
    public String getTimestamp() { return timestamp; }
    public int getMessageSequence() { return messageSequence; }
    public List<MentionSnapshot> getMentions() { return mentions; }
    public List<AttachmentSnapshot> getAttachments() { return attachments; }

    public MessageReference toReference() {
        return new MessageReference(messageId, groupOpenId, messageSequence);
    }
}
