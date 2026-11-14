package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.gameplay.WindowState;
import net.fries.zombies.game.match.MatchRun;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.*;
import org.bukkit.util.*;

import java.util.*;

/** Physical window ownership, navigation, board damage and continuous sneak rebuilding. */
public final class WindowService {
    private final MatchRun run;
    private final Map<String, WindowDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, WindowState> states = new HashMap<>();
    private final Map<String, List<Location>> spawns = new HashMap<>();
    private final Map<String, WindowDefinition.Board> components = new HashMap<>();
    private final Map<UUID, String> assigned = new HashMap<>();
    private final Map<UUID, String> arrivals = new HashMap<>();
    private final Map<String, Boolean> attacking = new HashMap<>();

    public WindowService(MatchRun run) {
        this.run = run;
        for (var d : run.map.windows()) {
            definitions.put(d.id(), d);
            states.put(d.id(), new WindowState(d.boards().size(), d.breakTicks(), d.repairTicks()));
            for (var board : d.boards()) {
                var block = block(board);
                if (block.getType() != MapWorlds.material(board.material()))
                    throw new IllegalArgumentException(
                            "Window board does not match published geometry");
                place(d, board);
                components.put(key(block), board);
            }
            var safe = new ArrayList<Location>();
            MapWorlds.each(
                    run.world,
                    d.spawnRegion(),
                    b -> {
                        if (b.isPassable()
                                && b.getRelative(0, 1, 0).isPassable()
                                && b.getRelative(0, -1, 0).getType().isSolid())
                            safe.add(b.getLocation().add(.5, 0, .5));
                    });
            if (safe.isEmpty())
                throw new IllegalArgumentException("Window has no safe exterior spawn");
            for (var p : safe)
                if (run.map.playableBounds().contains(point(p)))
                    throw new IllegalArgumentException("Window spawns inside playable area");
            spawns.put(d.id(), List.copyOf(safe));
        }
    }

    public List<WindowDefinition> active() {
        return definitions.values().stream()
                .filter(d -> d.requiredRooms().stream().allMatch(run.interactions::roomUnlocked))
                .toList();
    }

    public Location spawn(WindowDefinition d, int index) {
        var locations = spawns.get(d.id());
        return locations.get(Math.floorMod(index, locations.size())).clone();
    }

    public void assign(Zombie zombie, WindowDefinition d) {
        assigned.put(zombie.getUniqueId(), d.id());
        arrivals.put(zombie.getUniqueId(), d.id());
        zombie.setCollidable(false);
    }

    public boolean exterior(UUID zombie) {
        return assigned.containsKey(zombie);
    }

    public boolean guide(Zombie zombie) {
        String id = assigned.get(zombie.getUniqueId());
        if (id == null) {
            var previous = definitions.get(arrivals.get(zombie.getUniqueId()));
            if (previous != null
                    && !run.map.playableBounds().contains(point(zombie.getLocation()))
                    && zombie.getLocation()
                                    .distanceSquared(
                                            MapWorlds.location(run.world, previous.entry()))
                            < 9) {
                assign(zombie, previous);
                id = previous.id();
            }
        }
        if (id == null) return false;
        var d = definitions.get(id);
        if (cleared(zombie, d)) {
            assigned.remove(zombie.getUniqueId());
            zombie.setCollidable(true);
            return false;
        }
        zombie.setTarget(null);
        boolean open = opening(d);
        var destination = open ? entryDestination(d) : MapWorlds.location(run.world, d.approach());
        if (zombie.hasAI()) zombie.getPathfinder().moveTo(destination, 1.0);
        return true;
    }

    private boolean cleared(Zombie zombie, WindowDefinition d) {
        return run.map.playableBounds().contains(point(zombie.getLocation()))
                && d.clearsBoardPlane(point(zombie.getLocation()), zombie.getWidth() / 2 + .05);
    }

    private Location entryDestination(WindowDefinition d) {
        var configured = MapWorlds.location(run.world, d.entry());
        var farther = configured.clone();
        switch (d.orientation()) {
            case NORTH -> farther.add(0, 0, .75);
            case SOUTH -> farther.subtract(0, 0, .75);
            case WEST -> farther.add(.75, 0, 0);
            case EAST -> farther.subtract(.75, 0, 0);
        }
        // Native waypoint arrival has a tolerance. Aim into the next clear interior cell so the
        // whole body crosses the opening before returning to normal targeting/collision.
        return run.map.playableBounds().contains(point(farther))
                        && farther.getBlock().isPassable()
                        && farther.clone().add(0, 1, 0).getBlock().isPassable()
                        && farther.clone().subtract(0, 1, 0).getBlock().getType().isSolid()
                ? farther
                : configured;
    }

    private boolean opening(WindowDefinition d) {
        var b = d.boards().getFirst();
        int x = (int) Math.floor(d.approach().x()),
                y = (int) Math.floor(d.entry().y()),
                z = (int) Math.floor(d.approach().z());
        if (d.orientation() == WindowDefinition.Orientation.NORTH
                || d.orientation() == WindowDefinition.Orientation.SOUTH) z = b.z();
        else x = b.x();
        return run.world.getBlockAt(x, y, z).isPassable()
                && run.world.getBlockAt(x, y + 1, z).isPassable();
    }

    public void tick() {
        arrivals.keySet()
                .removeIf(
                        id -> {
                            var entity = Bukkit.getEntity(id);
                            return entity == null || !entity.isValid() || entity.isDead();
                        });
        assigned.keySet()
                .removeIf(
                        id -> {
                            var e = Bukkit.getEntity(id);
                            return e == null || !e.isValid() || e.isDead();
                        });
        for (var d : definitions.values()) {
            var state = states.get(d.id());
            boolean attack =
                    !opening(d)
                            && assigned.entrySet().stream()
                                    .filter(e -> e.getValue().equals(d.id()))
                                    .map(e -> Bukkit.getEntity(e.getKey()))
                                    .filter(e -> e instanceof Zombie z && z.hasAI())
                                    .anyMatch(
                                            e ->
                                                    e.getLocation()
                                                                    .distanceSquared(
                                                                            MapWorlds.location(
                                                                                    run.world,
                                                                                    d.approach()))
                                                            <= 3.5);
            attacking.put(d.id(), attack);
            int damaged = state.attackingBoard();
            int broken = state.attack(run.tick, attack);
            if (broken >= 0) {
                var board = d.boards().get(broken);
                clearCracks(d, board);
                block(board).setType(Material.AIR, false);
                effects(board, false);
                if (opening(d))
                    for (var assignment : List.copyOf(assigned.entrySet()))
                        if (assignment.getValue().equals(d.id())) {
                            var e = Bukkit.getEntity(assignment.getKey());
                            if (e instanceof Zombie zombie) guide(zombie);
                        }
            } else if (attack && damaged >= 0 && run.tick % 4 == 0) {
                var board = d.boards().get(damaged);
                for (var p : run.world.getPlayers())
                    p.sendBlockDamage(
                            block(board).getLocation(), state.damageProgress(), source(d, board));
            }
            Player repairer = null;
            if (!(attack && d.denyRepairUnderAttack()))
                repairer =
                        run.world.getPlayers().stream()
                                .filter(
                                        p ->
                                                run.alive(p.getUniqueId())
                                                        && p.isSneaking()
                                                        && !run.lives.reviving(p.getUniqueId())
                                                        && p.getLocation()
                                                                        .distanceSquared(
                                                                                MapWorlds.location(
                                                                                        run.world,
                                                                                        d.point()))
                                                                <= 12.25
                                                        && p.hasLineOfSight(
                                                                MapWorlds.location(
                                                                        run.world, d.point())))
                                .sorted(
                                        Comparator.<Player, Boolean>comparing(
                                                        p ->
                                                                !p.getUniqueId()
                                                                        .equals(state.repairer()))
                                                .thenComparing(p -> p.getUniqueId().toString()))
                                .findFirst()
                                .orElse(null);
            int missing = state.missing();
            boolean free = missing >= 0 && available(d.boards().get(missing));
            int rebuilt =
                    state.repair(
                            run.tick,
                            repairer == null ? null : repairer.getUniqueId(),
                            repairer != null,
                            free);
            if (rebuilt >= 0) {
                var board = d.boards().get(rebuilt);
                place(d, board);
                effects(board, true);
                run.points.repair(
                        repairer.getUniqueId(),
                        d.id(),
                        Integer.toString(rebuilt),
                        run.waves.round(),
                        true,
                        d.repairGold(),
                        d.roundGoldCap(),
                        run.tick);
            }
        }
    }

    private boolean available(WindowDefinition.Board board) {
        var b = block(board);
        if (!b.getType().isAir()) return false;
        var box =
                new BoundingBox(
                        board.x(),
                        board.y(),
                        board.z(),
                        board.x() + 1,
                        board.y() + 1,
                        board.z() + 1);
        return run.world.getNearbyEntities(box).stream()
                .noneMatch(e -> e instanceof LivingEntity && e.getBoundingBox().overlaps(box));
    }

    public boolean shootThrough(Block b) {
        var board = components.get(key(b));
        return board != null && b.getType() == MapWorlds.material(board.material());
    }

    public org.bukkit.util.RayTraceResult blocking(
            Location start, org.bukkit.util.Vector direction, double range) {
        var cursor = start.clone();
        double travelled = 0;
        for (int step = 0; step < 256 && travelled < range; step++) {
            var hit =
                    run.world.rayTraceBlocks(
                            cursor, direction, range - travelled, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) return hit;
            if (!shootThrough(hit.getHitBlock())) return hit;
            var b = hit.getHitBlock();
            var p = hit.getHitPosition();
            double exit = Double.POSITIVE_INFINITY;
            double[] pos = {p.getX(), p.getY(), p.getZ()},
                    dir = {direction.getX(), direction.getY(), direction.getZ()},
                    lo = {b.getX(), b.getY(), b.getZ()};
            for (int axis = 0; axis < 3; axis++)
                if (Math.abs(dir[axis]) > 1e-9) {
                    double t = ((dir[axis] > 0 ? lo[axis] + 1 : lo[axis]) - pos[axis]) / dir[axis];
                    if (t >= 0) exit = Math.min(exit, t);
                }
            if (!Double.isFinite(exit)) return hit;
            var next = p.clone().add(direction.clone().multiply(exit + .001));
            travelled = start.toVector().distance(next);
            cursor = next.toLocation(run.world);
        }
        return travelled >= range
                ? null
                : run.world.rayTraceBlocks(
                        cursor, direction, range - travelled, FluidCollisionMode.NEVER, true);
    }

    public String context(Player player) {
        for (var d : definitions.values())
            if (player.getLocation().distanceSquared(MapWorlds.location(run.world, d.point()))
                    <= 12.25) {
                var state = states.get(d.id());
                if (player.getUniqueId().equals(state.repairer()))
                    return "Rebuilding window " + Math.round(state.repairProgress() * 100) + "%";
                if (attacking.getOrDefault(d.id(), false) && d.denyRepairUnderAttack())
                    return "Window under attack";
                return state.missing() < 0 ? "Window intact" : "Hold sneak • rebuild window";
            }
        return "";
    }

    public WindowState state(String id) {
        return states.get(id);
    }

    public WindowDefinition definition(String id) {
        return definitions.get(id);
    }

    public void clear() {
        for (var d : definitions.values()) for (var b : d.boards()) clearCracks(d, b);
        assigned.clear();
        arrivals.clear();
    }

    private void place(WindowDefinition d, WindowDefinition.Board b) {
        var data = MapWorlds.material(b.material()).createBlockData();
        if (data instanceof TrapDoor door) {
            door.setOpen(true);
            door.setFacing(org.bukkit.block.BlockFace.valueOf(d.orientation().name()));
        }
        block(b).setBlockData(data, false);
    }

    private void effects(WindowDefinition.Board b, boolean rebuild) {
        var at = block(b).getLocation().add(.5, .5, .5);
        run.world.playSound(
                at,
                rebuild ? Sound.BLOCK_WOOD_PLACE : Sound.BLOCK_WOOD_BREAK,
                .45f,
                rebuild ? 1.2f : .8f);
        run.world.spawnParticle(
                Particle.BLOCK,
                at,
                8,
                .2,
                .2,
                .2,
                0,
                MapWorlds.material(b.material()).createBlockData());
    }

    private void clearCracks(WindowDefinition d, WindowDefinition.Board b) {
        for (var p : run.world.getPlayers())
            p.sendBlockDamage(block(b).getLocation(), 0, source(d, b));
    }

    private int source(WindowDefinition d, WindowDefinition.Board b) {
        return Objects.hash(d.id(), b.x(), b.y(), b.z());
    }

    private Block block(WindowDefinition.Board b) {
        return run.world.getBlockAt(b.x(), b.y(), b.z());
    }

    private static String key(Block b) {
        return b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private static MapTemplate.Point point(Location p) {
        return new MapTemplate.Point(p.getX(), p.getY(), p.getZ());
    }
}
