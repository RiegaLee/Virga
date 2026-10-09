package io.github.kloping.qqbot.impl.message.v2;

import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.entities.Bot;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;

public class BaseGroupMemberAddEvent extends BaseGroupMemberEvent {
    public BaseGroupMemberAddEvent(RawMessage message, JSONObject data, Bot bot) {
        super(message, data, bot);
    }

    @Override
    public boolean isRemoved() {
        return false;
    }

    @Override
    public String getClassName() {
        return "GroupMemberAddEvent";
    }
}
