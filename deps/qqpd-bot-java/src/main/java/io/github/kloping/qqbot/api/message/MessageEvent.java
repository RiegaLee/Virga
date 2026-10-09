package io.github.kloping.qqbot.api.message;

import io.github.kloping.qqbot.api.Sender;
import io.github.kloping.qqbot.api.event.Event;
import io.github.kloping.qqbot.entities.ex.msg.MessageChain;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;
import io.github.kloping.qqbot.entities.qqpd.v2.Contact;

import java.util.Collections;
import java.util.List;

/**
 * 消息事件接口
 *
 * @author github.kloping
 */
public interface MessageEvent<T extends Contact, S extends Contact> extends Event, Sender {
    /**
     * 获取消息
     *
     * @return
     */
    RawMessage getRawMessage();

    /**
     * 消息事件发送者
     *
     * @return
     */
    T getSender();

    /**
     * 获取提及的用户
     *
     * @return
     */
    default List<? extends T> getMentions() {
        return Collections.emptyList();
    }

    /**
     * 发送环境
     *
     * @return
     */
    S getSubject();
    /**
     * 将消息转为 MessageChain
     *
     * @return
     */
    MessageChain getMessage();

    /**
     * set filter
     *
     * @param filters
     */
    void setFilter(Class<?>[] filters);

    @Override
    default String getClassName() {
        return MessageEvent.class.getSimpleName();
    }
}
