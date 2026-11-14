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
    private Weapon definition;
    private int upgradeLevel;
    private long upgradedAt = -1;
    private int magazine;
    private int reserve;
    private final WeaponTiming timing = new WeaponTiming();
    private long reloadEnds = -1;
    private long shotsFired, lastShotTick = -1;

    public WeaponState(Weapon definition) {
        this.definition = definition;
        magazine = definition.magazine();
        reserve = definition.reserve();
    }

    public Shot shoot(long tick) {
        return shoot(tick, 1, definition.cooldownTicks());
    }

    public Shot shoot(long tick, double rate, int cooldown) {
        tick(tick);
        if (reloadEnds >= 0) return Shot.RELOADING;
        if (!timing.ready(tick)) return Shot.COOLDOWN;
        if (magazine == 0) return Shot.EMPTY;
        magazine--;
        shotsFired++;
        lastShotTick = tick;
        timing.fired(tick, cooldown, rate);
        return Shot.FIRED;
    }

    public boolean reload(long tick) {
        return reload(tick, 1);
    }

    public boolean reload(long tick, double speed) {
        tick(tick);
        if (reloadEnds >= 0 || magazine == definition.magazine() || reserve == 0) return false;
        reloadEnds = tick + WeaponTiming.duration(definition.reloadTicks(), speed);
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

    /** Same owned identity and ammunition; upgrades never reset recovery or finish a reload. */
    public boolean upgrade(
            Survivor owner,
            net.fries.zombies.common.content.WeaponUpgrades rules,
            int expectedLevel,
            int price,
            long tick) {
        var tier = rules.next(definition.id(), upgradeLevel);
        if (price < 0 || price > 100000)
            throw new IllegalArgumentException("Invalid upgrade price");
        if (owner.life() != Survivor.Life.ALIVE
                || owner.weapons().get(definition.id()) != this
                || tier == null
                || expectedLevel != upgradeLevel
                || reloading()
                || upgradedAt >= 0 && tick - upgradedAt < 10
                || !owner.spend(price)) return false;
        definition = tier.apply(definition);
        upgradeLevel++;
        upgradedAt = tick;
        return true;
    }

    public int upgradeLevel() {
        return upgradeLevel;
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

    public long readyAt() {
        return timing.readyAt();
    }

    public long fireRemaining(long tick) {
        return timing.remaining(tick);
    }

    public long shotsFired() {
        return shotsFired;
    }

    public long lastShotTick() {
        return lastShotTick;
    }
}
