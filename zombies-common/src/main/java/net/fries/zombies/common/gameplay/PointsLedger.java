package net.fries.zombies.common.gameplay;

import net.fries.zombies.common.content.Gameplay;

import java.util.*;

/**
 * Gold is issued only from applied damage receipts, with attack/target deduplication and lifetime
 * caps.
 */
public final class PointsLedger {
    public enum Kind {
        BULLET,
        MELEE,
        EXPLOSIVE,
        DAMAGE_OVER_TIME
    }

    public record Damage(
            UUID attack,
            UUID enemy,
            UUID player,
            long tick,
            Kind kind,
            boolean headshot,
            double before,
            double after) {}

    private static final class Enemy {
        double lowestHealth = Double.POSITIVE_INFINITY;
        int paid;
        boolean dead;
        final Set<UUID> attacks = new HashSet<>();
        final Map<UUID, Long> contributors = new HashMap<>();
    }

    private final PointsPolicy policy;
    private final Map<UUID, Survivor> players;
    private final Map<UUID, Enemy> enemies = new HashMap<>();
    private final Map<String, Set<String>> repaired = new HashMap<>();
    private final Map<String, Integer> repairGold = new HashMap<>();
    private final Set<String> objectives = new HashSet<>();
    private long doubleGoldUntil;

    public PointsLedger(PointsPolicy policy, Map<UUID, Survivor> players) {
        this.policy = policy;
        this.players = players;
    }

    public void register(UUID enemy) {
        if (enemies.putIfAbsent(enemy, new Enemy()) != null)
            throw new IllegalArgumentException("Enemy already registered");
    }

    public int damage(Damage receipt, Gameplay.Weapon weapon) {
        if (receipt.attack() == null
                || receipt.kind() == null
                || (receipt.kind() == Kind.BULLET && weapon == null)) return 0;
        var enemy = enemies.get(receipt.enemy());
        var player = players.get(receipt.player());
        if (enemy == null
                || enemy.dead
                || player == null
                || player.life() == Survivor.Life.LEFT
                || receipt.tick() < 0
                || !Double.isFinite(receipt.before())
                || !Double.isFinite(receipt.after())
                || receipt.before() <= 0
                || receipt.after() < 0
                || receipt.after() >= Math.min(receipt.before(), enemy.lowestHealth)
                || enemy.attacks.contains(receipt.attack())) return 0;
        enemy.lowestHealth = receipt.after();
        enemy.contributors.put(receipt.player(), receipt.tick());
        int base =
                switch (receipt.kind()) {
                    case BULLET ->
                            receipt.headshot()
                                    ? weapon.behaviour().headGold()
                                    : weapon.behaviour().bodyGold();
                    case MELEE -> policy.meleeGold();
                    case EXPLOSIVE -> policy.explosiveGold();
                    case DAMAGE_OVER_TIME -> policy.damageOverTimeGold();
                };
        if (receipt.after() == 0) base = Math.addExact(base, policy.lethalBonus());
        int amount = payEnemy(enemy, receipt.player(), base, receipt.tick());
        if (amount > 0) enemy.attacks.add(receipt.attack());
        if (receipt.after() == 0) {
            enemy.dead = true;
            for (var contributor : enemy.contributors.entrySet())
                if (!contributor.getKey().equals(receipt.player())
                        && receipt.tick() - contributor.getValue() <= policy.assistWindowTicks())
                    payEnemy(enemy, contributor.getKey(), policy.assistGold(), receipt.tick());
        }
        return amount;
    }

    private int payEnemy(Enemy enemy, UUID player, int base, long tick) {
        int amount = Math.min(Math.max(0, policy.targetGoldCap() - enemy.paid), scaled(base, tick));
        if (credit(player, amount, tick)) {
            enemy.paid += amount;
            return amount;
        }
        return 0;
    }

    private int scaled(int base, long tick) {
        return Math.multiplyExact(base, tick < doubleGoldUntil ? 2 : 1);
    }

    private boolean credit(UUID id, int amount, long tick) {
        var s = players.get(id);
        if (s == null || s.life() == Survivor.Life.LEFT || amount <= 0) return false;
        s.gold(amount, tick);
        return true;
    }

    public int repair(
            UUID player,
            String window,
            String board,
            int round,
            boolean validTransition,
            int perBoard,
            int roundCap,
            long tick) {
        if (!validTransition) return 0;
        String key = player + ":" + window + ":" + round;
        var boards = repaired.computeIfAbsent(window + ":" + round, k -> new HashSet<>());
        if (boards.contains(board)) return 0;
        int amount =
                Math.min(
                        Math.max(0, roundCap - repairGold.getOrDefault(key, 0)),
                        scaled(perBoard, tick));
        if (!credit(player, amount, tick)) return 0;
        boards.add(board);
        repairGold.merge(key, amount, Math::addExact);
        return amount;
    }

    public int objective(UUID player, String objective, int round, long tick) {
        String key = player + ":" + objective + ":" + round;
        if (objectives.contains(key)) return 0;
        int amount = scaled(policy.objectiveGold(), tick);
        if (!credit(player, amount, tick)) return 0;
        objectives.add(key);
        return amount;
    }

    public void doubleGold(long tick, int duration) {
        if (duration < 1 || duration > 72000)
            throw new IllegalArgumentException("Invalid power-up duration");
        doubleGoldUntil = Math.max(doubleGoldUntil, tick + duration);
    }

    public long doubleGoldRemaining(long tick) {
        return Math.max(0, doubleGoldUntil - tick);
    }

    public int paid(UUID enemy) {
        var state = enemies.get(enemy);
        return state == null ? 0 : state.paid;
    }

    public boolean seen(UUID enemy, UUID attack) {
        var state = enemies.get(enemy);
        return state != null && state.attacks.contains(attack);
    }
}
