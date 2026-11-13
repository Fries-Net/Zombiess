package net.fries.zombies.playtest;

import io.papermc.paper.command.brigadier.*;

import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.common.match.MatchPhase;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.world.MapWorlds;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;

import java.util.*;

/** Console-only assertions, packaged separately from the deployable plugin. */
public final class PaperPlaytest extends JavaPlugin implements BasicCommand {
    private ZombiesPlugin zombies;
    private boolean quiet;
    private UUID target;
    private double targetHealth;
    private int ammoBefore, pointsBefore;
    private long pausedTick;
    private String previousWorld;
    private UUID previousMatch;
    private UUID awaitingRespawn;
    private boolean observedRespawn;
    private final Map<UUID, Long> matchesBefore = new HashMap<>();

    @Override
    public void onEnable() {
        zombies = (ZombiesPlugin) getServer().getPluginManager().getPlugin("FriesNetZombies");
        registerCommand("fnztest", this);
        getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        () -> {
                            var r = zombies.matches().current();
                            if (quiet && r != null && r.waves != null) r.waves.pause(true);
                            if (awaitingRespawn != null
                                    && r != null
                                    && r.survivor(awaitingRespawn) != null
                                    && r.survivor(awaitingRespawn).life() == Survivor.Life.ALIVE)
                                observedRespawn = true;
                        },
                        1,
                        1);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (source.getSender() instanceof Player) return;
        String step = args.length == 0 ? "status" : args[0];
        try {
            switch (step) {
                case "setup" -> {
                    for (var p : getServer().getOnlinePlayers()) {
                        check(
                                !zombies.matches().managed(p.getUniqueId()),
                                "player recovery finished");
                        p.getInventory().clear();
                        p.getInventory().setItem(4, new ItemStack(Material.EMERALD, 7));
                        p.setGameMode(GameMode.SURVIVAL);
                        p.setLevel(17);
                        p.setExp(.3f);
                        p.setFoodLevel(19);
                    }
                    check(getServer().getOnlinePlayers().size() == 2, "two connected clients");
                }
                case "playing" -> {
                    var r = run();
                    check(r.playing(), "in progress");
                    check(
                            r.session.snapshot().initialPlayerCount() == 2,
                            "frozen two-player scaling");
                    check(r.players.size() == 2, "two survivors");
                    check(r.world.getPlayers().size() == 2, "admission to disposable world");
                    previousWorld = r.worldName;
                    previousMatch = r.session.snapshot().identity().matchId();
                    quiet = true;
                }
                case "body" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = target(r);
                    position(p, z, 1.0);
                    var w = r.combat.held(p);
                    ammoBefore = w.magazine();
                    targetHealth = z.getHealth();
                    r.combat.shoot(p);
                    check(
                            Math.abs(z.getHealth() - (targetHealth - w.definition().damage()))
                                    < .01,
                            "body damage: health="
                                    + z.getHealth()
                                    + " before="
                                    + targetHealth
                                    + " ammo="
                                    + w.magazine());
                    check(w.magazine() == ammoBefore - 1, "one bullet consumed");
                    r.combat.shoot(p);
                    check(w.magazine() == ammoBefore - 1, "same-tick cooldown");
                }
                case "head" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = (Zombie) getServer().getEntity(target);
                    check(z != null, "target present");
                    position(p, z, 1.7);
                    var w = r.combat.held(p);
                    double health = z.getHealth();
                    r.combat.shoot(p);
                    check(
                            Math.abs(
                                            z.getHealth()
                                                    - (health
                                                            - w.definition().damage()
                                                                    * w.definition()
                                                                            .headshotMultiplier()))
                                    < .01,
                            "headshot multiplier");
                }
                case "occlusion" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = (Zombie) getServer().getEntity(target);
                    position(p, z, 1.0);
                    r.world.getBlockAt(-4, 65, 3).setType(Material.STONE);
                    double health = z.getHealth();
                    r.combat.shoot(p);
                    check(z.getHealth() == health, "wall blocks hitscan");
                    r.world.getBlockAt(-4, 65, 3).setType(Material.AIR);
                }
                case "reload" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var w = r.combat.held(p);
                    ammoBefore = w.magazine() + w.reserve();
                    check(w.reloading(), "F-key packet starts reload");
                    r.combat.shoot(p);
                    check(w.magazine() + w.reserve() == ammoBefore, "reload prevents shooting");
                }
                case "reloaded" -> {
                    var r = run();
                    var w = r.combat.held(player("SurvivorOne"));
                    check(!w.reloading(), "reload completed");
                    check(
                            w.magazine() == 20 && w.magazine() + w.reserve() == ammoBefore,
                            "reload conserves ammunition");
                }
                case "pause" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    zombies.matches().pause(true);
                    pausedTick = r.tick;
                    p.setNoDamageTicks(0);
                    double health = p.getHealth();
                    p.damage(100);
                    check(p.getHealth() == health, "paused damage blocked");
                }
                case "resume" -> {
                    var r = run();
                    check(r.tick == pausedTick, "pause freezes clocks");
                    check(
                            r.world.getEntitiesByClass(Zombie.class).stream()
                                    .noneMatch(Zombie::hasAI),
                            "pause freezes native mobs");
                    zombies.matches().pause(false);
                }
                case "purchases" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var s = r.survivor(p.getUniqueId());
                    var g = r.map.gates().getFirst();
                    p.teleport(new Location(r.world, 4.5, 64, .5));
                    int points = s.points();
                    check(r.interactions.interact(p), "gate interaction");
                    check(
                            s.points() == points && !r.interactions.open(g.id()),
                            "insufficient points do not mutate gate");
                    s.credit(10000);
                    points = s.points();
                    r.interactions.interact(p);
                    check(
                            r.interactions.open(g.id()) && s.points() == points - g.price(),
                            "gate purchase charged exactly once");
                    MapWorlds.each(
                            r.world,
                            g.box(),
                            b -> check(b.getType() == Material.AIR, "gate geometry removed"));
                    points = s.points();
                    r.interactions.interact(p);
                    check(s.points() == points, "opened gate cannot charge twice");
                    var barrier = r.map.gates().get(1);
                    p.teleport(new Location(r.world, 4.5, 64, 8.5));
                    r.interactions.interact(p);
                    check(
                            r.interactions.open(barrier.id())
                                    && s.points() == points - barrier.price(),
                            "barrier purchase");
                    points = s.points();
                    var shop = r.map.shops().get(1);
                    p.teleport(new Location(r.world, 11.5, 64, .5));
                    r.interactions.interact(p);
                    check(s.weapons().containsKey(shop.weapon()), "AK-47 purchased");
                    check(
                            s.points() == points - r.content.weapons().get(shop.weapon()).price(),
                            "weapon price");
                    p.getInventory().setHeldItemSlot(1);
                    var w = r.combat.held(p);
                    check(w != null && w.definition().name().equals("AK-47"), "AK equipped");
                    points = s.points();
                    r.interactions.interact(p);
                    check(s.points() == points, "full reserve cannot charge");
                    var z = (Zombie) getServer().getEntity(target);
                    position(p, z, 1.0);
                    r.combat.shoot(p);
                    r.combat.reload(p);
                    ammoBefore = w.magazine() + w.reserve();
                }
                case "ammo" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var w = r.combat.held(p);
                    check(w != null && !w.reloading(), "AK reload finished");
                    var s = r.survivor(p.getUniqueId());
                    int points = s.points();
                    p.teleport(new Location(r.world, 11.5, 64, .5));
                    r.interactions.interact(p);
                    check(
                            w.reserve() == 240 && s.points() == points - w.definition().ammoPrice(),
                            "purchased reserve ammo");
                    p.getInventory().setHeldItemSlot(0);
                }
                case "repair" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    p.teleport(new Location(r.world, -4.5, 64, -9.5));
                    p.setSneaking(true);
                    r.world.getBlockAt(-6, 64, -11).setType(Material.AIR);
                    pointsBefore = r.survivor(p.getUniqueId()).points();
                    check(r.interactions.interact(p), "repair channel starts");
                }
                case "repaired" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    check(
                            r.world.getBlockAt(-6, 64, -11).getType() == Material.IRON_BARS,
                            "repair restored geometry");
                    check(
                            r.survivor(p.getUniqueId()).points()
                                    == pointsBefore + r.content.repairPoints(),
                            "repair reward");
                    p.setSneaking(false);
                }
                case "repair-cancel" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    r.world.getBlockAt(-6, 64, -11).setType(Material.AIR);
                    p.setSneaking(true);
                    pointsBefore = r.survivor(p.getUniqueId()).points();
                    r.interactions.interact(p);
                    p.teleport(MapWorlds.location(r.world, r.map.playerSpawn()));
                    p.setSneaking(false);
                }
                case "repair-cancelled" -> {
                    var r = run();
                    check(
                            r.survivor(player("SurvivorOne").getUniqueId()).points()
                                    == pointsBefore,
                            "interrupted repair grants nothing");
                    check(
                            r.world.getBlockAt(-6, 64, -11).getType() == Material.AIR,
                            "interrupted repair changes no blocks");
                }
                case "down" -> {
                    var r = run();
                    var a = player("SurvivorOne");
                    var b = player("SurvivorTwo");
                    a.teleport(new Location(r.world, -4.5, 64, .5));
                    b.teleport(new Location(r.world, -3, 64, .5));
                    a.setNoDamageTicks(0);
                    a.damage(100);
                    check(
                            r.survivor(a.getUniqueId()).life() == Survivor.Life.DOWNED,
                            "lethal damage downs");
                    check(
                            r.session.snapshot().phase() == MatchPhase.IN_PROGRESS,
                            "teammate prevents defeat");
                    b.setSneaking(true);
                }
                case "revived" -> {
                    var r = run();
                    var a = player("SurvivorOne");
                    var b = player("SurvivorTwo");
                    check(
                            r.survivor(a.getUniqueId()).life() == Survivor.Life.ALIVE,
                            "revive completes");
                    check(
                            r.survivor(b.getUniqueId()).result(r.content, false).revives() == 1,
                            "revive credited once");
                    b.setSneaking(false);
                }
                case "bleed" -> {
                    var p = player("SurvivorTwo");
                    p.setNoDamageTicks(0);
                    p.damage(100);
                    awaitingRespawn = p.getUniqueId();
                    observedRespawn = false;
                }
                case "bled" -> {
                    var r = run();
                    var p = player("SurvivorTwo");
                    check(
                            r.survivor(p.getUniqueId()).life() == Survivor.Life.DEAD
                                    && p.getGameMode() == GameMode.SPECTATOR,
                            "bleedout enters spectator");
                }
                case "hud" -> {
                    var r = run();
                    for (var p : r.world.getPlayers())
                        check(
                                p.getScoreboard().getObjective(DisplaySlot.SIDEBAR) != null,
                                "owned sidebar");
                }
                case "battle" -> {
                    var r = run();
                    var z = (Zombie) getServer().getEntity(target);
                    if (z != null) {
                        z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(18);
                        z.setHealth(18);
                    }
                    quiet = false;
                    r.waves.pause(false);
                    for (var p : r.world.getPlayers()) {
                        p.setSneaking(false);
                        p.teleport(MapWorlds.location(r.world, r.map.playerSpawn()));
                        p.setHealth(20);
                        p.setNoDamageTicks(60);
                    }
                }
                case "restored" -> {
                    if (awaitingRespawn != null)
                        check(observedRespawn, "dead teammate returned on round clear");
                    check(zombies.matches().current() == null, "instance released");
                    if (previousWorld != null) {
                        check(getServer().getWorld(previousWorld) == null, "world unloaded");
                        check(
                                !getServer()
                                        .getWorldContainer()
                                        .toPath()
                                        .resolve(previousWorld)
                                        .toFile()
                                        .exists(),
                                "world files removed");
                    } else
                        try (var paths =
                                java.nio.file.Files.list(
                                        getServer().getWorldContainer().toPath())) {
                            check(
                                    paths.noneMatch(
                                            p ->
                                                    p.getFileName()
                                                            .toString()
                                                            .matches("fnz_[0-9a-f]{32}")),
                                    "crash worlds removed");
                        }
                    for (var p : getServer().getOnlinePlayers()) {
                        check(!zombies.matches().managed(p.getUniqueId()), "player released");
                        check(p.getGameMode() == GameMode.SURVIVAL, "original mode");
                        check(
                                p.getInventory().getItem(4) != null
                                        && p.getInventory().getItem(4).getType() == Material.EMERALD
                                        && p.getInventory().getItem(4).getAmount() == 7,
                                "original inventory");
                        check(
                                p.getLevel() == 17 && Math.abs(p.getExp() - .3) < .01,
                                "original experience");
                        check(p.getWorld().getName().equals("world"), "original world");
                    }
                }
                case "fresh" -> {
                    var r = run();
                    check(
                            !r.session.snapshot().identity().matchId().equals(previousMatch),
                            "new match identity");
                    check(!r.worldName.equals(previousWorld), "new disposable world");
                    check(r.session.snapshot().initialPlayerCount() == 2, "fresh scaling");
                    check(!r.interactions.open("workshop"), "gate reset");
                    check(
                            r.world.getBlockAt(6, 64, 0).getType() == Material.OAK_PLANKS,
                            "door blocks restored");
                    check(
                            r.world.getBlockAt(-6, 64, -11).getType() == Material.IRON_BARS,
                            "barricade restored");
                    previousWorld = r.worldName;
                    previousMatch = r.session.snapshot().identity().matchId();
                    quiet = true;
                }
                case "defeat" -> {
                    var r = run();
                    for (var p : r.world.getPlayers()) {
                        p.setNoDamageTicks(0);
                        p.damage(100);
                    }
                }
                case "frozen" -> {
                    var r = run();
                    check(
                            r.session.snapshot().initialPlayerCount() == 2,
                            "disconnect does not rescale");
                    check(
                            r.waves.remaining() == r.content.waves().at(1, 2).count(),
                            "wave retains two-player spawn budget");
                    check(
                            r.players.values().stream()
                                            .filter(s -> s.life() == Survivor.Life.LEFT)
                                            .count()
                                    == 1,
                            "quit updates membership");
                }
                case "accounts" -> {
                    int expected = Integer.parseInt(args[1]);
                    var futures =
                            getServer().getOnlinePlayers().stream()
                                    .map(
                                            p ->
                                                    zombies.accounts()
                                                            .account(p.getUniqueId())
                                                            .thenAccept(
                                                                    a -> {
                                                                        check(
                                                                                a.matchesPlayed()
                                                                                        == expected,
                                                                                "durable match"
                                                                                    + " count "
                                                                                        + a
                                                                                                .matchesPlayed()
                                                                                        + " expected"
                                                                                        + " "
                                                                                        + expected);
                                                                        check(
                                                                                a.tokens() >= 23
                                                                                        && a
                                                                                                        .highestRound()
                                                                                                == 3,
                                                                                "victory rewards"
                                                                                    + " and round"
                                                                                    + " record");
                                                                    }))
                                    .toArray(java.util.concurrent.CompletableFuture[]::new);
                    java.util.concurrent.CompletableFuture.allOf(futures)
                            .whenComplete(
                                    (v, e) -> {
                                        if (e == null)
                                            getLogger().info("PLAYTEST_PASS accounts-" + expected);
                                        else getLogger().severe("PLAYTEST_FAIL accounts " + e);
                                    });
                    return;
                }
                case "status" -> getLogger().info("PLAYTEST_STATUS " + zombies.matches().status());
                default -> throw new IllegalArgumentException("Unknown test step");
            }
            getLogger().info("PLAYTEST_PASS " + step);
        } catch (Throwable e) {
            getLogger().severe("PLAYTEST_FAIL " + step + " " + e);
            e.printStackTrace();
        }
    }

    private MatchRun run() {
        var r = zombies.matches().current();
        check(r != null, "match present");
        return r;
    }

    private Player player(String name) {
        var p = getServer().getPlayerExact(name);
        check(p != null, "client present: " + name);
        return p;
    }

    private Zombie target(MatchRun r) {
        var z =
                r.world.getEntitiesByClass(Zombie.class).stream()
                        .filter(v -> r.waves.contains(v.getUniqueId()))
                        .findFirst()
                        .orElseThrow();
        target = z.getUniqueId();
        z.setAI(false);
        z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
        z.setHealth(100);
        z.teleport(new Location(r.world, -3.5, 64, 5.5));
        return z;
    }

    private void position(Player p, Zombie z, double height) {
        var l = new Location(run().world, -3.5, 64, .5);
        l.setDirection(
                z.getLocation()
                        .add(0, height, 0)
                        .toVector()
                        .subtract(l.clone().add(0, 1.62, 0).toVector()));
        p.teleport(l);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
