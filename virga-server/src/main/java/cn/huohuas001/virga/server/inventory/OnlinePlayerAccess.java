package cn.huohuas001.virga.server.inventory;

import cn.huohuas001.virga.server.game.GamePlayer;

/**
 * Thread-affinity boundary for reading live player data: name lookups and inventory reads
 * both belong to the server thread.
 */
public interface OnlinePlayerAccess {
    /** True on the server thread. */
    boolean isGlobalThread();

    /** Runs {@code task} on the server thread; false when scheduling is closed and it never runs. */
    boolean executeGlobal(Runnable task);

    /** True when the current thread may read {@code player}'s inventory. */
    boolean ownsPlayer(GamePlayer player);

    /**
     * Runs {@code task} for {@code player} on the server thread; {@code retired} runs instead when
     * the player leaves first.
     *
     * @return false when the player is already gone or scheduling is closed; neither callback runs
     */
    boolean executeForPlayer(GamePlayer player, Runnable task, Runnable retired);

    GamePlayer findExactPlayer(String playerName);
}
