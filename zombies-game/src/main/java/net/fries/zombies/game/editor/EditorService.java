package net.fries.zombies.game.editor;

import net.fries.zombies.common.content.*;
import net.fries.zombies.game.*;
import net.fries.zombies.game.world.*;
import net.fries.zombies.persistence.*;
import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.entity.Player;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Draft admission, durable save/publication and isolated world leases. */
public final class EditorService {
    private final ZombiesPlugin plugin;
    private final MatchCoordinator matches;
    private final MapWorlds worlds;
    private final DurableFiles disk;
    private final Path data;
    private final Map<UUID, EditorSession> sessions = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();
    private final Set<String> locked = new HashSet<>();
    private final EditorActions actions;
    private final EditorMenus menus;
    private long tick;

    public EditorService(
            ZombiesPlugin plugin, MatchCoordinator matches, MapWorlds worlds, DurableFiles disk) {
        this.plugin = plugin;
        this.matches = matches;
        this.worlds = worlds;
        this.disk = disk;
        data = plugin.getDataFolder().toPath();
        actions = new EditorActions(this);
        menus = new EditorMenus(plugin, this, actions);
        plugin.getServer().getPluginManager().registerEvents(menus, plugin);
    }

    public EditorSession session(UUID id) {
        return sessions.get(id);
    }

    public boolean contains(UUID id) {
        return sessions.containsKey(id) || opening.contains(id);
    }

    public ContentCatalog catalog() {
        return matches.catalog();
    }

    public void execute(Player player, String[] args) {
        try {
            if (args.length == 0 || args[0].equals("menu")) {
                menus.open(player);
                return;
            }
            if (args[0].equals("drafts")) {
                drafts(player);
                return;
            }
            if (Set.of("open", "enter").contains(args[0]) && args.length == 2) {
                open(player, args[1]);
                return;
            }
            if (args[0].equals("create") && args.length == 2) {
                duplicate(player, "training", args[1]);
                return;
            }
            if (args[0].equals("duplicate") && args.length == 3) {
                duplicate(player, args[1], args[2]);
                return;
            }
            if (args[0].equals("import") && (args.length == 3 || args.length == 4)) {
                importWorld(player, args[1], args[2], args.length == 4 ? args[3] : "training");
                return;
            }
            if (Set.of("close", "exit").contains(args[0])) {
                if (args.length == 2 && args[1].equals("confirm")) close(player);
                else if (session(player.getUniqueId()) != null) menus.confirmExit(player);
                return;
            }
            if (args[0].equals("versions") && args.length == 2) {
                versions(player, args[1]);
                return;
            }
            if (args[0].equals("checkout") && args.length == 3) {
                checkout(player, args[1], args[2]);
                return;
            }
            var s = require(player);
            if (s.busy) throw new IllegalStateException("Draft is saving; wait for completion");
            switch (args[0]) {
                case "delete" -> menus.confirmDelete(player);
                case "preview" -> {
                    player.closeInventory();
                    player.setGameMode(
                            player.getGameMode() == GameMode.SPECTATOR
                                    ? GameMode.CREATIVE
                                    : GameMode.SPECTATOR);
                    message(
                            player,
                            "Geometry preview toggled. Use editor preview to resume building;"
                                    + " publish and /zombies join "
                                    + s.map
                                    + " to test gameplay.");
                }
                case "wand" -> {
                    if (args.length != 2) throw new IllegalArgumentException("wand <material>");
                    var material = Material.matchMaterial(args[1]);
                    if (material == null || !material.isItem() || material.isAir())
                        throw new IllegalArgumentException("Choose an item material");
                    s.wand = material;
                    menus.tools(player);
                }
                case "save" -> save(player, false);
                case "export" -> {
                    if (args.length != 2)
                        throw new IllegalArgumentException("export <new-world-folder>");
                    export(player, args[1]);
                }
                case "publish" -> save(player, true);
                case "validate" -> {
                    validate(s);
                    message(player, "Draft and world geometry are valid");
                }
                case "undo" -> {
                    s.draft.undo();
                    menus.details(player);
                }
                case "redo" -> {
                    s.draft.redo();
                    menus.details(player);
                }
                default -> actions.execute(player, s, args);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            message(player, error.getMessage());
        }
    }

    public EditorSession require(Player p) {
        var s = sessions.get(p.getUniqueId());
        if (s == null)
            throw new IllegalStateException(
                    "Use /zombies editor open <map>, or import <new-map> <world-folder>"
                            + " <template-map>");
        return s;
    }

    public void duplicate(Player p, String source, String target) {
        checkName(source);
        checkName(target);
        if (contains(p.getUniqueId())
                || catalog().maps().containsKey(target)
                || !locked.add(target))
            throw new IllegalArgumentException("Exit your session and choose a new map name");
        disk.submit(
                        () -> {
                            Path destination = data.resolve("maps/drafts/" + target + ".yml");
                            if (Files.exists(destination)
                                    || Files.exists(data.resolve("maps/" + target + ".yml")))
                                throw new java.io.IOException("Map or draft already exists");
                            Path draft = data.resolve("maps/drafts/" + source + ".yml");
                            var copy =
                                    new MapDraft(
                                            ContentYaml.readBytes(
                                                    Files.exists(draft)
                                                            ? draft
                                                            : data.resolve(
                                                                    "maps/" + source + ".yml")));
                            copy.change(
                                    doc -> {
                                        doc.put("id", "friesnet:map/" + target);
                                        doc.put("name", target);
                                        doc.put("version", "draft");
                                    });
                            DurableFiles.replace(destination, copy.bytes());
                            return null;
                        })
                .whenComplete(
                        (v, error) ->
                                plugin.onServer(
                                        () -> {
                                            locked.remove(target);
                                            if (error == null && p.isOnline()) open(p, target);
                                            else if (error != null)
                                                message(p, "Create failed: " + cause(error));
                                        }));
    }

    public void drafts(Player p) {
        disk.submit(
                        () -> {
                            Path directory = data.resolve("maps/drafts");
                            if (!Files.isDirectory(directory)) return List.<String>of();
                            try (var files = Files.list(directory)) {
                                return files.filter(
                                                f ->
                                                        f.getFileName()
                                                                .toString()
                                                                .matches("[a-z0-9_-]{1,32}\\.yml"))
                                        .limit(512)
                                        .map(f -> f.getFileName().toString().replace(".yml", ""))
                                        .sorted()
                                        .toList();
                            }
                        })
                .whenComplete(
                        (names, error) ->
                                plugin.onServer(
                                        () -> {
                                            if (!p.isOnline()) return;
                                            if (error != null) {
                                                message(
                                                        p,
                                                        "Could not list saved drafts: "
                                                                + cause(error));
                                                return;
                                            }
                                            if (names.isEmpty()) message(p, "No saved drafts yet");
                                            else
                                                for (String name : names)
                                                    p.sendMessage(
                                                            Component.text(
                                                                            "[Enter saved draft] "
                                                                                    + name)
                                                                    .clickEvent(
                                                                            net.kyori.adventure.text
                                                                                    .event
                                                                                    .ClickEvent
                                                                                    .runCommand(
                                                                                            "/zombies"
                                                                                                + " editor"
                                                                                                + " enter"
                                                                                                + " "
                                                                                                    + name)));
                                        }));
    }

    public Collection<String> suggest(Player p, String[] args) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        Collection<String> choices = List.of();
        if (args.length <= 1)
            choices =
                    List.of(
                            "enter",
                            "open",
                            "create",
                            "duplicate",
                            "menu",
                            "save",
                            "validate",
                            "publish",
                            "undo",
                            "redo",
                            "exit",
                            "close",
                            "import",
                            "export",
                            "versions",
                            "drafts",
                            "checkout",
                            "add",
                            "select",
                            "set",
                            "point",
                            "region",
                            "bounds",
                            "playable",
                            "spawn",
                            "room",
                            "delete",
                            "preview",
                            "wand");
        else if (args.length == 2
                && Set.of("enter", "open", "duplicate", "versions", "checkout").contains(args[0]))
            choices = catalog().maps().keySet();
        else if (args.length == 2 && args[0].equals("add")) {
            var kinds =
                    new ArrayList<>(
                            Arrays.stream(MapObject.Kind.values())
                                    .map(k -> k.name().toLowerCase(Locale.ROOT))
                                    .toList());
            kinds.addAll(List.of("window", "door"));
            choices = kinds;
        } else if (args.length == 2 && args[0].equals("select"))
            choices = List.of("objects", "windows", "gates", "barricades");
        else {
            var s = session(p.getUniqueId());
            if (s != null
                    && args.length == 3
                    && args[0].equals("select")
                    && Set.of("objects", "windows", "gates", "barricades").contains(args[1]))
                choices = s.draft.nodes(args[1]).stream().map(n -> n.get("id").toString()).toList();
            else if (s != null && args[0].equals("set") && !s.selectedId.isEmpty()) {
                if (args.length == 2) {
                    var fields = new ArrayList<>(s.selected().keySet());
                    if (s.selected().get("properties") instanceof Map<?, ?> props)
                        props.keySet().forEach(k -> fields.add(k.toString()));
                    choices = fields;
                } else if (args.length == 3 && args[1].equals("weapon"))
                    choices =
                            catalog().gameplay().weapons().keySet().stream()
                                    .map(Object::toString)
                                    .toList();
                else if (args.length == 3 && args[1].equals("orientation"))
                    choices = List.of("north", "south", "east", "west");
            }
        }
        return choices.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    public void open(Player p, String map) {
        checkName(map);
        if (!locked.add(map)) throw new IllegalStateException("This draft has an active author");
        CompletableFuture<Void> captured;
        try {
            captured = matches.captureEditor(p);
        } catch (RuntimeException e) {
            locked.remove(map);
            throw e;
        }
        opening.add(p.getUniqueId());
        var loaded =
                disk.submit(
                        () -> {
                            Path draft = data.resolve("maps/drafts/" + map + ".yml");
                            return new MapDraft(
                                    ContentYaml.readBytes(
                                            Files.exists(draft)
                                                    ? draft
                                                    : data.resolve("maps/" + map + ".yml")));
                        });
        captured.thenCombine(loaded, (v, d) -> d)
                .whenComplete(
                        (draft, error) ->
                                plugin.onServer(
                                        () -> {
                                            if (error != null
                                                    || !p.isOnline()
                                                    || !opening.contains(p.getUniqueId())) {
                                                opening.remove(p.getUniqueId());
                                                locked.remove(map);
                                                matches.editorReturned(p);
                                                message(
                                                        p,
                                                        "Could not open draft; your equipment is"
                                                                + " being restored");
                                                return;
                                            }
                                            var root = ContentYaml.parse(draft.bytes());
                                            worlds.allocate(
                                                            UUID.randomUUID(),
                                                            root.text("world-source"),
                                                            root.text("world-digest"))
                                                    .whenComplete(
                                                            (name, failure) ->
                                                                    plugin.onServer(
                                                                            () -> {
                                                                                if (failure != null
                                                                                        || !p
                                                                                                .isOnline()
                                                                                        || !opening
                                                                                                .remove(
                                                                                                        p
                                                                                                                .getUniqueId())) {
                                                                                    opening.remove(
                                                                                            p
                                                                                                    .getUniqueId());
                                                                                    locked.remove(
                                                                                            map);
                                                                                    if (name
                                                                                            != null)
                                                                                        worlds
                                                                                                .release(
                                                                                                        name);
                                                                                    matches
                                                                                            .editorReturned(
                                                                                                    p);
                                                                                    return;
                                                                                }
                                                                                try {
                                                                                    var spawn =
                                                                                            MapTemplate
                                                                                                    .point(
                                                                                                            root
                                                                                                                    .node(
                                                                                                                            "player-spawn"));
                                                                                    var world =
                                                                                            root.text(
                                                                                                                    "world-source")
                                                                                                            .equals(
                                                                                                                    "blueprint")
                                                                                                    ? worlds
                                                                                                            .build(
                                                                                                                    name,
                                                                                                                    draft
                                                                                                                            .validate(
                                                                                                                                    catalog()
                                                                                                                                            .gameplay()))
                                                                                                    : worlds
                                                                                                            .open(
                                                                                                                    name,
                                                                                                                    spawn);
                                                                                    var s =
                                                                                            new EditorSession(
                                                                                                    p
                                                                                                            .getUniqueId(),
                                                                                                    map,
                                                                                                    name,
                                                                                                    draft,
                                                                                                    world);
                                                                                    sessions.put(
                                                                                            p
                                                                                                    .getUniqueId(),
                                                                                            s);
                                                                                    s.savedAt =
                                                                                            tick;
                                                                                    matches
                                                                                            .editorAdmitted(
                                                                                                    p
                                                                                                            .getUniqueId());
                                                                                    p
                                                                                            .closeInventory();
                                                                                    p.getInventory()
                                                                                            .clear();
                                                                                    p.setGameMode(
                                                                                            GameMode
                                                                                                    .CREATIVE);
                                                                                    p.teleport(
                                                                                            MapWorlds
                                                                                                    .location(
                                                                                                            world,
                                                                                                            spawn));
                                                                                    menus.tools(p);
                                                                                    menus.open(p);
                                                                                    message(
                                                                                            p,
                                                                                            "Draft"
                                                                                                + " opened."
                                                                                                + " Build"
                                                                                                + " normally"
                                                                                                + " or use"
                                                                                                + " WorldEdit;"
                                                                                                + " tools"
                                                                                                + " edit"
                                                                                                + " gameplay"
                                                                                                + " metadata."
                                                                                                + " Save"
                                                                                                + " before"
                                                                                                + " closing.");
                                                                                } catch (
                                                                                        RuntimeException
                                                                                                e) {
                                                                                    worlds.release(
                                                                                            name);
                                                                                    locked.remove(
                                                                                            map);
                                                                                    matches
                                                                                            .editorReturned(
                                                                                                    p);
                                                                                    message(
                                                                                            p,
                                                                                            "Draft"
                                                                                                + " rejected:"
                                                                                                + " "
                                                                                                    + e
                                                                                                            .getMessage());
                                                                                }
                                                                            }));
                                        }));
    }

    public void importWorld(Player p, String map, String folder, String template) {
        checkName(map);
        if (!folder.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid source world folder name");
        checkName(template);
        if (locked.contains(map) || catalog().maps().containsKey(map))
            throw new IllegalArgumentException("Choose a new map name");
        var root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path source = root.resolve(folder).normalize();
        if (!source.getParent().equals(root)
                || Bukkit.getWorld(folder) != null
                || Files.isSymbolicLink(source))
            throw new IllegalArgumentException(
                    "Import an unloaded world folder directly inside the server world container");
        locked.add(map);
        disk.submit(
                        () -> {
                            Path file = data.resolve("maps/drafts/" + map + ".yml");
                            if (Files.exists(file))
                                throw new IllegalArgumentException("Draft already exists");
                            Path embedded = source.resolve("friesnet-map.yml");
                            var d =
                                    new MapDraft(
                                            ContentYaml.readBytes(
                                                    Files.isRegularFile(embedded)
                                                            ? embedded
                                                            : data.resolve(
                                                                    "maps/" + template + ".yml")));
                            String token = UUID.randomUUID().toString().replace("-", "");
                            String digest =
                                    WorldSnapshot.copy(
                                            source, data.resolve("maps/worlds/" + token));
                            d.change(
                                    doc -> {
                                        doc.put("id", "friesnet:map/" + map);
                                        doc.put("name", map);
                                        doc.put("version", "draft");
                                        doc.put("world-source", token);
                                        doc.put("world-digest", digest);
                                        doc.put("blocks", new ArrayList<>());
                                    });
                            DurableFiles.replace(file, d.bytes());
                            return null;
                        })
                .whenComplete(
                        (v, error) ->
                                plugin.onServer(
                                        () -> {
                                            locked.remove(map);
                                            if (error == null) open(p, map);
                                            else message(p, "World import failed: " + cause(error));
                                        }));
    }

    public void versions(Player p, String map) {
        checkName(map);
        disk.submit(
                        () -> {
                            Path directory = data.resolve("maps/published/" + map);
                            if (!Files.isDirectory(directory)) return List.<String>of();
                            try (var paths = Files.list(directory)) {
                                return paths.filter(
                                                f ->
                                                        f.getFileName()
                                                                .toString()
                                                                .matches("[0-9a-f]{32}\\.yml"))
                                        .sorted(
                                                Comparator.comparingLong(
                                                                (Path f) ->
                                                                        f.toFile().lastModified())
                                                        .reversed())
                                        .limit(20)
                                        .map(f -> f.getFileName().toString().replace(".yml", ""))
                                        .toList();
                            }
                        })
                .whenComplete(
                        (versions, error) ->
                                plugin.onServer(
                                        () -> {
                                            if (error != null) {
                                                message(p, "Could not read versions");
                                                return;
                                            }
                                            if (versions.isEmpty())
                                                message(p, "No published history yet");
                                            for (var version : versions)
                                                p.sendMessage(
                                                        Component.text(
                                                                        "[Open published draft] "
                                                                                + version)
                                                                .clickEvent(
                                                                        net.kyori.adventure.text
                                                                                .event.ClickEvent
                                                                                .runCommand(
                                                                                        "/zombies"
                                                                                            + " editor"
                                                                                            + " checkout"
                                                                                            + " "
                                                                                                + map
                                                                                                + " "
                                                                                                + version)));
                                        }));
    }

    public void checkout(Player p, String map, String version) {
        checkName(map);
        if (!version.matches("[0-9a-f]{32}") || contains(p.getUniqueId()) || !locked.add(map))
            throw new IllegalArgumentException(
                    "Close the draft before checking out a published version");
        disk.submit(
                        () -> {
                            Path draft = data.resolve("maps/drafts/" + map + ".yml");
                            if (Files.exists(draft))
                                DurableFiles.replace(
                                        data.resolve(
                                                "maps/drafts/history/"
                                                        + map
                                                        + "-"
                                                        + System.currentTimeMillis()
                                                        + ".yml"),
                                        ContentYaml.readBytes(draft));
                            byte[] bytes =
                                    ContentYaml.readBytes(
                                            data.resolve(
                                                    "maps/published/"
                                                            + map
                                                            + "/"
                                                            + version
                                                            + ".yml"));
                            DurableFiles.replace(draft, bytes);
                            return null;
                        })
                .whenComplete(
                        (v, error) ->
                                plugin.onServer(
                                        () -> {
                                            locked.remove(map);
                                            if (error == null) open(p, map);
                                            else
                                                message(
                                                        p,
                                                        "Version checkout failed: " + cause(error));
                                        }));
    }

    public void export(Player p, String folder) {
        checkName(folder);
        var s = require(p);
        var root = ContentYaml.parse(s.draft.bytes());
        if (root.text("world-source").equals("blueprint"))
            throw new IllegalStateException("Save the draft before exporting");
        Path source = data.resolve("maps/worlds/" + root.text("world-source"));
        Path target =
                plugin.getServer()
                        .getWorldContainer()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(folder);
        byte[] metadata = s.draft.bytes();
        disk.submit(
                        () -> {
                            String digest = WorldSnapshot.copy(source, target);
                            if (!digest.equals(root.text("world-digest")))
                                throw new java.io.IOException("Saved snapshot checksum mismatch");
                            DurableFiles.replace(target.resolve("friesnet-map.yml"), metadata);
                            return null;
                        })
                .whenComplete(
                        (v, e) ->
                                plugin.onServer(
                                        () ->
                                                message(
                                                        p,
                                                        e == null
                                                                ? "Exported the last saved draft"
                                                                        + " and metadata to "
                                                                        + folder
                                                                : "Export failed: " + cause(e))));
    }

    public void save(Player p, boolean publish) {
        var s = require(p);
        if (s.busy) throw new IllegalStateException("Save already running");
        if (publish) validate(s);
        s.savedAt = tick;
        s.busy = true;
        p.closeInventory();
        Location returnTo = p.getLocation().clone();
        var hub = Bukkit.getWorlds().getFirst();
        if (hub == s.world) throw new IllegalStateException("Draft cannot be the hub");
        if (!p.teleport(hub.getSpawnLocation())) {
            s.busy = false;
            throw new IllegalStateException("Could not leave draft for save");
        }
        if (!s.world.getPlayers().isEmpty() || !Bukkit.unloadWorld(s.world, true)) {
            s.busy = false;
            p.teleport(returnTo);
            throw new IllegalStateException("Draft world is occupied or could not be saved");
        }
        s.world = null;
        byte[] before = s.draft.bytes();
        String token = UUID.randomUUID().toString().replace("-", "");
        Path source = plugin.getServer().getWorldContainer().toPath().resolve(s.worldName);
        var gameplay = catalog().gameplay();
        disk.submit(
                        () -> {
                            String digest =
                                    WorldSnapshot.copy(
                                            source, data.resolve("maps/worlds/" + token));
                            var snapshot = new MapDraft(before);
                            snapshot.change(
                                    doc -> {
                                        doc.put("world-source", token);
                                        doc.put("world-digest", digest);
                                        doc.put("blocks", new ArrayList<>());
                                        if (publish)
                                            doc.put("version", "v" + System.currentTimeMillis());
                                    });
                            byte[] bytes = snapshot.bytes();
                            ContentCatalog candidate = null;
                            if (publish) {
                                var published = snapshot.validate(gameplay);
                                var current = ContentCatalog.load(data);
                                var maps = new LinkedHashMap<>(current.maps());
                                maps.put(s.map, published);
                                if (maps.size() > 32)
                                    throw new IllegalArgumentException("At most 32 published maps");
                                current.mysteryPools().validate(published);
                                current.perks().validate(published);
                                current.upgrades().validate(published);
                                candidate =
                                        new ContentCatalog(
                                                current.gameplay(),
                                                maps,
                                                current.mysteryPools(),
                                                current.perks(),
                                                current.upgrades());
                                Path deployed = data.resolve("maps/" + s.map + ".yml");
                                if (Files.exists(deployed))
                                    retainVersion(s.map, ContentYaml.readBytes(deployed));
                                retainVersion(s.map, bytes);
                            }
                            DurableFiles.replace(
                                    data.resolve("maps/drafts/" + s.map + ".yml"), bytes);
                            // Commit the public pointer last, after every fallible
                            // validation/write.
                            if (publish)
                                DurableFiles.replace(data.resolve("maps/" + s.map + ".yml"), bytes);
                            return new Saved(bytes, candidate);
                        })
                .whenComplete(
                        (saved, error) ->
                                plugin.onServer(
                                        () -> {
                                            if (saved != null) {
                                                var root = ContentYaml.parse(saved.bytes());
                                                s.draft.change(
                                                        doc -> {
                                                            doc.put(
                                                                    "world-source",
                                                                    root.text("world-source"));
                                                            doc.put(
                                                                    "world-digest",
                                                                    root.text("world-digest"));
                                                            doc.put("blocks", new ArrayList<>());
                                                            doc.put(
                                                                    "version",
                                                                    root.text("version"));
                                                        });
                                                if (saved.catalog() != null)
                                                    matches.installCatalog(saved.catalog());
                                            }
                                            if (sessions.get(s.player) != s) {
                                                locked.remove(s.map);
                                                worlds.release(s.worldName);
                                                return;
                                            }
                                            try {
                                                s.world =
                                                        worlds.open(
                                                                s.worldName,
                                                                MapTemplate.point(
                                                                        ContentYaml.parse(
                                                                                        s.draft
                                                                                                .bytes())
                                                                                .node(
                                                                                        "player-spawn")));
                                                s.busy = false;
                                                returnTo.setWorld(s.world);
                                                p.teleport(returnTo);
                                                menus.tools(p);
                                            } catch (RuntimeException failure) {
                                                close(p);
                                                message(
                                                        p,
                                                        "Saved world could not reopen; use editor"
                                                                + " open to recover");
                                                return;
                                            }
                                            message(
                                                    p,
                                                    error == null
                                                            ? (publish
                                                                    ? "Published immutable version;"
                                                                          + " future matches use it"
                                                                    : "Draft saved")
                                                            : ("Save failed: " + cause(error)));
                                        }));
    }

    private record Saved(byte[] bytes, ContentCatalog catalog) {}

    private void retainVersion(String map, byte[] bytes) throws Exception {
        String key =
                HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                        .substring(0, 32);
        Path version = data.resolve("maps/published/" + map + "/" + key + ".yml");
        if (!Files.exists(version)) DurableFiles.replace(version, bytes);
    }

    public void validate(EditorSession s) {
        var map = s.draft.validate(catalog().gameplay());
        catalog().mysteryPools().validate(map);
        catalog().perks().validate(map);
        catalog().upgrades().validate(map);
        MapGeometry.validate(s.world, map);
        MapRoutes.validate(s.world, map);
    }

    public void close(Player p) {
        opening.remove(p.getUniqueId());
        var s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (!s.busy) locked.remove(s.map);
        p.closeInventory();
        if (p.getWorld() == s.world) p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
        matches.editorReturned(p);
        if (!s.busy)
            worlds.release(s.worldName)
                    .exceptionally(
                            e -> {
                                plugin.getLogger().warning("event=editor_world_cleanup_deferred");
                                return null;
                            });
    }

    public void shutdown() {
        for (var id : List.copyOf(sessions.keySet())) {
            var p = Bukkit.getPlayer(id);
            if (p != null) close(p);
        }
    }

    public void tick() {
        if (++tick % 10 != 0) return;
        for (var s : sessions.values()) {
            var p = Bukkit.getPlayer(s.player);
            if (p == null || s.busy || p.getWorld() != s.world) continue;
            try {
                if (tick - s.savedAt >= 2400) {
                    s.savedAt = tick;
                    message(p, "Autosaving draft and terrain...");
                    save(p, false);
                    continue;
                }
                menus.markers(p, s);
            } catch (IllegalArgumentException invalid) {
                p.sendActionBar(
                        Component.text(
                                "Draft contains an invalid point; validate and correct its"
                                        + " metadata"));
            }
        }
    }

    public void menu(Player p) {
        menus.open(p);
    }

    public void details(Player p) {
        menus.details(p);
    }

    public static void message(Player p, String text) {
        p.sendMessage(Component.text(text == null ? "Editor operation failed" : text));
    }

    private static String cause(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage();
    }

    private static void checkName(String name) {
        if (!name.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Use a lowercase map or world folder name");
    }
}
