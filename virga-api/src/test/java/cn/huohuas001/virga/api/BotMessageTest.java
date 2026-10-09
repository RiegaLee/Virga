package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BotMessageTest {
    @Test
    void messageDefensivelyCopiesCollections() {
        List<MentionSnapshot> mentions = new ArrayList<MentionSnapshot>();
        mentions.add(new MentionSnapshot("2", "open-2", "target", "member"));

        BotMessage message = new BotMessage(
            "message-1",
            "group-1",
            null,
            new SenderSnapshot("1", "open-1", "sender", "member"),
            "hello",
            "hello",
            null,
            4,
            mentions,
            Collections.<AttachmentSnapshot>emptyList()
        );

        mentions.clear();
        assertEquals(1, message.getMentions().size());
        assertThrows(UnsupportedOperationException.class, () -> message.getMentions().clear());
        assertEquals("message-1", message.toReference().getMessageId());
    }
}
