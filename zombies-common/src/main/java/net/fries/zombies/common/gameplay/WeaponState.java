package net.fries.zombies.common.gameplay;

import net.fries.zombies.common.content.Gameplay.Weapon;

import java.util.UUID;

/** Authoritative ammunition belongs to an owned instance, never to client-editable item lore. */
public final class WeaponState {
    public enum Shot {
        FIRED,
        COOLDOWN,
        RELOADING,
        EMPTY
    }

    private final UUID instanceId = UUID.randomUUID();
    private final Weapon definition;
    private int magazine;
    private int reserve;
    private long nextShot;
    private long reloadEnds = -1;

    public WeaponState(Weapon definition) {
        this.definition = definition;
        magazine = definition.magazine();
        reserve = definition.reserve();
    }

    public Shot shoot(long tick) {
        tick(tick);
        if (reloadEnds >= 0) return Shot.RELOADING;
        if (tick < nextShot) return Shot.COOLDOWN;
        if (magazine == 0) return Shot.EMPTY;
        magazine--;
        nextShot = tick + definition.cooldownTicks();
        return Shot.FIRED;
    }

    public boolean reload(long tick) {
        tick(tick);
        if (reloadEnds >= 0 || magazine == definition.magazine() || reserve == 0) return false;
        reloadEnds = tick + definition.reloadTicks();
        return true;
    }

    public void tick(long tick) {
        if (reloadEnds < 0 || tick < reloadEnds) return;
        int amount = Math.min(definition.magazine() - magazine, reserve);
        magazine += amount;
        reserve -= amount;
        reloadEnds = -1;
    }

    public void cancelReload() {
        reloadEnds = -1;
    }

    public boolean refill() {
        if (reserve == definition.reserve()) return false;
        reserve = definition.reserve();
        return true;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public Weapon definition() {
        return definition;
    }

    public int magazine() {
        return magazine;
    }

    public int reserve() {
        return reserve;
    }

    public boolean reloading() {
        return reloadEnds >= 0;
    }

    public long reloadRemaining(long tick) {
        return Math.max(0, reloadEnds - tick);
    }
}
