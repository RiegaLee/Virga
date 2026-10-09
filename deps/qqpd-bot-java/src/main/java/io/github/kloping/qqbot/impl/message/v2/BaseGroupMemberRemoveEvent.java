package io.github.kloping.qqbot.impl.message.v2;

import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.entities.Bot;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;

public class BaseGroupMemberRemoveEvent extends BaseGroupMemberEvent {
    public BaseGroupMemberRemoveEvent(RawMessage message, JSONObject data, Bot bot) {
        super(message, data, bot);
    }

    @Override
    public boolean isRemoved() {
        return true;
    }

    @Override
    public String getClassName() {
        return "GroupMemberRemoveEvent";
    }
}
