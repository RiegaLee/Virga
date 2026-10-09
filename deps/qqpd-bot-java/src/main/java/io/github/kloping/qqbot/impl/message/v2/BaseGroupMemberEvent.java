package io.github.kloping.qqbot.impl.message.v2;

import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.api.v2.GroupMemberEvent;
import io.github.kloping.qqbot.entities.Bot;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;

/**
 * Base implementation shared by ordinary QQ group member events.
 */
public abstract class BaseGroupMemberEvent extends BaseGroupEvent implements GroupMemberEvent {
    public BaseGroupMemberEvent(RawMessage message, JSONObject data, Bot bot) {
        super(message, data, bot);
    }

    @Override
    public String getMemberOpenId() {
        return getMetadata().getString("member_openid");
    }

    @Override
    public String getUserOpenId() {
        return getMetadata().getString("user_openid");
    }

    @Override
    public Long getTimestamp() {
        return getMetadata().getLong("timestamp");
    }

    @Override
    public String toString() {
        return String.format("group member %s in %s", isRemoved() ? "removed" : "added", getGroupId());
    }
}
