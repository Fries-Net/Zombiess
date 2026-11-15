package net.fries.zombies.playtest;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.world.*;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Native adult/baby navigation and full/partial policies using isolated board fixtures. */
final class PaperWindowChecks {
    static void start(ZombiesPlugin plugin, JavaPlugin assertions) {
        var real = plugin.matches().current();
        real.world.getEntitiesByClass(Zombie.class).stream()
                .filter(z -> real.waves.contains(z.getUniqueId()))
                .forEach(z -> z.teleport(new Location(real.world, -10.5, 64, .5)));
        new org.bukkit.scheduler.BukkitRunnable() {
            int scenario, age;
            MatchRun scene;
            WindowDefinition definition;
            final List<Zombie> mobs = new ArrayList<>();
            final Map<Block, BlockData> before = new LinkedHashMap<>();
            final Set<UUID> crossed = new HashSet<>();

            void prepare() {
                int variant = scenario % 5;
                var orientation = WindowDefinition.Orientation.values()[Math.min(variant, 3)];
                if (variant == 4) orientation = WindowDefinition.Orientation.NORTH;
                boolean ns =
                        orientation == WindowDefinition.Orientation.NORTH
                                || orientation == WindowDefinition.Orientation.SOUTH;
                boolean negative =
                        orientation == WindowDefinition.Orientation.NORTH
                                || orientation == WindowDefinition.Orientation.WEST;
                var boards = new ArrayList<WindowDefinition.Board>();
                for (int lateral : new int[] {0, -1, 1})
                    for (int y = 64; y <= 65; y++)
                        boards.add(
                                new WindowDefinition.Board(
                                        ns ? lateral : 0, y, ns ? 0 : lateral, "OAK_TRAPDOOR"));
                var approach =
                        new MapTemplate.Point(
                                ns ? .5 : negative ? -.8 : 1.8,
                                64,
                                ns ? (negative ? -.8 : 1.8) : .5);
                var entry =
                        new MapTemplate.Point(
                                ns ? .5 : negative ? 1.5 : -1.5,
                                64,
                                ns ? (negative ? 1.5 : -1.5) : .5);
                var spawn =
                        ns
                                ? new MapTemplate.Box(
                                        0, 64, negative ? -3 : 2, 0, 64, negative ? -2 : 3)
                                : new MapTemplate.Box(
                                        negative ? -3 : 2, 64, 0, negative ? -2 : 3, 64, 0);
                var playable =
                        ns
                                ? new MapTemplate.Box(
                                        -14, 64, negative ? 1 : -9, 14, 72, negative ? 9 : -1)
                                : new MapTemplate.Box(
                                        negative ? 1 : -14, 64, -9, negative ? 14 : -1, 72, 9);
                definition =
                        new WindowDefinition(
                                "fixture",
                                new MapTemplate.Point(entry.x(), 65, entry.z()),
                                orientation,
                                boards,
                                spawn,
                                approach,
                                entry,
                                5,
                                20,
                                0,
                                0,
                                true,
                                Set.of("spawn"),
                                new NamespacedId("friesnet:window/test"),
                                variant == 4
                                        ? WindowDefinition.EntryPolicy.PASSABLE_OPENING
                                        : WindowDefinition.EntryPolicy.ALL_BOARDS);
                var m = real.map;
                var map =
                        new MapTemplate(
                                m.id(),
                                m.version(),
                                m.name(),
                                entry,
                                m.bounds(),
                                m.blocks(),
                                m.zombieSpawns(),
                                m.gates(),
                                m.barricades(),
                                m.shops(),
                                List.of(definition),
                                m.initialRooms(),
                                playable,
                                m.worldSource(),
                                m.worldDigest(),
                                m.objects());
                scene =
                        new MatchRun(
                                real.session,
                                real.rules,
                                real.content,
                                map,
                                real.mysteryPools,
                                real.perkRules,
                                real.upgradeRules);
                scene.world = real.world;
                scene.players.putAll(real.players);
                scene.interactions = real.interactions;
                scene.lives = real.lives;
                for (var board : boards) {
                    var block = real.world.getBlockAt(board.x(), board.y(), board.z());
                    before.put(block, block.getBlockData());
                    block.setType(Material.OAK_TRAPDOOR);
                }
                scene.windows = new WindowService(scene);
                for (boolean baby : new boolean[] {false, true}) {
                    var mob =
                            real.world.spawn(
                                    MapWorlds.location(real.world, approach),
                                    Zombie.class,
                                    z -> {
                                        z.setPersistent(false);
                                        z.setBaby(baby);
                                        z.setShouldBurnInDay(false);
                                        z.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(.3);
                                        z.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(.1);
                                    });
                    if (baby && mob.getHeight() >= 1.2 || !baby && mob.getHeight() < 1.7)
                        throw new AssertionError("native body size");
                    mobs.add(mob);
                    scene.windows.assign(mob, definition);
                }
                age = 0;
            }

            void clean() {
                mobs.forEach(Zombie::remove);
                mobs.clear();
                crossed.clear();
                if (scene != null) scene.windows.clear();
                before.forEach((block, data) -> block.setBlockData(data, false));
                before.clear();
            }

            public void run() {
                try {
                    if (scene == null) prepare();
                    scene.tick = real.tick;
                    scene.windows.tick();
                    var state = scene.windows.state("fixture");
                    if (state.intactCount() == 4
                            && scenario % 5 < 4
                            && scene.windows.entryAllowed(definition))
                        throw new AssertionError("partial opening allowed premature entry");
                    for (var mob : mobs) {
                        scene.windows.guide(mob);
                        if (!scene.windows.exterior(mob.getUniqueId())
                                && definition.clearsBoardPlane(
                                        new MapTemplate.Point(mob.getX(), mob.getY(), mob.getZ()),
                                        mob.getWidth() / 2 + .05)) crossed.add(mob.getUniqueId());
                    }
                    if (crossed.size() == 2) {
                        if (scenario % 5 < 4 && state.intactCount() != 0
                                || scenario % 5 == 4 && state.intactCount() != 4)
                            throw new AssertionError("entry policy board state");
                        assertions
                                .getLogger()
                                .info(
                                        "PLAYTEST_WINDOW_CARDINAL "
                                                + definition.orientation()
                                                + " "
                                                + definition.entryPolicy()
                                                + " adult+baby");
                        clean();
                        scene = null;
                        scenario++;
                        if (scenario == 10) {
                            cancel();
                            assertions.getLogger().info("PLAYTEST_PASS window-cardinals");
                        }
                    } else if (++age > 240)
                        throw new AssertionError(
                                "native passage deadline "
                                        + definition.orientation()
                                        + " boards="
                                        + state.intactCount()
                                        + " positions="
                                        + mobs.stream()
                                                .map(
                                                        z ->
                                                                z.getLocation().toVector()
                                                                        + " baby="
                                                                        + z.isBaby()
                                                                        + " ai="
                                                                        + z.hasAI()
                                                                        + " vehicle="
                                                                        + z.getVehicle()
                                                                        + " path="
                                                                        + z.getPathfinder()
                                                                                .hasPath()
                                                                        + " goals="
                                                                        + Bukkit.getMobGoals()
                                                                                .getRunningGoals(z))
                                                .toList());
                } catch (Throwable error) {
                    clean();
                    cancel();
                    assertions.getLogger().severe("PLAYTEST_FAIL window-cardinals " + error);
                }
            }
        }.runTaskTimer(assertions, 1, 1);
    }
}
