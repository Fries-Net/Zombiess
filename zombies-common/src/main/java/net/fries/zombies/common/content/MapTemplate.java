package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Trusted bounded block blueprint. Its digest pins geometry and interactions together for a match.
 */
public record MapTemplate(
        NamespacedId id,
        String version,
        String name,
        Point playerSpawn,
        Box bounds,
        List<Fill> blocks,
        List<Spawn> zombieSpawns,
        List<Gate> gates,
        List<Barricade> barricades,
        List<Shop> shops) {
    public MapTemplate {
        blocks = List.copyOf(blocks);
        zombieSpawns = List.copyOf(zombieSpawns);
        gates = List.copyOf(gates);
        barricades = List.copyOf(barricades);
        shops = List.copyOf(shops);
    }

    public record Point(double x, double y, double z) {}

    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public Box {
            if (x2 < x1 || y2 < y1 || z2 < z1)
                throw new IllegalArgumentException("Reversed map bounds");
        }

        public long volume() {
            return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        }

        public boolean contains(Point p) {
            return p.x >= x1
                    && p.x < x2 + 1
                    && p.y >= y1
                    && p.y < y2 + 1
                    && p.z >= z1
                    && p.z < z2 + 1;
        }
    }

    public record Fill(Box box, String material) {}

    public record Spawn(Point point, String gate) {}

    public record Gate(String id, String name, Point point, Box box, int price) {}

    public record Barricade(String id, Point point, Box box, String material) {}

    public record Shop(Point point, NamespacedId weapon) {}

    public static MapTemplate load(Path path, Gameplay gameplay) throws IOException {
        byte[] source = ContentYaml.readBytes(path);
        var r = ContentYaml.parse(source);
        r.keys(
                "schema-version",
                "id",
                "version",
                "name",
                "player-spawn",
                "bounds",
                "blocks",
                "zombie-spawns",
                "gates",
                "barricades",
                "shops");
        r.integer("schema-version", 1, 1);
        String version = r.text("version");
        if (!version.matches("[a-zA-Z0-9_.-]{1,24}"))
            throw new IllegalArgumentException("Unsafe map version");
        try {
            version +=
                    "-"
                            + HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(source))
                                    .substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        var bounds = box(r.node("bounds"));
        if (bounds.volume() > 200000)
            throw new IllegalArgumentException("Map exceeds bounded template size");
        var blocks =
                r.nodes("blocks").stream()
                        .map(
                                n -> {
                                    n.keys("box", "material");
                                    return new Fill(box(n.node("box")), n.text("material"));
                                })
                        .toList();
        if (blocks.isEmpty()
                || blocks.size() > 256
                || blocks.stream().mapToLong(f -> f.box.volume()).sum() > 200000)
            throw new IllegalArgumentException("Invalid block budget");
        var gates =
                r.nodes("gates").stream()
                        .map(
                                n -> {
                                    n.keys("id", "name", "point", "box", "price");
                                    return new Gate(
                                            n.text("id"),
                                            n.text("name"),
                                            point(n.node("point")),
                                            box(n.node("box")),
                                            n.integer("price", 1, 100000));
                                })
                        .toList();
        var barricades =
                r.nodes("barricades").stream()
                        .map(
                                n -> {
                                    n.keys("id", "point", "box", "material");
                                    return new Barricade(
                                            n.text("id"),
                                            point(n.node("point")),
                                            box(n.node("box")),
                                            n.text("material"));
                                })
                        .toList();
        var shops =
                r.nodes("shops").stream()
                        .map(
                                n -> {
                                    n.keys("point", "weapon");
                                    var id = new NamespacedId(n.text("weapon"));
                                    if (!gameplay.weapons().containsKey(id))
                                        throw new IllegalArgumentException("Unknown shop weapon");
                                    return new Shop(point(n.node("point")), id);
                                })
                        .toList();
        Set<String> gateIds = new HashSet<>();
        gateIds.add("none");
        for (var g : gates)
            if (!g.id.matches("[a-z0-9_-]{1,32}") || !gateIds.add(g.id) || g.box.volume() > 256)
                throw new IllegalArgumentException("Invalid gate");
        Set<String> barricadeIds = new HashSet<>();
        for (var b : barricades)
            if (!barricadeIds.add(b.id) || b.box.volume() > 32)
                throw new IllegalArgumentException("Invalid barricade");
        var spawns =
                r.nodes("zombie-spawns").stream()
                        .map(
                                n -> {
                                    n.keys("point", "gate");
                                    var gate = n.text("gate");
                                    if (!gateIds.contains(gate))
                                        throw new IllegalArgumentException("Unknown spawn gate");
                                    return new Spawn(point(n.node("point")), gate);
                                })
                        .toList();
        if (spawns.isEmpty()
                || spawns.size() > 64
                || spawns.stream().noneMatch(s -> s.gate.equals("none")))
            throw new IllegalArgumentException("Missing initial spawns");
        var map =
                new MapTemplate(
                        new NamespacedId(r.text("id")),
                        version,
                        r.text("name"),
                        point(r.node("player-spawn")),
                        bounds,
                        blocks,
                        spawns,
                        gates,
                        barricades,
                        shops);
        map.requireInside(map.playerSpawn);
        for (var s : spawns) map.requireInside(s.point);
        for (var s : shops) map.requireInside(s.point);
        for (var f : blocks) map.requireInside(f.box);
        for (var g : gates) {
            map.requireInside(g.point);
            map.requireInside(g.box);
        }
        for (var b : barricades) {
            map.requireInside(b.point);
            map.requireInside(b.box);
        }
        for (int i = 0; i < gates.size(); i++)
            for (int j = i + 1; j < gates.size(); j++) {
                var a = gates.get(i).box;
                var b = gates.get(j).box;
                if (a.x1 <= b.x2
                        && a.x2 >= b.x1
                        && a.y1 <= b.y2
                        && a.y2 >= b.y1
                        && a.z1 <= b.z2
                        && a.z2 >= b.z1) throw new IllegalArgumentException("Overlapping gates");
            }
        return map;
    }

    private void requireInside(Point p) {
        if (!bounds.contains(p)) throw new IllegalArgumentException("Map point outside bounds");
    }

    private void requireInside(Box b) {
        requireInside(new Point(b.x1, b.y1, b.z1));
        requireInside(new Point(b.x2, b.y2, b.z2));
    }

    private static Point point(ContentYaml n) {
        n.keys("x", "y", "z");
        return new Point(n.number("x", -128, 128), n.number("y", 1, 255), n.number("z", -128, 128));
    }

    private static Box box(ContentYaml n) {
        n.keys("x1", "y1", "z1", "x2", "y2", "z2");
        return new Box(
                n.integer("x1", -128, 128),
                n.integer("y1", 0, 255),
                n.integer("z1", -128, 128),
                n.integer("x2", -128, 128),
                n.integer("y2", 0, 255),
                n.integer("z2", -128, 128));
    }
}
