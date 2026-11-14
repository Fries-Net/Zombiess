package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;

import org.bukkit.World;

import java.util.*;

/** Bounded two-block walking checks with virtual opened doors; never changes the draft terrain. */
public final class MapRoutes {
    private record Cell(int x, int y, int z) {
        static Cell at(MapTemplate.Point p) {
            return new Cell(
                    (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
        }

        MapTemplate.Point point() {
            return new MapTemplate.Point(x, y, z);
        }
    }

    public static void validate(World world, MapTemplate map) {
        Set<Cell> doors = new HashSet<>();
        for (var gate : map.gates())
            MapWorlds.each(
                    world, gate.box(), b -> doors.add(new Cell(b.getX(), b.getY(), b.getZ())));
        var goals = new HashSet<Cell>();
        for (var object : map.objects()) {
            if (object.kind() == MapObject.Kind.PLAYER_SPAWN) goals.add(Cell.at(object.point()));
            if (map.windows().isEmpty() && object.kind() == MapObject.Kind.ZOMBIE_REGION)
                goals.addAll(safeCells(world, object.region()));
        }
        if (map.windows().isEmpty()) map.zombieSpawns().forEach(s -> goals.add(Cell.at(s.point())));
        else map.windows().forEach(w -> goals.add(Cell.at(w.entry())));
        var budget = new int[] {65536};
        walk(
                world,
                map.bounds(),
                doors,
                Cell.at(map.playerSpawn()),
                goals,
                budget,
                "Player spawns or zombie entries cannot reach the player area with doors open");
        for (var window : map.windows()) {
            walk(
                    world,
                    map.bounds(),
                    Set.of(),
                    Cell.at(window.approach()),
                    new HashSet<>(safeCells(world, window.spawnRegion())),
                    budget,
                    "Blocked exterior spawn route at window " + window.id());
            var boards = new HashSet<Cell>();
            window.boards().forEach(b -> boards.add(new Cell(b.x(), b.y(), b.z())));
            walk(
                    world,
                    map.bounds(),
                    boards,
                    Cell.at(window.approach()),
                    new HashSet<>(Set.of(Cell.at(window.entry()))),
                    budget,
                    "Blocked passage through window " + window.id());
        }
    }

    private static List<Cell> safeCells(World world, MapTemplate.Box region) {
        var cells = new ArrayList<Cell>();
        MapWorlds.each(
                world,
                region,
                b -> {
                    var cell = new Cell(b.getX(), b.getY(), b.getZ());
                    if (safe(world, Set.of(), cell)) cells.add(cell);
                });
        return cells;
    }

    private static void walk(
            World world,
            MapTemplate.Box bounds,
            Set<Cell> removed,
            Cell start,
            Set<Cell> goals,
            int[] budget,
            String failure) {
        goals.remove(start);
        if (goals.isEmpty()) return;
        var seen = new HashSet<Cell>();
        var queue = new ArrayDeque<Cell>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            if (--budget[0] < 0)
                throw new IllegalArgumentException(
                        "Route validation exceeds 65536 walk cells; reduce map bounds or split the"
                            + " map");
            var cell = queue.removeFirst();
            for (var delta : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy : new int[] {0, 1, -1}) {
                    var next = new Cell(cell.x + delta[0], cell.y + dy, cell.z + delta[1]);
                    if (!bounds.contains(next.point())
                            || seen.contains(next)
                            || !safe(world, removed, next)) continue;
                    // Stepping up needs room above the old position; dropping needs room above the
                    // new.
                    if (dy > 0 && !passable(world, removed, new Cell(cell.x, cell.y + 2, cell.z)))
                        continue;
                    if (dy < 0 && !passable(world, removed, new Cell(next.x, next.y + 2, next.z)))
                        continue;
                    seen.add(next);
                    goals.remove(next);
                    if (goals.isEmpty()) return;
                    queue.addLast(next);
                }
            }
        }
        var missing = goals.iterator().next();
        throw new IllegalArgumentException(
                failure + " near " + missing.x + "," + missing.y + "," + missing.z);
    }

    private static boolean safe(World world, Set<Cell> removed, Cell c) {
        return passable(world, removed, c)
                && passable(world, removed, new Cell(c.x, c.y + 1, c.z))
                && !removed.contains(new Cell(c.x, c.y - 1, c.z))
                && world.getBlockAt(c.x, c.y - 1, c.z).getType().isSolid();
    }

    private static boolean passable(World world, Set<Cell> removed, Cell c) {
        return removed.contains(c) || world.getBlockAt(c.x, c.y, c.z).isPassable();
    }

    private MapRoutes() {}
}
