package net.fries.zombies.game.combat;

import net.fries.zombies.common.content.Gameplay;
import net.fries.zombies.common.gameplay.PointsLedger;
import net.fries.zombies.game.match.MatchRun;

import org.bukkit.entity.*;

import java.util.*;

/** Single applied-damage boundary for bullets, melee, area effects and periodic damage. */
public final class MatchDamageService {
    private record Attack(long tick, Set<UUID> targets) {}

    private final MatchRun run;
    private final Map<UUID, Attack> attacks = new LinkedHashMap<>();
    private UUID authorized;

    public MatchDamageService(MatchRun run) {
        this.run = run;
    }

    public boolean authorizes(UUID target) {
        return target.equals(authorized);
    }

    public double apply(
            UUID attack,
            Player player,
            Zombie enemy,
            double amount,
            boolean headshot,
            PointsLedger.Kind kind,
            Gameplay.Weapon weapon) {
        if (!run.alive(player.getUniqueId())
                || player.getWorld() != run.world
                || enemy.getWorld() != run.world
                || !enemy.isValid()
                || enemy.isDead()
                || enemy.isInvulnerable()
                || !run.waves.contains(enemy.getUniqueId())
                || !Double.isFinite(amount)
                || amount <= 0
                || run.points.seen(enemy.getUniqueId(), attack)) return 0;
        if (attacks.size() > 8192 || run.tick % 20 == 0)
            attacks.entrySet().removeIf(e -> run.tick - e.getValue().tick() > 40);
        if (attacks.size() > 8192)
            throw new IllegalStateException("Damage action capacity exceeded");
        if (!attacks.computeIfAbsent(attack, k -> new Attack(run.tick, new HashSet<>()))
                .targets()
                .add(enemy.getUniqueId())) return 0;
        double before = enemy.getHealth();
        enemy.setNoDamageTicks(0);
        run.waves.attribute(enemy.getUniqueId(), player.getUniqueId(), headshot);
        authorized = enemy.getUniqueId();
        try {
            enemy.damage(amount, player);
        } finally {
            authorized = null;
        }
        if (!enemy.isDead()) run.waves.forgetAttribution(enemy.getUniqueId());
        double after = Math.max(0, enemy.getHealth());
        if (after >= before) return 0;
        run.points.damage(
                new PointsLedger.Damage(
                        attack,
                        enemy.getUniqueId(),
                        player.getUniqueId(),
                        run.tick,
                        kind,
                        headshot,
                        before,
                        after),
                weapon);
        return before - after;
    }

    public double area(
            UUID action,
            Player player,
            Collection<Zombie> targets,
            double damage,
            PointsLedger.Kind kind,
            Gameplay.Weapon weapon) {
        if (kind != PointsLedger.Kind.EXPLOSIVE && kind != PointsLedger.Kind.DAMAGE_OVER_TIME)
            throw new IllegalArgumentException("Invalid area damage kind");
        double applied = 0;
        for (var enemy : targets.stream().distinct().limit(100).toList()) {
            if (!player.hasLineOfSight(enemy)) continue;
            applied += apply(action, player, enemy, damage, false, kind, weapon);
        }
        return applied;
    }
}
