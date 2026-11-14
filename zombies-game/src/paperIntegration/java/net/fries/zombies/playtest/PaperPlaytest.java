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
    private UUID jambZombie;
    private double targetHealth;
    private int ammoBefore, pointsBefore;
    private long pausedTick;
    private String previousWorld;
    private UUID previousMatch;
    private UUID awaitingRespawn;
    private boolean observedRespawn;
    private final Map<String, List<Long>> shotTicks = new HashMap<>();
    private final Map<String, Long> observedShots = new HashMap<>();
    private boolean trackingShots;
    private boolean battleDiagnostics;
    private long switchDeadline;
    private List<Zombie> piercingTargets;
    private Item powerup;
    private final Map<UUID, Long> matchesBefore = new HashMap<>();

    @Override
    public void onEnable() {
        zombies = (ZombiesPlugin) getServer().getPluginManager().getPlugin("FriesNetZombies");
        // Idle control players must not be killed by flat-world slimes while another client edits.
        // Match/draft worlds explicitly retain NORMAL difficulty and production zombie spawning.
        var hub = Bukkit.getWorld("world");
        if (hub != null) {
            hub.setGameRule(GameRules.SPAWN_MOBS, false);
            hub.setDifficulty(Difficulty.PEACEFUL);
        }
        registerCommand("fnztest", this);
        getServer()
                .getPluginManager()
                .registerEvents(
                        new org.bukkit.event.Listener() {
                            @org.bukkit.event.EventHandler(
                                    priority = org.bukkit.event.EventPriority.MONITOR)
                            public void editorTool(org.bukkit.event.player.PlayerInteractEvent e) {
                                var session =
                                        zombies.matches()
                                                .editor()
                                                .session(e.getPlayer().getUniqueId());
                                if (session != null)
                                    getLogger()
                                            .info(
                                                    "PLAYTEST_EDITOR_INPUT action="
                                                            + e.getAction()
                                                            + " hand="
                                                            + e.getHand()
                                                            + " item="
                                                            + (e.getItem() == null
                                                                    ? "none"
                                                                    : e.getItem().getType())
                                                            + " block="
                                                            + e.getClickedBlock()
                                                            + " selected="
                                                            + session.selectedId);
                            }
                        },
                        this);
        getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        () -> {
                            var r = zombies.matches().current();
                            if (quiet && r != null && r.waves != null) r.waves.pause(true);
                            if (battleDiagnostics
                                    && r != null
                                    && r.playing()
                                    && r.tick % 200 == 0) {
                                getLogger()
                                        .info(
                                                "PLAYTEST_BATTLE round="
                                                        + r.waves.round()
                                                        + " remaining="
                                                        + r.waves.remaining()
                                                        + " enemies="
                                                        + r
                                                                .world
                                                                .getEntitiesByClass(Zombie.class)
                                                                .stream()
                                                                .filter(
                                                                        z ->
                                                                                r.waves.contains(
                                                                                        z
                                                                                                .getUniqueId()))
                                                                .map(
                                                                        z ->
                                                                                z.getLocation()
                                                                                                .toVector()
                                                                                        + " hp="
                                                                                        + z
                                                                                                .getHealth()
                                                                                        + " ai="
                                                                                        + z.hasAI()
                                                                                        + " exterior="
                                                                                        + r.windows
                                                                                                .exterior(
                                                                                                        z
                                                                                                                .getUniqueId()))
                                                                .toList());
                                for (var id : r.players.keySet()) {
                                    var p = Bukkit.getPlayer(id);
                                    var w = p == null ? null : r.combat.held(p);
                                    getLogger()
                                            .info(
                                                    "PLAYTEST_BATTLE player="
                                                            + id
                                                            + " life="
                                                            + r.survivor(id).life()
                                                            + " weapon="
                                                            + (w == null
                                                                    ? "none"
                                                                    : w.definition().name()
                                                                            + " shots="
                                                                            + w.shotsFired()
                                                                            + " ammo="
                                                                            + w.magazine()
                                                                            + "/"
                                                                            + w.reserve()));
                                }
                            }
                            if (trackingShots && r != null && r.combat != null)
                                for (String name : List.of("SurvivorOne", "SurvivorTwo")) {
                                    var p = getServer().getPlayerExact(name);
                                    if (p == null) continue;
                                    var w = r.combat.held(p);
                                    if (w == null) continue;
                                    if (w.shotsFired()
                                            != observedShots.getOrDefault(name, w.shotsFired()))
                                        shotTicks.get(name).add(w.lastShotTick());
                                    observedShots.put(name, w.shotsFired());
                                }
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
                        p.setHealth(20);
                        p.setLevel(17);
                        p.setExp(.3f);
                        p.setFoodLevel(19);
                        p.saveData();
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
                case "window-jamb-start" -> {
                    var r = run();
                    quiet = false;
                    r.waves.pause(false);
                    var z =
                            r.world.getEntitiesByClass(Zombie.class).stream()
                                    .filter(v -> r.waves.contains(v.getUniqueId()))
                                    .findFirst()
                                    .orElseThrow();
                    var window = r.windows.definition("north");
                    jambZombie = z.getUniqueId();
                    r.windows.assign(z, window);
                    z.teleport(MapWorlds.location(r.world, window.entry()));
                    check(!r.windows.guide(z), "fully cleared entry releases guidance");
                    z.teleport(new Location(r.world, -5.5, 64, -9.4875));
                    check(
                            r.windows.guide(z) && !z.isCollidable(),
                            "jamb pushback reacquires physical entry guidance");
                }
                case "window-jamb-entered" -> {
                    var r = run();
                    new org.bukkit.scheduler.BukkitRunnable() {
                        int remaining = 100;

                        public void run() {
                            var z = (Zombie) Bukkit.getEntity(jambZombie);
                            if (z != null
                                    && !r.windows.exterior(jambZombie)
                                    && r.map.playableBounds()
                                            .contains(
                                                    new net.fries.zombies.common.content.MapTemplate
                                                            .Point(z.getX(), z.getY(), z.getZ()))) {
                                quiet = true;
                                r.waves.pause(true);
                                cancel();
                                getLogger().info("PLAYTEST_PASS window-jamb-entered");
                            } else if (--remaining == 0) {
                                cancel();
                                getLogger()
                                        .severe(
                                                "PLAYTEST_FAIL window-jamb-entered native entry did"
                                                    + " not clear: "
                                                        + (z == null
                                                                ? "missing"
                                                                : z.getLocation()));
                            }
                        }
                    }.runTaskTimer(this, 1, 1);
                    return;
                }
                case "body" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = target(r);
                    position(p, z, 1.0);
                    var w = r.combat.held(p);
                    ammoBefore = w.magazine();
                    targetHealth = z.getHealth();
                    int gold = r.survivor(p.getUniqueId()).points();
                    r.combat.shoot(p);
                    check(
                            r.survivor(p.getUniqueId()).points() == gold + 10,
                            "body hit grants 10 gold");
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
                case "timing-start" -> {
                    var r = run();
                    var p = player("SurvivorTwo");
                    giveAk(p);
                    p.getInventory().setHeldItemSlot(1);
                    player("SurvivorOne").teleport(new Location(r.world, -4.5, 64, .5, -90, 0));
                    p.teleport(new Location(r.world, -4.5, 64, 2.5, -90, 0));
                    beginTracking();
                }
                case "timing-rates" -> {
                    getLogger()
                            .info(
                                    "PLAYTEST_TIMING glock="
                                            + shotTicks.get("SurvivorOne")
                                            + " ak="
                                            + shotTicks.get("SurvivorTwo"));
                    for (var entry : Map.of("SurvivorOne", 30L, "SurvivorTwo", 20L).entrySet()) {
                        var ticks = shotTicks.get(entry.getKey());
                        check(
                                ticks.size() >= 3,
                                "multiple client shots " + entry.getKey() + " " + ticks);
                        for (int i = 1; i < ticks.size(); i++) {
                            long interval = ticks.get(i) - ticks.get(i - 1);
                            check(
                                    interval >= entry.getValue()
                                            && interval <= entry.getValue() + 3,
                                    "distinct configured cadence " + entry.getKey() + " " + ticks);
                        }
                        var w = run().combat.held(player(entry.getKey()));
                        check(
                                w.magazine() == w.definition().magazine() - ticks.size(),
                                "exact rapid-click ammunition " + entry.getKey());
                    }
                }
                case "timing-hold-start" -> beginTracking();
                case "timing-held" -> {
                    check(
                            shotTicks.get("SurvivorOne").size() == 1,
                            "semi-auto holds fire once " + shotTicks);
                    check(
                            shotTicks.get("SurvivorTwo").size() == 3,
                            "automatic native hold fires every 20 ticks " + shotTicks);
                }
                case "timing-reset" -> {
                    trackingShots = false;
                    for (String name : List.of("SurvivorOne", "SurvivorTwo")) {
                        var p = player(name);
                        var r = run();
                        r.combat.switched(p);
                        p.getInventory().setHeldItemSlot(0);
                        p.getInventory().setItem(1, null);
                        r.survivor(p.getUniqueId())
                                .weapons()
                                .remove(
                                        new net.fries.zombies.common.NamespacedId(
                                                "friesnet:weapon/ak47"));
                    }
                }
                case "timing-switch-start" -> {
                    var p = player("SurvivorOne");
                    giveAk(p);
                    var w = run().combat.held(p);
                    long before = w.shotsFired();
                    run().combat.shoot(p);
                    check(w.shotsFired() == before + 1, "switch fixture successful Glock shot");
                    switchDeadline = w.readyAt();
                }
                case "timing-switched" -> {
                    var w = run().combat.held(player("SurvivorOne"));
                    check(w.definition().name().equals("AK-47"), "actual client hotbar switch");
                    check(
                            w.shotsFired() == 1 && w.magazine() == 19,
                            "switch spam cannot bypass previous recovery");
                    check(w.lastShotTick() >= switchDeadline, "Glock recovery survives AK switch");
                }
                case "head" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = (Zombie) getServer().getEntity(target);
                    check(z != null, "target present");
                    position(p, z, 1.7);
                    var w = r.combat.held(p);
                    double health = z.getHealth();
                    int gold = r.survivor(p.getUniqueId()).points();
                    r.combat.shoot(p);
                    check(
                            r.survivor(p.getUniqueId()).points() == gold + 15,
                            "head hit grants 15 gold");
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
                    int gold = r.survivor(p.getUniqueId()).points();
                    r.combat.shoot(p);
                    check(z.getHealth() == health, "wall blocks hitscan");
                    check(r.survivor(p.getUniqueId()).points() == gold, "wall hits grant no gold");
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
                            w.magazine() == w.definition().magazine()
                                    && w.magazine() + w.reserve() == ammoBefore,
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
                    check(
                            r.windows.active().size() == 3,
                            "door unlocking activates workshop window spawns");
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
                case "gold-invalid" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = (Zombie) getServer().getEntity(target);
                    var s = r.survivor(p.getUniqueId());
                    int gold = s.points();
                    double health = z.getHealth();
                    z.setInvulnerable(true);
                    r.combat.shoot(p);
                    check(
                            s.points() == gold && z.getHealth() == health,
                            "invulnerable target grants no gold");
                    z.setInvulnerable(false);
                    check(
                            r.combat
                                            .damageService()
                                            .apply(
                                                    UUID.randomUUID(),
                                                    p,
                                                    z,
                                                    0,
                                                    false,
                                                    PointsLedger.Kind.BULLET,
                                                    r.content.weapons().get(r.content.starter()))
                                    == 0,
                            "zero damage rejected");
                    check(s.points() == gold, "invalid damage unchanged");
                }
                case "gold-area" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var fresh =
                            r.world.getEntitiesByClass(Zombie.class).stream()
                                    .filter(
                                            z ->
                                                    r.waves.contains(z.getUniqueId())
                                                            && !z.getUniqueId().equals(target))
                                    .limit(3)
                                    .toList();
                    check(fresh.size() == 3, "three registered test enemies");
                    int index = 0;
                    for (var z : fresh) {
                        z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
                        z.setHealth(100);
                        z.teleport(new Location(r.world, -3.5, 64, 5.5 + index++ * 2));
                    }
                    p.teleport(new Location(r.world, -3.5, 64, .5));
                    var s = r.survivor(p.getUniqueId());
                    int gold = s.points();
                    var action = UUID.randomUUID();
                    var w = r.content.weapons().get(r.content.starter());
                    r.combat
                            .damageService()
                            .area(
                                    action,
                                    p,
                                    fresh.subList(0, 2),
                                    10,
                                    PointsLedger.Kind.EXPLOSIVE,
                                    w);
                    check(s.points() == gold + 10, "explosion awards each distinct target");
                    r.combat
                            .damageService()
                            .area(
                                    action,
                                    p,
                                    fresh.subList(0, 2),
                                    10,
                                    PointsLedger.Kind.EXPLOSIVE,
                                    w);
                    check(
                            s.points() == gold + 10 && fresh.getFirst().getHealth() == 90,
                            "duplicate explosion applies and rewards once");
                    r.combat
                            .damageService()
                            .area(
                                    UUID.randomUUID(),
                                    p,
                                    fresh.subList(0, 2),
                                    2,
                                    PointsLedger.Kind.DAMAGE_OVER_TIME,
                                    w);
                    check(
                            s.points() == gold + 10 && fresh.getFirst().getHealth() == 88,
                            "periodic damage has configured zero gold");
                    piercingTargets = fresh;
                }
                case "gold-pierce" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var old = (Zombie) getServer().getEntity(target);
                    old.teleport(new Location(r.world, -10.5, 64, .5));
                    var id = new net.fries.zombies.common.NamespacedId("friesnet:weapon/awp");
                    var w = new WeaponState(r.content.weapons().get(id));
                    r.survivor(p.getUniqueId()).weapons().put(id, w);
                    p.getInventory().setItem(2, r.combat.item(w));
                    p.getInventory().setHeldItemSlot(2);
                    position(p, piercingTargets.getFirst(), 1.0);
                    int gold = r.survivor(p.getUniqueId()).points();
                    var health = piercingTargets.stream().map(Zombie::getHealth).toList();
                    r.combat.shoot(p);
                    for (int i = 0; i < 3; i++)
                        check(
                                Math.abs(piercingTargets.get(i).getHealth() - (health.get(i) - 30))
                                        < .01,
                                "AWP damages each pierced enemy once");
                    check(
                            w.magazine() == 0 && r.survivor(p.getUniqueId()).points() == gold + 60,
                            "AWP single round and three distinct hit rewards");
                    r.combat.reload(p);
                    check(w.reloading(), "AWP long reload starts");
                }
                case "gold-pierce-reloaded" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var w = r.combat.held(p);
                    check(
                            w.magazine() == 1 && w.reserve() == 39 && !w.reloading(),
                            "AWP long reload conserves ammo");
                    r.combat.switched(p);
                    p.getInventory().setHeldItemSlot(0);
                    p.getInventory().setItem(2, null);
                    r.survivor(p.getUniqueId()).weapons().remove(w.definition().id());
                }
                case "gold-melee" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    var z = piercingTargets.getFirst();
                    p.teleport(z.getLocation().clone().subtract(0, 0, 1.5));
                    p.getInventory().setHeldItemSlot(8);
                    int gold = r.survivor(p.getUniqueId()).points();
                    double health = z.getHealth();
                    p.attack(z);
                    check(
                            Math.abs(z.getHealth() - (health - 6)) < .01
                                    && r.survivor(p.getUniqueId()).points() == gold + 10,
                            "native knife event grants valid melee gold");
                    p.getInventory().setHeldItemSlot(0);
                }
                case "gold-objective" -> {
                    var r = run();
                    var s = r.survivor(player("SurvivorOne").getUniqueId());
                    int gold = s.points();
                    check(
                            r.points.objective(s.id(), "test-power", r.waves.round(), r.tick)
                                    == 100,
                            "objective reward");
                    check(
                            r.points.objective(s.id(), "test-power", r.waves.round(), r.tick) == 0
                                    && s.points() == gold + 100,
                            "objective duplicate rejected");
                }
                case "gold-powerup" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    powerup = r.powerups.spawn(p.getLocation().clone().add(0, .3, 0));
                    powerup.setPickupDelay(0);
                }
                case "gold-powered" -> {
                    var r = run();
                    check(
                            !powerup.isValid() && r.points.doubleGoldRemaining(r.tick) > 0,
                            "native pickup enables team double gold");
                    var p = player("SurvivorOne");
                    var z = piercingTargets.getFirst();
                    int gold = r.survivor(p.getUniqueId()).points();
                    r.combat
                            .damageService()
                            .apply(
                                    UUID.randomUUID(),
                                    p,
                                    z,
                                    1,
                                    false,
                                    PointsLedger.Kind.BULLET,
                                    r.content.weapons().get(r.content.starter()));
                    check(
                            r.survivor(p.getUniqueId()).points() == gold + 20,
                            "powerup doubles hit gold");
                }
                case "window-shoot" -> {
                    var r = run();
                    check(r.windows.active().size() == 2, "locked room window inactive");
                    var p = player("SurvivorOne");
                    var z =
                            r.world.getEntitiesByClass(Zombie.class).stream()
                                    .filter(v -> r.waves.contains(v.getUniqueId()))
                                    .findFirst()
                                    .orElseThrow();
                    z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
                    z.setHealth(100);
                    z.teleport(new Location(r.world, -4.5, 64, -11.8));
                    p.teleport(new Location(r.world, -4.5, 64, -8.5));
                    var at = p.getLocation();
                    at.setDirection(
                            z.getLocation()
                                    .add(0, 1, 0)
                                    .toVector()
                                    .subtract(p.getEyeLocation().toVector()));
                    p.teleport(at);
                    int gold = r.survivor(p.getUniqueId()).points();
                    r.combat.shoot(p);
                    check(
                            z.getHealth() == 94
                                    && r.survivor(p.getUniqueId()).points() == gold + 10,
                            "intact designated boards permit valid shots");
                    check(
                            r.windows.state("north").intactCount() == 6,
                            "shots do not damage boards");
                }
                case "window-start" -> {
                    var r = run();
                    quiet = false;
                    r.waves.pause(false);
                    int offset = 0;
                    for (var z : r.world.getEntitiesByClass(Zombie.class))
                        if (r.waves.contains(z.getUniqueId())) {
                            z.teleport(
                                    new Location(
                                            r.world,
                                            -4.5 + (offset++ % 2 == 0 ? .3 : -.3),
                                            64,
                                            -11.2));
                            r.windows.assign(z, r.windows.definition("north"));
                        }
                    player("SurvivorOne")
                            .teleport(MapWorlds.location(r.world, r.map.playerSpawn()));
                }
                case "window-damaging" -> {
                    var s = run().windows.state("north");
                    check(
                            s.intactCount() == 6
                                    && s.damageProgress() > 0
                                    && s.damageProgress() < 1,
                            "visible progressive board damage");
                }
                case "window-first-board" -> {
                    var r = run();
                    check(
                            r.windows.state("north").intactCount() == 5,
                            "multiple attackers remove one board per duration");
                    check(
                            r.world.getEntitiesByClass(Zombie.class).stream()
                                    .filter(z -> r.waves.contains(z.getUniqueId()))
                                    .noneMatch(
                                            z ->
                                                    r.map.playableBounds()
                                                            .contains(
                                                                    new net.fries.zombies.common
                                                                            .content.MapTemplate
                                                                            .Point(
                                                                            z.getX(), z.getY(),
                                                                            z.getZ()))),
                            "remaining board still separates enemies");
                }
                case "window-entered" -> {
                    var r = run();
                    long inside =
                            r.world.getEntitiesByClass(Zombie.class).stream()
                                    .filter(z -> r.waves.contains(z.getUniqueId()))
                                    .filter(
                                            z ->
                                                    r.map.playableBounds()
                                                            .contains(
                                                                    new net.fries.zombies.common
                                                                            .content.MapTemplate
                                                                            .Point(
                                                                            z.getX(), z.getY(),
                                                                            z.getZ())))
                                    .count();
                    getLogger()
                            .info(
                                    "PLAYTEST_WINDOW positions="
                                            + r.world.getEntitiesByClass(Zombie.class).stream()
                                                    .map(
                                                            z ->
                                                                    z.getLocation().toVector()
                                                                            + " exterior="
                                                                            + r.windows.exterior(
                                                                                    z
                                                                                            .getUniqueId()))
                                                    .toList());
                    check(
                            inside >= 2,
                            "multiple zombies navigate through opening without teleport: "
                                    + inside);
                    check(
                            r.windows.state("north").intactCount() == 4,
                            "zombies enter as soon as passage is available");
                    quiet = true;
                    r.waves.pause(true);
                }
                case "repair" -> {
                    var r = run();
                    for (var z : r.world.getEntitiesByClass(Zombie.class))
                        if (r.waves.contains(z.getUniqueId()))
                            z.teleport(new Location(r.world, -10.5, 64, .5));
                    var p = player("SurvivorOne");
                    p.teleport(new Location(r.world, -4.5, 64, -8.5));
                    p.setSneaking(true);
                    pointsBefore = r.survivor(p.getUniqueId()).points();
                    check(
                            r.windows.state("north").missing() == 0,
                            "missing window board can be rebuilt by holding sneak");
                }
                case "repaired" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    check(
                            r.world.getBlockAt(-5, 64, -10).getType() == Material.OAK_TRAPDOOR,
                            "repair restored geometry");
                    check(
                            r.survivor(p.getUniqueId()).points()
                                    == pointsBefore + r.content.repairPoints(),
                            "repair reward");
                    p.setSneaking(false);
                }
                case "repair-cap-start" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    p.setSneaking(true);
                    pointsBefore = r.survivor(p.getUniqueId()).points();
                    check(r.windows.state("north").missing() == 1, "second missing board");
                }
                case "repair-capped" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    check(r.windows.state("north").intactCount() == 6, "second board rebuilt");
                    check(
                            r.survivor(p.getUniqueId()).points() == pointsBefore,
                            "window per-round gold cap respected");
                }
                case "window-rebreak" -> {
                    var r = run();
                    quiet = false;
                    r.waves.pause(false);
                    for (var z : r.world.getEntitiesByClass(Zombie.class))
                        if (r.waves.contains(z.getUniqueId())) {
                            z.teleport(new Location(r.world, -4.5, 64, -11.2));
                            r.windows.assign(z, r.windows.definition("north"));
                        }
                }
                case "window-under-attack" -> {
                    var r = run();
                    check(
                            r.windows.state("north").missing() == 0
                                    && r.windows.state("north").repairProgress() == 0,
                            "active attackers interrupt rebuilding");
                }
                case "window-occupied" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    p.teleport(new Location(r.world, -4.5, 64, -8.5));
                    p.setSneaking(true);
                    player("SurvivorTwo").teleport(new Location(r.world, -4.5, 64, -9.5));
                    pointsBefore = r.survivor(p.getUniqueId()).points();
                }
                case "window-space-checked" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    check(
                            r.windows.state("north").missing() == 0
                                    && r.world.getBlockAt(-5, 64, -10).getType() == Material.AIR,
                            "occupied opening cannot be rebuilt");
                    check(
                            r.survivor(p.getUniqueId()).points() == pointsBefore,
                            "occupied board grants nothing");
                    p.setSneaking(false);
                    p.teleport(MapWorlds.location(r.world, r.map.playerSpawn()));
                    player("SurvivorTwo").teleport(new Location(r.world, -2.5, 64, .5));
                }
                case "repair-cancel" -> {
                    var r = run();
                    var p = player("SurvivorOne");
                    quiet = true;
                    r.waves.pause(true);
                    for (var z : r.world.getEntitiesByClass(Zombie.class))
                        if (r.waves.contains(z.getUniqueId()))
                            z.teleport(new Location(r.world, -10.5, 64, .5));
                    p.setSneaking(true);
                    pointsBefore = r.survivor(p.getUniqueId()).points();
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
                            r.world.getBlockAt(-5, 64, -10).getType() == Material.AIR,
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
                    battleDiagnostics = true;
                    var r = run();
                    for (var z : r.world.getEntitiesByClass(Zombie.class))
                        if (r.waves.contains(z.getUniqueId())) {
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
                                p.getAttribute(Attribute.MAX_HEALTH).getValue() == 20
                                        && Math.abs(p.getWalkSpeed() - .2) < .001,
                                "perk attributes and movement restored after cleanup/crash");
                        check(
                                p.getInventory().getItem(4) != null
                                        && p.getInventory().getItem(4).getType() == Material.EMERALD
                                        && p.getInventory().getItem(4).getAmount() == 7,
                                "original inventory for "
                                        + p.getName()
                                        + ": "
                                        + p.getInventory().getItem(4));
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
                            r.world.getBlockAt(-5, 64, -10).getType() == Material.OAK_TRAPDOOR,
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
                    PaperPerkChecks.seedCrash(r, player("SurvivorOne"));
                    PaperUpgradeChecks.seedCrash(r, player("SurvivorOne"));
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
                default -> {
                    if (step.equals("editor-closed") || step.equals("editor-second-closed")) {
                        PaperEditorChecks.awaitClosed(
                                zombies,
                                this,
                                step.equals("editor-closed") ? "SurvivorOne" : "SurvivorTwo",
                                step);
                        return;
                    }
                    if (!PaperUpgradeChecks.handle(step, zombies)
                            && !PaperPerkChecks.handle(step, zombies)
                            && !PaperEditorChecks.handle(step, zombies))
                        throw new IllegalArgumentException("Unknown test step");
                }
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

    private void giveAk(Player p) {
        var r = run();
        var id = new net.fries.zombies.common.NamespacedId("friesnet:weapon/ak47");
        var w = new WeaponState(r.content.weapons().get(id));
        r.survivor(p.getUniqueId()).weapons().put(id, w);
        p.getInventory().setItem(1, r.combat.item(w));
    }

    private void beginTracking() {
        trackingShots = true;
        for (String name : List.of("SurvivorOne", "SurvivorTwo")) {
            shotTicks.put(name, new ArrayList<>());
            observedShots.put(name, run().combat.held(player(name)).shotsFired());
        }
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
        if (z.getAttribute(Attribute.MAX_HEALTH).getBaseValue() < 100) {
            z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
            z.setHealth(100);
        }
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
