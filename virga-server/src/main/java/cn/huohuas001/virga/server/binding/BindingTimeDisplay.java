package cn.huohuas001.virga.server.binding;

import java.time.Duration;
import java.time.Instant;

/** 仅供绑定提示使用：不足一秒向上取整，不改变验证码过期和冷却判断。 */
final class BindingTimeDisplay {
    private BindingTimeDisplay() {}

    static long remainingSeconds(Instant now, Instant expiresAt) {
        Duration remaining = Duration.between(now, expiresAt);
        // 沿用提示至少显示一秒的规则；是否已过期仍由绑定服务判定。
        if (remaining.isNegative() || remaining.isZero()) return 1L;
        return remaining.getSeconds() + (remaining.getNano() == 0 ? 0L : 1L);
    }
}
