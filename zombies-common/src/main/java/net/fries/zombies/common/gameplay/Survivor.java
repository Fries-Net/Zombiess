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
    private final FireControl fireControl = new FireControl();
    private double fireRate = 1, reloadSpeed = 1, damageMultiplier = 1;
    private long bleedout;
    private int points, kills, headshots, revives, rounds;
    private int goldDelta;
    private long goldTick = -100;

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
        if (content.scoring().fixedRewards())
            credit(content.killPoints() + (headshot ? content.headshotPoints() : 0));
    }

    public void round(Gameplay content) {
        rounds++;
        if (content.scoring().fixedRewards()) credit(content.roundPoints());
    }

    public void down(long tick, int duration) {
        if (life != Life.ALIVE) return;
        life = Life.DOWNED;
        bleedout = tick + duration;
        weapons.values().forEach(WeaponState::cancelReload);
        fireControl.cancel();
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

    public void gold(int amount, long tick) {
        credit(amount);
        goldDelta = tick - goldTick <= 20 ? Math.addExact(goldDelta, amount) : amount;
        goldTick = tick;
    }

    public String goldFeedback(long tick) {
        return tick - goldTick <= 30 ? " +" + goldDelta + " gold" : "";
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

    public FireControl fireControl() {
        return fireControl;
    }

    public double fireRate() {
        return fireRate;
    }

    public double reloadSpeed() {
        return reloadSpeed;
    }

    public double damageMultiplier() {
        return damageMultiplier;
    }

    public void weaponModifiers(double fireRate, double reloadSpeed, double damageMultiplier) {
        WeaponTiming.duration(1, fireRate);
        WeaponTiming.duration(1, reloadSpeed);
        if (!Double.isFinite(damageMultiplier) || damageMultiplier <= 0 || damageMultiplier > 10)
            throw new IllegalArgumentException("Invalid damage modifier");
        this.fireRate = fireRate;
        this.reloadSpeed = reloadSpeed;
        this.damageMultiplier = damageMultiplier;
    }

    public long bleedoutRemaining(long tick) {
        return Math.max(0, bleedout - tick);
    }
}
