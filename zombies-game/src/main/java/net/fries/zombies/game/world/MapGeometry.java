package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;

import org.bukkit.World;

import java.util.*;

/** Publication and admission share the same physical validation. No terrain is changed here. */
public final class MapGeometry {
    public static void validate(World world, MapTemplate map) {
        safe(world, map.playerSpawn());
        for (var spawn : map.zombieSpawns()) safe(world, spawn.point());
        for (var object : map.objects()) {
            if (object.kind() == MapObject.Kind.PLAYER_SPAWN
                    || object.kind() == MapObject.Kind.SPECTATOR_SPAWN) {
                if (!map.playableBounds().contains(object.point()))
                    throw new IllegalArgumentException("Player spawn outside playable area");
                safe(world, object.point());
            }
            if (object.kind() == MapObject.Kind.ZOMBIE_REGION
                    || object.kind() == MapObject.Kind.BOSS_SPAWN) region(world, object.region());
        }
        for (var gate : map.gates()) {
            int[] solid = {0};
            MapWorlds.each(
                    world,
                    gate.box(),
                    b -> {
                        if (!b.getType().isAir()) solid[0]++;
                    });
            if (solid[0] == 0)
                throw new IllegalArgumentException("Door contains no blocks: " + gate.id());
        }
        for (var barricade : map.barricades())
            MapWorlds.each(
                    world,
                    barricade.box(),
                    b -> {
                        if (b.getType() != MapWorlds.material(barricade.material())
                                || b.getType().isAir())
                            throw new IllegalArgumentException("Invalid legacy barricade geometry");
                    });
        for (var window : map.windows()) {
            for (var board : window.boards())
                if (world.getBlockAt(board.x(), board.y(), board.z()).getType()
                        != MapWorlds.material(board.material()))
                    throw new IllegalArgumentException(
                            "Window board material mismatch: " + window.id());
            safe(world, window.approach());
            safe(world, window.entry());
            region(world, window.spawnRegion());
            var first = window.boards().getFirst();
            boolean ns =
                    window.orientation() == WindowDefinition.Orientation.NORTH
                            || window.orientation() == WindowDefinition.Orientation.SOUTH;
            int x = ns ? (int) Math.floor(window.approach().x()) : first.x(),
                    z = ns ? first.z() : (int) Math.floor(window.approach().z()),
                    y = (int) Math.floor(window.entry().y());
            for (int dy = 0; dy < 2; dy++) {
                int level = y + dy;
                if (!world.getBlockAt(x, level, z).isPassable()
                        && window.boards().stream()
                                .noneMatch(b -> b.x() == x && b.y() == level && b.z() == z))
                    throw new IllegalArgumentException(
                            "Window entry is blocked by unowned geometry: " + window.id());
            }
        }
    }

    private static void safe(World world, MapTemplate.Point p) {
        var at = MapWorlds.location(world, p);
        if (!at.getBlock().isPassable()
                || !at.clone().add(0, 1, 0).getBlock().isPassable()
                || !at.clone().subtract(0, 1, 0).getBlock().getType().isSolid())
            throw new IllegalArgumentException("Unsafe spawn or window navigation point");
    }

    private static void region(World world, MapTemplate.Box box) {
        boolean[] safe = {false};
        MapWorlds.each(
                world,
                box,
                b -> {
                    if (b.isPassable()
                            && b.getRelative(0, 1, 0).isPassable()
                            && b.getRelative(0, -1, 0).getType().isSolid()) safe[0] = true;
                });
        if (!safe[0]) throw new IllegalArgumentException("Region has no safe spawn");
    }

    private MapGeometry() {}
}
