package net.fries.zombies.common.gameplay;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.Gameplay;
import net.fries.zombies.common.match.PlayerResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Per-match points, statistics and life; no permanent balances or Paper dependency. */
public final class Survivor {
    public enum Life {
        ALIVE,
        DOWNED,
        DEAD,
        LEFT
    }

    private final UUID id;
    private final Map<NamespacedId, WeaponState> weapons = new LinkedHashMap<>();
    private Life life = Life.ALIVE;
    private long bleedout;
    private int points, kills, headshots, revives, rounds;

    public Survivor(UUID id, Gameplay content) {
        this.id = id;
        points = content.startingPoints();
        weapons.put(content.starter(), new WeaponState(content.weapons().get(content.starter())));
    }

    public boolean spend(int price) {
        if (price < 0) throw new IllegalArgumentException("Negative price");
        if (points < price) return false;
        points -= price;
        return true;
    }

    public void credit(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative credit");
        points = Math.addExact(points, value);
    }

    public void kill(boolean headshot, Gameplay content) {
        kills++;
        if (headshot) headshots++;
        credit(content.killPoints() + (headshot ? content.headshotPoints() : 0));
    }

    public void round(Gameplay content) {
        rounds++;
        credit(content.roundPoints());
    }

    public void down(long tick, int duration) {
        if (life != Life.ALIVE) return;
        life = Life.DOWNED;
        bleedout = tick + duration;
        weapons.values().forEach(WeaponState::cancelReload);
    }

    public boolean bleed(long tick) {
        if (life == Life.DOWNED && tick >= bleedout) {
            life = Life.DEAD;
            return true;
        }
        return false;
    }

    public boolean revive(Survivor target) {
        if (life != Life.ALIVE || target.life != Life.DOWNED || target == this) return false;
        target.life = Life.ALIVE;
        revives++;
        return true;
    }

    public void respawn() {
        if (life == Life.DEAD) life = Life.ALIVE;
    }

    public void leave() {
        life = Life.LEFT;
        weapons.values().forEach(WeaponState::cancelReload);
    }

    public PlayerResult result(Gameplay content, boolean win) {
        return new PlayerResult(
                id,
                rounds,
                kills,
                headshots,
                revives,
                (long) rounds * content.tokensPerRound()
                        + (win && life != Life.LEFT ? content.victoryTokens() : 0),
                (long) kills * content.experiencePerKill() + (long) rounds * 10);
    }

    public Map<NamespacedId, WeaponState> weapons() {
        return weapons;
    }

    public Life life() {
        return life;
    }

    public int points() {
        return points;
    }

    public int kills() {
        return kills;
    }

    public int rounds() {
        return rounds;
    }

    public UUID id() {
        return id;
    }

    public long bleedoutRemaining(long tick) {
        return Math.max(0, bleedout - tick);
    }
}
