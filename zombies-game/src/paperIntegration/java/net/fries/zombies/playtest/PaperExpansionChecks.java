package net.fries.zombies.playtest;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;
import net.fries.zombies.common.content.PowerUps.Type;
import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.world.ZombieFactory;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;

import java.util.*;

final class PaperExpansionChecks {
    private static final List<Zombie> models = new ArrayList<>();
    private static final Map<UUID, List<UUID>> weapons = new HashMap<>();
    private static final Map<UUID, Integer> gold = new HashMap<>();
    private static Set<UUID> nuked;
    private static Item pickup;
    private static Item hovering;
    private static Location hoverOrigin;
    private static int nukeReward;

    static void seedCrash(MatchRun r, Player p) {
        check(
                r.powerups.spawn(p.getLocation().clone().add(5, 0, 0), Type.MAX_AMMO) != null,
                "crash drop fixture");
    }

    static boolean handle(String step, ZombiesPlugin plugin) {
        if (!step.startsWith("enemy-")
                && !step.startsWith("powerup-")
                && !step.startsWith("profile-")) return false;
        var r = plugin.matches().current();
        var one = Bukkit.getPlayerExact("SurvivorOne");
        var two = Bukkit.getPlayerExact("SurvivorTwo");
        switch (step) {
            case "enemy-spawn" -> {
                for (String invalid : List.of("AIR", "FIRE", "MISSING_MATERIAL")) {
                    boolean rejected = false;
                    try {
                        net.fries.zombies.game.world.ContentCatalog.validatePowerupMaterial(
                                invalid);
                    } catch (IllegalArgumentException expected) {
                        rejected = true;
                    }
                    check(rejected, "invalid power-up item rejected before gameplay");
                }
                var factory = new ZombieFactory();
                int i = 0;
                for (var type : r.archetypes.types().values()) {
                    var at = new Location(r.world, -11.5 + (i++) * 2, 64, -6.5);
                    var mob =
                            factory.spawn(
                                    at,
                                    type,
                                    r.archetypes.stats(
                                            type, r.content.waves().at(1, 2), new Random(1)));
                    mob.setAI(false);
                    models.add(mob);
                    check(mob.isBaby() == type.baby(), "native archetype age");
                }
            }
            case "enemy-checked" -> {
                for (var mob : models) {
                    var key =
                            mob.getPersistentDataContainer()
                                    .get(
                                            new NamespacedKey("friesnetzombies", "archetype"),
                                            org.bukkit.persistence.PersistentDataType.STRING);
                    var type = r.archetypes.types().get(new NamespacedId(key));
                    check(
                            mob.getAttribute(Attribute.MAX_HEALTH).getValue()
                                    <= r.archetypes.maxHealth(),
                            "bounded health");
                    check(
                            mob.getAttribute(Attribute.MOVEMENT_SPEED).getValue()
                                    <= r.archetypes.maxSpeed(),
                            "bounded effective movement including babies");
                    check(
                            Math.abs(mob.getAttribute(Attribute.ARMOR).getValue() - type.armour())
                                    < .001,
                            "explicit protection does not double-stack equipment");
                    check(
                            type.equipment().equals("NONE")
                                    || mob.getEquipment().getHelmet() != null
                                            && mob.getEquipment().getHelmetDropChance() == 0,
                            "visible non-dropping armour");
                    check(
                            !type.elite() || mob.isGlowing() && mob.customName() != null,
                            "elite visual identity");
                    check(!type.baby() || mob.getHeight() < 1.2, "native baby collision box");
                }
                models.forEach(Entity::remove);
                models.clear();
            }
            case "powerup-max-start" -> {
                one.teleport(new Location(r.world, .5, 64, -2.5));
                two.teleport(new Location(r.world, 2.5, 64, -2.5));
                for (var p : List.of(one, two)) {
                    var s = r.survivor(p.getUniqueId());
                    weapons.put(
                            s.id(),
                            s.weapons().values().stream().map(WeaponState::instanceId).toList());
                    s.weapons()
                            .values()
                            .forEach(
                                    w -> {
                                        w.shoot(r.tick);
                                        w.reload(r.tick);
                                    });
                }
                pickup = r.powerups.spawn(one.getLocation(), Type.MAX_AMMO);
                check(pickup != null, "max-ammo drop");
            }
            case "powerup-max-claimed" -> {
                check(!pickup.isValid(), "native collision collected max ammo");
                for (var s : r.players.values()) {
                    check(
                            weapons.get(s.id())
                                    .equals(
                                            s.weapons().values().stream()
                                                    .map(WeaponState::instanceId)
                                                    .toList()),
                            "max ammo preserves every instance");
                    check(
                            s.weapons().values().stream()
                                    .allMatch(
                                            w ->
                                                    w.magazine() == w.definition().magazine()
                                                            && w.reserve()
                                                                    == w.definition().reserve()
                                                            && !w.reloading()),
                            "team max ammo uses upgraded capacities");
                }
                check(
                        !r.powerups.pickup(one, pickup),
                        "duplicate collection event cannot grant twice");
                pickup = r.powerups.spawn(one.getLocation(), Type.INSTA_KILL);
                check(pickup != null, "insta-kill drop");
            }
            case "powerup-insta" -> {
                check(r.powerups.instaRemaining() > 0, "native insta-kill collection");
                var enemy = r.waves.enemies().getFirst();
                enemy.getAttribute(Attribute.MAX_HEALTH).setBaseValue(1000);
                enemy.setHealth(1000);
                enemy.setInvulnerable(false);
                var s = r.survivor(one.getUniqueId());
                int before = s.points();
                var w = r.combat.held(one);
                var action = UUID.randomUUID();
                check(
                        r.combat
                                                .damageService()
                                                .apply(
                                                        action,
                                                        one,
                                                        enemy,
                                                        1,
                                                        true,
                                                        PointsLedger.Kind.BULLET,
                                                        w.definition())
                                        > 0
                                && enemy.isDead(),
                        "valid damaging hit kills eligible enemy");
                check(
                        s.points() == before + w.definition().behaviour().headGold(),
                        "insta-kill gives one headshot receipt");
                check(
                        r.combat
                                        .damageService()
                                        .apply(
                                                action,
                                                one,
                                                enemy,
                                                1,
                                                true,
                                                PointsLedger.Kind.BULLET,
                                                w.definition())
                                == 0,
                        "duplicate insta-kill event blocked");
                pickup = r.powerups.spawn(one.getLocation(), Type.DOUBLE_POINTS);
                check(pickup != null, "double-points drop");
            }
            case "powerup-double" -> {
                check(
                        r.points.doubleGoldRemaining(r.tick) > 0,
                        "existing points multiplier owns Double Points");
                long remaining = r.points.doubleGoldRemaining(r.tick);
                check(
                        !r.powerups.pickup(two, pickup)
                                && r.points.doubleGoldRemaining(r.tick) == remaining,
                        "second collector cannot extend consumed pickup");
                var d = r.map.windows().getFirst();
                var state = r.windows.state(d.id());
                int broken = -1;
                for (int i = 0; i < d.breakTicks(); i++) {
                    broken = state.attack(r.tick + i, true);
                    if (broken >= 0) break;
                }
                check(broken >= 0, "damaged window fixture");
                var board = d.boards().get(broken);
                r.world.getBlockAt(board.x(), board.y(), board.z()).setType(Material.AIR);
                for (var s : r.players.values()) gold.put(s.id(), s.points());
                pickup = r.powerups.spawn(one.getLocation(), Type.CARPENTER);
                check(pickup != null, "carpenter drop");
            }
            case "powerup-carpenter" -> {
                hovering = r.powerups.spawn(one.getLocation().clone().add(8, 0, 0), Type.MAX_AMMO);
                check(hovering != null, "stationary long-lived pickup fixture");
                hoverOrigin = hovering.getLocation();
                var d = r.map.windows().getFirst();
                check(
                        r.windows.state(d.id()).intactCount() == d.boards().size(),
                        "carpenter restores safe window state");
                for (var board : d.boards())
                    check(
                            r.world.getBlockAt(board.x(), board.y(), board.z()).getType()
                                    == Material.OAK_TRAPDOOR,
                            "carpenter restores physical geometry");
                for (var s : r.players.values())
                    check(
                            s.points() == gold.get(s.id()),
                            "carpenter gives no farmable repair gold");
                pickup = r.powerups.spawn(one.getLocation().clone().add(10, 0, 0), Type.NUKE);
                check(pickup != null, "expiring drop fixture");
            }
            case "powerup-expired" -> {
                check(
                        hovering.isValid()
                                && hovering.getLocation().distanceSquared(hoverOrigin) < .01,
                        "gravity-free pickup cannot drift away from its label");
                check(
                        !pickup.isValid() && !r.powerups.pickup(one, pickup),
                        "expired drop cannot activate");
                nuked = new HashSet<>(r.waves.enemies().stream().map(Entity::getUniqueId).toList());
                check(!nuked.isEmpty(), "nuke fixture has active enemies");
                for (var s : r.players.values()) gold.put(s.id(), s.points());
                nukeReward =
                        r.powerupRules.nukeGold()
                                * (r.points.doubleGoldRemaining(r.tick) > 0 ? 2 : 1);
                pickup = r.powerups.spawn(one.getLocation(), Type.NUKE);
                check(pickup != null, "nuke drop");
            }
            case "powerup-nuke" -> {
                check(
                        nuked.stream().noneMatch(r.waves::contains),
                        "nuke eliminates snapshot of eligible active zombies");
                for (var s : r.players.values())
                    check(
                            s.points() == gold.get(s.id()) + nukeReward,
                            "one configured team reward, no hit/assist bonus");
                check(!r.powerups.pickup(two, pickup), "duplicate nuke event is inert");
                check(
                        r.powerups.spawn(one.getLocation(), Type.NUKE) == null,
                        "round drop cap counts expired and collected drops");
                check(r.playing(), "nuke cannot double-finish the match");
                r.powerups.clear();
            }
            case "profile-start" -> {
                for (var p : List.of(one, two)) {
                    var s = r.survivor(p.getUniqueId());
                    var base = r.content.weapons().get(new NamespacedId("friesnet:weapon/mac10"));
                    var rapid =
                            new Gameplay.Weapon(
                                    base.id(),
                                    base.name(),
                                    base.material(),
                                    200,
                                    2000,
                                    base.damage(),
                                    base.headshotMultiplier(),
                                    base.range(),
                                    2,
                                    base.reloadTicks(),
                                    base.price(),
                                    base.ammoPrice(),
                                    base.behaviour());
                    s.weapons().put(base.id(), new WeaponState(rapid));
                    r.combat.equip(p);
                    p.getInventory().setHeldItemSlot(1);
                }
            }
            case "profile-stats" -> {
                var counts = new TreeMap<String, Integer>();
                for (var enemy : r.waves.enemies())
                    counts.merge(
                            r.waves.archetype(enemy.getUniqueId()).id().value(), 1, Integer::sum);
                long[] samples = Bukkit.getServer().getTickTimes().clone();
                Arrays.sort(samples);
                long oneShots = r.combat.held(one).shotsFired(),
                        twoShots = r.combat.held(two).shotsFired();
                check(
                        oneShots >= 40 && twoShots >= 40 && counts.size() >= 3,
                        "mixed population under sustained two-tick fire");
                plugin.getLogger()
                        .info(
                                "PLAYTEST_PROFILE mspt_p50="
                                        + samples[samples.length / 2] / 1e6
                                        + " mspt_p95="
                                        + samples[(int) (samples.length * .95)] / 1e6
                                        + " population="
                                        + counts
                                        + " shots="
                                        + oneShots
                                        + "/"
                                        + twoShots);
            }
            default -> throw new IllegalArgumentException("Unknown expansion stage");
        }
        return true;
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
}
