package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.util.*;

public record WindowDefinition(
        String id,
        MapTemplate.Point point,
        Orientation orientation,
        List<Board> boards,
        MapTemplate.Box spawnRegion,
        MapTemplate.Point approach,
        MapTemplate.Point entry,
        int breakTicks,
        int repairTicks,
        int repairGold,
        int roundGoldCap,
        boolean denyRepairUnderAttack,
        Set<String> requiredRooms,
        NamespacedId asset) {
    public enum Orientation {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    public record Board(int x, int y, int z, String material) {
        public MapTemplate.Point point() {
            return new MapTemplate.Point(x, y, z);
        }
    }

    public WindowDefinition {
        boards = List.copyOf(boards);
        requiredRooms = Set.copyOf(requiredRooms);
        if (!id.matches("[a-z0-9_-]{1,32}")
                || boards.isEmpty()
                || boards.size() > 64
                || new HashSet<>(boards.stream().map(Board::point).toList()).size() != boards.size()
                || spawnRegion.volume() > 256)
            throw new IllegalArgumentException("Invalid window geometry");
        boolean ns = orientation == Orientation.NORTH || orientation == Orientation.SOUTH;
        int plane = ns ? boards.getFirst().z() : boards.getFirst().x();
        if (boards.stream().anyMatch(b -> (ns ? b.z() : b.x()) != plane))
            throw new IllegalArgumentException("Window boards must share an oriented plane");
        double outside = ns ? approach.z() : approach.x(), inside = ns ? entry.z() : entry.x();
        boolean negative = orientation == Orientation.NORTH || orientation == Orientation.WEST;
        if (negative
                ? !(outside < plane && inside > plane + 1)
                : !(outside > plane + 1 && inside < plane))
            throw new IllegalArgumentException("Window approach and entry must straddle boards");
        if (negative
                ? (ns ? spawnRegion.z2() : spawnRegion.x2()) >= plane
                : (ns ? spawnRegion.z1() : spawnRegion.x1()) <= plane + 1)
            throw new IllegalArgumentException("Spawn region must be behind window");
        int lateral = (int) Math.floor(ns ? approach.x() : approach.z());
        if (boards.stream().noneMatch(b -> (ns ? b.x() : b.z()) == lateral))
            throw new IllegalArgumentException("No board at window approach");
    }

    public static WindowDefinition load(ContentYaml n) {
        n.keys(
                "id",
                "point",
                "orientation",
                "boards",
                "spawn-region",
                "approach",
                "entry",
                "break-ticks",
                "repair-ticks",
                "repair-gold",
                "round-gold-cap",
                "deny-repair-under-attack",
                "required-rooms",
                "asset");
        var boards =
                n.nodes("boards").stream()
                        .map(
                                b -> {
                                    b.keys("x", "y", "z", "material");
                                    return new Board(
                                            b.integer("x", -128, 128),
                                            b.integer("y", 0, 255),
                                            b.integer("z", -128, 128),
                                            b.text("material"));
                                })
                        .toList();
        return new WindowDefinition(
                n.text("id"),
                MapTemplate.point(n.node("point")),
                Orientation.valueOf(n.text("orientation")),
                boards,
                MapTemplate.box(n.node("spawn-region")),
                MapTemplate.point(n.node("approach")),
                MapTemplate.point(n.node("entry")),
                n.integer("break-ticks", 1, 1200),
                n.integer("repair-ticks", 1, 1200),
                n.integer("repair-gold", 0, 1000),
                n.integer("round-gold-cap", 0, 100000),
                n.bool("deny-repair-under-attack"),
                Set.copyOf(n.strings("required-rooms")),
                new NamespacedId(n.text("asset")));
    }
}
