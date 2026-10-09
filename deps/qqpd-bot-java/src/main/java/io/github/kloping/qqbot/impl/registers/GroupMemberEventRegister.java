package io.github.kloping.qqbot.impl.registers;

import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.api.event.Event;
import io.github.kloping.qqbot.entities.Bot;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMemberAddEvent;
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMemberRemoveEvent;
import io.github.kloping.qqbot.network.Events;
import io.github.kloping.spt.annotations.AutoStand;
import io.github.kloping.spt.annotations.AutoStandAfter;
import io.github.kloping.spt.annotations.Entity;

@Entity
public class GroupMemberEventRegister implements Events.EventRegister {
    public static final String GROUP_MEMBER_ADD = "GROUP_MEMBER_ADD";
    public static final String GROUP_MEMBER_REMOVE = "GROUP_MEMBER_REMOVE";

    @AutoStandAfter
    private void register(Events events) {
        events.register(GROUP_MEMBER_ADD, this).register(GROUP_MEMBER_REMOVE, this);
    }

    @AutoStand
    Bot bot;

    @Override
    public Event handle(String type, JSONObject data, RawMessage message) {
        if (GROUP_MEMBER_ADD.equals(type)) return new BaseGroupMemberAddEvent(message, data, bot);
        if (GROUP_MEMBER_REMOVE.equals(type)) return new BaseGroupMemberRemoveEvent(message, data, bot);
        return null;
    }
}
