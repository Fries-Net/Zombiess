package net.fries.zombies.common.gameplay;

import net.fries.zombies.common.content.MysteryPools;

import java.util.UUID;

/** One paid roll can be claimed or refunded once. All deadlines use match ticks. */
public final class MysteryRoll {
    private final UUID owner;
    private final MysteryPools.Reward reward;
    private final int price;
    private final long ready, expires;
    private boolean settled;

    public MysteryRoll(
            UUID owner, MysteryPools.Reward reward, int price, long ready, long expires) {
        if (owner == null || reward == null || price < 0 || ready < 0 || expires <= ready)
            throw new IllegalArgumentException("Invalid mystery purchase");
        this.owner = owner;
        this.reward = reward;
        this.price = price;
        this.ready = ready;
        this.expires = expires;
    }

    public UUID owner() {
        return owner;
    }

    public MysteryPools.Reward reward() {
        return reward;
    }

    public boolean ready(long tick) {
        return !settled && tick >= ready && tick < expires;
    }

    public boolean expired(long tick) {
        return !settled && tick >= expires;
    }

    public boolean claim(UUID player, long tick) {
        if (!owner.equals(player) || !ready(tick)) return false;
        settled = true;
        return true;
    }

    public int refund() {
        if (settled) return 0;
        settled = true;
        return price;
    }
}
