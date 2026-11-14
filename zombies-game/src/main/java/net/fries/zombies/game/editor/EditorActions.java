package net.fries.zombies.game.editor;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

/** Metadata operations shared by commands, menus and the in-world tools. */
@SuppressWarnings("unchecked")
public final class EditorActions {
    private final EditorService service;

    public EditorActions(EditorService service) {
        this.service = service;
    }

    public void execute(Player p, EditorSession s, String[] args) {
        switch (args[0]) {
            case "add" -> {
                if (args.length != 3)
                    throw new IllegalArgumentException("add <kind|window|door> <id>");
                add(p, s, args[1], args[2]);
            }
            case "select" -> {
                if (args.length != 3
                        || !Set.of("objects", "windows", "gates", "barricades").contains(args[1]))
                    throw new IllegalArgumentException(
                            "select <objects|windows|gates|barricades> <id>");
                s.selectedList = args[1];
                s.selectedId = args[2];
                s.selected();
                service.details(p);
            }
            case "set" -> {
                if (args.length < 3) throw new IllegalArgumentException("set <property> <value>");
                set(s, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                service.details(p);
            }
            case "point" -> point(s, args.length > 1 ? args[1] : "point", p.getLocation());
            case "region" -> region(s, args.length > 1 ? args[1] : "region");
            case "bounds", "playable" -> {
                var box = selection(s);
                s.draft.change(
                        doc ->
                                doc.put(
                                        args[0].equals("bounds") ? "bounds" : "playable-bounds",
                                        box));
            }
            case "spawn" -> s.draft.change(doc -> doc.put("player-spawn", point(p.getLocation())));
            case "room" -> {
                if ((args.length != 2 && args.length != 3)
                        || !args[1].matches("[a-z0-9_-]{1,32}")
                        || args.length == 3 && !Set.of("locked", "unlocked").contains(args[2]))
                    throw new IllegalArgumentException("room <id> [locked|unlocked]");
                boolean unlocked = args.length == 2 || args[2].equals("unlocked");
                s.draft.change(
                        doc -> {
                            var rooms = new ArrayList<Object>((List<?>) doc.get("initial-rooms"));
                            if (unlocked && !rooms.contains(args[1])) rooms.add(args[1]);
                            if (!unlocked) rooms.remove(args[1]);
                            doc.put("initial-rooms", rooms);
                        });
                EditorService.message(
                        p,
                        "Room "
                                + args[1]
                                + " starts "
                                + (unlocked ? "unlocked" : "locked; a door must unlock it"));
            }
            case "delete" -> {
                s.selected();
                s.draft.change(
                        doc ->
                                s.draft
                                        .nodes(s.selectedList)
                                        .removeIf(n -> s.selectedId.equals(n.get("id"))));
                s.selectedId = "";
                service.menu(p);
            }
            case "tool" -> {
                if (args.length != 2
                        || !Set.of("select", "point", "region", "board").contains(args[1]))
                    throw new IllegalArgumentException("tool <select|point|region|board>");
                s.tool = args[1];
                p.getInventory()
                        .setHeldItemSlot(
                                List.of("select", "point", "region", "board").indexOf(s.tool));
            }
            default ->
                    throw new IllegalArgumentException(
                            "Editor: menu, add, select, set, point, region, bounds, playable,"
                                    + " spawn, room, undo, redo, validate, save, publish, close");
        }
    }

    public void add(Player p, EditorSession s, String type, String id) {
        if (!id.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid object id");
        String list =
                type.equalsIgnoreCase("window")
                        ? "windows"
                        : type.equalsIgnoreCase("door") ? "gates" : "objects";
        if (s.draft.nodes(list).stream().anyMatch(n -> id.equals(n.get("id"))))
            throw new IllegalArgumentException("Object id exists");
        var n = new LinkedHashMap<String, Object>();
        n.put("id", id);
        n.put("point", point(p.getLocation()));
        if (list.equals("gates")) {
            n.put("name", id);
            n.put("box", selection(s));
            n.put("price", 750);
            n.put("unlocks-room", id);
        } else if (list.equals("windows")) {
            var b = MapTemplate.box(new ContentYaml(selection(s)));
            if (b.volume() > 64)
                throw new IllegalArgumentException("Select at most 64 board blocks");
            boolean ns = b.z1() == b.z2();
            if (!ns && b.x1() != b.x2())
                throw new IllegalArgumentException("Select a flat board plane");
            var boards = new ArrayList<Map<String, Object>>();
            for (int y = b.y1(); y <= b.y2(); y++)
                for (int x = b.x1(); x <= b.x2(); x++)
                    for (int z = b.z1(); z <= b.z2(); z++) {
                        var block = s.world.getBlockAt(x, y, z);
                        if (!block.getType().isAir())
                            boards.add(
                                    new LinkedHashMap<>(
                                            Map.of(
                                                    "x",
                                                    x,
                                                    "y",
                                                    y,
                                                    "z",
                                                    z,
                                                    "material",
                                                    block.getType().name())));
                    }
            double x = b.x1() + .5, z = b.z1() + .5, y = b.y1();
            n.put("orientation", ns ? "NORTH" : "WEST");
            n.put("boards", boards);
            n.put("approach", MapDraft.point(ns ? x : x - 1.8, y, ns ? z - 1.8 : z));
            n.put("entry", MapDraft.point(ns ? x : x + 1.8, y, ns ? z + 1.8 : z));
            n.put(
                    "spawn-region",
                    MapDraft.box(
                            b.x1() - (ns ? 0 : 3),
                            b.y1(),
                            b.z1() - (ns ? 3 : 0),
                            b.x1() - (ns ? 0 : 3),
                            b.y1(),
                            b.z1() - (ns ? 3 : 0)));
            n.put("break-ticks", 40);
            n.put("repair-ticks", 40);
            n.put("repair-gold", 10);
            n.put("round-gold-cap", 60);
            n.put("deny-repair-under-attack", true);
            n.put("required-rooms", new ArrayList<>(List.of("spawn")));
            n.put("asset", "friesnet:window/wooden");
        } else {
            var kind = MapObject.Kind.valueOf(type.toUpperCase(Locale.ROOT));
            n.put("kind", kind.name());
            n.put(
                    "region",
                    s.first != null && s.second != null ? selection(s) : single(p.getLocation()));
            n.put("rooms", new ArrayList<>(List.of("spawn")));
            n.put("asset", "friesnet:object/" + type.toLowerCase(Locale.ROOT));
            var properties = new LinkedHashMap<String, String>();
            switch (kind) {
                case WALL_WEAPON, AMMO -> {
                    var weapon =
                            service.catalog()
                                    .gameplay()
                                    .weapons()
                                    .get(service.catalog().gameplay().starter());
                    properties.put("weapon", weapon.id().value());
                    properties.put("price", Integer.toString(weapon.price()));
                    properties.put("ammo-price", Integer.toString(weapon.ammoPrice()));
                }
                case PERK -> {
                    properties.put("perk", "friesnet:perk/extra_health");
                    properties.put(
                            "price",
                            Integer.toString(
                                    service.catalog()
                                            .perks()
                                            .require("friesnet:perk/extra_health")
                                            .price()));
                }
                case MYSTERY_BOX -> {
                    properties.put("box", "friesnet:box/default");
                    properties.put(
                            "price",
                            Integer.toString(
                                    service.catalog()
                                            .mysteryPools()
                                            .require("friesnet:box/default")
                                            .price()));
                }
                case HOLOGRAM -> {
                    properties.put("text", "Round {round} • {remaining} zombies");
                    properties.put("alignment", "CENTER");
                    properties.put("visibility", "ALL");
                }
                case CUSTOM -> {
                    properties.put("text", "Activate objective");
                    properties.put("objective", id);
                    properties.put("price", "0");
                }
                case RANGE_TARGET -> properties.put("health", "100");
                default -> {}
            }
            n.put("properties", properties);
        }
        s.draft.change(doc -> s.draft.nodes(list).add(n));
        s.selectedList = list;
        s.selectedId = id;
        service.details(p);
    }

    public void set(EditorSession s, String field, String value) {
        var n = s.selected();
        Object converted;
        if (s.selectedList.equals("objects")) {
            if (field.equals("asset")) {
                new NamespacedId(value);
                converted = value;
            } else if (field.equals("rooms")) converted = csv(value);
            else {
                s.draft.change(
                        doc -> {
                            var props =
                                    new LinkedHashMap<String, Object>(
                                            (Map<String, Object>) s.selected().get("properties"));
                            props.put(field, value);
                            s.selected().put("properties", props);
                        });
                return;
            }
        } else {
            if (!n.containsKey(field)
                    || Set.of("id", "point", "box", "boards", "approach", "entry", "spawn-region")
                            .contains(field))
                throw new IllegalArgumentException(
                        "Use a point, region or board tool for geometry");
            Object old = n.get(field);
            if (old instanceof Number) converted = Integer.parseInt(value);
            else if (old instanceof Boolean) {
                if (!Set.of("true", "false").contains(value))
                    throw new IllegalArgumentException("Expected true or false");
                converted = Boolean.parseBoolean(value);
            } else if (old instanceof List<?>) converted = csv(value);
            else converted = field.equals("orientation") ? value.toUpperCase(Locale.ROOT) : value;
            if (field.equals("asset")) new NamespacedId(value);
        }
        Object result = converted;
        s.draft.change(doc -> s.selected().put(field, result));
    }

    public void point(EditorSession s, String field, Location location) {
        if (!Set.of("point", "approach", "entry").contains(field)
                || !s.selected().containsKey(field))
            throw new IllegalArgumentException("This object has no point field " + field);
        s.draft.change(doc -> s.selected().put(field, point(location)));
    }

    public void region(EditorSession s, String field) {
        if (!Set.of("region", "box", "spawn-region").contains(field)
                || !s.selected().containsKey(field))
            throw new IllegalArgumentException("This object has no region field " + field);
        var box = selection(s);
        s.draft.change(doc -> s.selected().put(field, box));
    }

    public void board(EditorSession s, Block b) {
        if (!s.selectedList.equals("windows") || b.getType().isAir())
            throw new IllegalArgumentException("Select a window and click an existing board block");
        s.draft.change(
                doc -> {
                    var boards = (List<Map<String, Object>>) s.selected().get("boards");
                    boolean removed =
                            boards.removeIf(
                                    n ->
                                            ((Number) n.get("x")).intValue() == b.getX()
                                                    && ((Number) n.get("y")).intValue() == b.getY()
                                                    && ((Number) n.get("z")).intValue()
                                                            == b.getZ());
                    if (!removed)
                        boards.add(
                                new LinkedHashMap<>(
                                        Map.of(
                                                "x",
                                                b.getX(),
                                                "y",
                                                b.getY(),
                                                "z",
                                                b.getZ(),
                                                "material",
                                                b.getType().name())));
                });
    }

    public void selectNearest(Player p, EditorSession s, Location at) {
        double distance = 16;
        String list = "", id = "";
        for (var name : List.of("objects", "windows", "gates", "barricades"))
            for (var n : s.draft.nodes(name)) {
                var point = MapTemplate.point(new ContentYaml((Map<?, ?>) n.get("point")));
                double d = MapWorldDistance(at, point);
                if (d < distance) {
                    distance = d;
                    list = name;
                    id = (String) n.get("id");
                }
            }
        if (id.isEmpty())
            throw new IllegalArgumentException("No object within four blocks of selection");
        s.selectedList = list;
        s.selectedId = id;
        service.details(p);
    }

    private static double MapWorldDistance(Location at, MapTemplate.Point p) {
        return Math.pow(at.getX() - p.x(), 2)
                + Math.pow(at.getY() - p.y(), 2)
                + Math.pow(at.getZ() - p.z(), 2);
    }

    private static List<String> csv(String value) {
        return value.equals("none")
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(value.split(",")));
    }

    public static Map<String, Object> point(Location at) {
        var value = MapDraft.point(at.getX(), at.getY(), at.getZ());
        MapTemplate.point(new ContentYaml(value));
        return value;
    }

    private static Map<String, Object> single(Location p) {
        return MapDraft.box(
                p.getBlockX(),
                p.getBlockY(),
                p.getBlockZ(),
                p.getBlockX(),
                p.getBlockY(),
                p.getBlockZ());
    }

    public static Map<String, Object> selection(EditorSession s) {
        if (s.first == null || s.second == null)
            throw new IllegalArgumentException(
                    "Use the region wand: left-click first corner, right-click second corner");
        return MapDraft.box(
                s.first.getBlockX(),
                s.first.getBlockY(),
                s.first.getBlockZ(),
                s.second.getBlockX(),
                s.second.getBlockY(),
                s.second.getBlockZ());
    }
}
