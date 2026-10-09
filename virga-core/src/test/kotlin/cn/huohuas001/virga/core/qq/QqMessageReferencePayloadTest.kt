package cn.huohuas001.virga.core.qq

import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.http.data.V2MsgData
import cn.huohuas001.virga.api.MessageReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QqMessageReferencePayloadTest {
    @Test fun `native markdown reply keeps its passive sequence without mentions or keyboard`() {
        val payload = V2MsgData().setMsg_type(2).setContent("# Virga · 示例")
            .setMarkdown(Markdown().setContent("# Virga · 示例\n\n1. **示例条目**"))
            .setMsg_id("markdown-source").setMsg_seq(2)
        val body = JSON.parseObject(serializeGroupPayload(payload))
        assertEquals(2, body.getInteger("msg_type"))
        assertEquals(2, body.getInteger("msg_seq"))
        assertEquals("markdown-source", body.getString("msg_id"))
        assertEquals("markdown-source", body.getJSONObject("message_reference").getString("message_id"))
        assertEquals("# Virga · 示例\n\n1. **示例条目**", body.getJSONObject("markdown").getString("content"))
        assertFalse(body.containsKey("keyboard"))
        assertFalse(body.toJSONString().contains("qqbot-at-user"))
    }
    @Test
    fun `passive text reply includes both quota reference and visible quote`() {
        val payload = V2MsgData()
            .setContent("回复内容")
            .setMsg_id("source-message-id")
            .setMsg_seq(7)

        val body = JSON.parseObject(serializeGroupPayload(payload, "source-message-id"))

        assertEquals("source-message-id", body.getString("msg_id"))
        assertEquals(7, body.getInteger("msg_seq"))
        assertEquals(
            "source-message-id",
            body.getJSONObject("message_reference").getString("message_id")
        )
    }

    @Test
    fun `active text and non-text payloads do not gain an implicit quote`() {
        val body = JSON.parseObject(serializeGroupPayload(V2MsgData().setContent("主动通知")))

        assertFalse(body.containsKey("message_reference"))
    }

    @Test
    fun `passive media image reply is unquoted without losing media caption or sequence`() {
        val reference = MessageReference("image-source-message", "test-group", 3)
        val payload = V2MsgData().setMsg_type(7).setMedia(V2MsgData.Media("uploaded-file-info"))
            .setContent("这是离线快照哦。")
            .setMsg_id(reference.messageId).setMsg_seq(reference.messageSequence)
        val body = JSON.parseObject(serializeGroupPayload(payload))
        assertEquals(7, body.getInteger("msg_type"))
        assertEquals("uploaded-file-info", body.getJSONObject("media").getString("file_info"))
        assertEquals("image-source-message", body.getString("msg_id"))
        assertEquals(3, body.getInteger("msg_seq"))
        assertFalse(body.containsKey("message_reference"))
        assertEquals("这是离线快照哦。", body.getString("content"))
    }

    @Test
    fun `active images and unquoted help remain unquoted and do not invent a mention`() {
        val active = JSON.parseObject(serializeGroupPayload(V2MsgData().setMsg_type(7)
            .setMedia(V2MsgData.Media("uploaded-file-info"))))
        assertFalse(active.containsKey("message_reference"))
        assertFalse(active.containsKey("msg_id"))
        assertFalse(active.containsKey("content"))
        val optOut = JSON.parseObject(serializeGroupPayload(V2MsgData().setContent("帮助")
            .setMsg_id("quota-only-id"), quotedMessageId = null))
        assertFalse(optOut.containsKey("message_reference"))
        assertEquals("quota-only-id", optOut.getString("msg_id"))
    }

    @Test
    fun `pure passive image never gains quote or mention even through shared explicit quote pipeline`() {
        val payload = V2MsgData().setMsg_type(7).setMedia(V2MsgData.Media("uploaded-file-info"))
            .setContent("").setMsg_id("image-quota-id").setMsg_seq(4)
        val body = JSON.parseObject(serializeGroupPayload(payload, "image-quota-id"))
        assertFalse(body.containsKey("message_reference"))
        assertFalse(body.containsKey("content"))
        assertFalse(body.containsKey("markdown"))
        assertFalse(body.containsKey("keyboard"))
        assertEquals("image-quota-id", body.getString("msg_id"))
        assertEquals(4, body.getInteger("msg_seq"))
        assertEquals("uploaded-file-info", body.getJSONObject("media").getString("file_info"))
    }

    @Test
    fun `markdown reply can carry the same visible quote reference`() {
        val payload = V2MsgData()
            .setMsg_type(2)
            .setMarkdown(Markdown().setContent("<qqbot-at-user id=\"requester-open-id\" />，查询结果"))

        val body = JSON.parseObject(serializeGroupPayload(payload, "source-message-id"))

        assertEquals(
            "source-message-id",
            body.getJSONObject("message_reference").getString("message_id")
        )
        assertEquals(2, body.getInteger("msg_type"))
    }
}
