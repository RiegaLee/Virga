package io.github.kloping.qqbot.api.v2;

/**
 * 普通 QQ 群成员加入或退出事件。
 */
public interface GroupMemberEvent extends GroupEvent {
    String getMemberOpenId();

    String getUserOpenId();

    Long getTimestamp();

    boolean isRemoved();
}
