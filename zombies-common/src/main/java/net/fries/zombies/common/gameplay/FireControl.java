package net.fries.zombies.common.gameplay;

import net.fries.zombies.common.content.Gameplay;

/**
 * One trigger per player. Switching cancels trigger/burst state, but never clears shot recovery.
 */
public final class FireControl {
    private final WeaponTiming recovery = new WeaponTiming();
    private WeaponState selected;
    private boolean held;
    private int burstRemaining;

    public WeaponState.Shot attempt(WeaponState weapon, long tick, double rate) {
        if (!recovery.ready(tick)) return WeaponState.Shot.COOLDOWN;
        var result = weapon.shoot(tick, rate, weapon.definition().cooldownTicks());
        if (result == WeaponState.Shot.FIRED) recovery.retainUntil(weapon.readyAt());
        return result;
    }

    public WeaponState.Shot press(WeaponState weapon, long tick, double rate) {
        if (held && selected == weapon) return WeaponState.Shot.COOLDOWN;
        cancel();
        selected = weapon;
        held = true;
        if (!recovery.ready(tick)) return WeaponState.Shot.COOLDOWN;
        burstRemaining =
                weapon.definition().behaviour().mode() == Gameplay.FireMode.BURST
                        ? weapon.definition().behaviour().burstShots()
                        : 1;
        return fire(tick, rate);
    }

    public WeaponState.Shot poll(WeaponState weapon, long tick, double rate, boolean using) {
        if (selected != weapon) {
            cancel();
            return WeaponState.Shot.COOLDOWN;
        }
        if (!using) held = false;
        if (burstRemaining > 0) return fire(tick, rate);
        if (!recovery.ready(tick)) return WeaponState.Shot.COOLDOWN;
        if (held && weapon.definition().behaviour().mode() == Gameplay.FireMode.AUTOMATIC)
            return fire(tick, rate);
        return WeaponState.Shot.COOLDOWN;
    }

    private WeaponState.Shot fire(long tick, double rate) {
        var b = selected.definition().behaviour();
        int delay =
                burstRemaining > 1 ? b.burstIntervalTicks() : selected.definition().cooldownTicks();
        var result = selected.shoot(tick, rate, delay);
        if (result == WeaponState.Shot.FIRED) {
            burstRemaining = Math.max(0, burstRemaining - 1);
            recovery.fired(tick, selected.definition().cooldownTicks(), rate);
        } else if (result == WeaponState.Shot.EMPTY || result == WeaponState.Shot.RELOADING)
            burstRemaining = 0;
        return result;
    }

    public void release() {
        held = false;
    }

    public void cancel() {
        held = false;
        burstRemaining = 0;
        selected = null;
    }

    public long remaining(long tick) {
        return recovery.remaining(tick);
    }
}
