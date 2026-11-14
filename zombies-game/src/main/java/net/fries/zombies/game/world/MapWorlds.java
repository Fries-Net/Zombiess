package net.fries.zombies.game.world;

import net.fries.zombies.common.content.MapTemplate;
import net.fries.zombies.persistence.DurableFiles;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Disposable worlds built only from immutable blueprints. Paper calls stay on the server thread.
 */
public final class MapWorlds {
    private static final String PREFIX = "fnz_";
    private static final String MARKER = "friesnet-runtime-world";
    private final JavaPlugin plugin;
    private final DurableFiles disk;
    private final Path root;

    public MapWorlds(JavaPlugin plugin, DurableFiles disk) {
        this.plugin = plugin;
        this.disk = disk;
        root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    public CompletableFuture<Void> recover() {
        // Capture loaded worlds here, never call Bukkit from the disk worker.
        Set<String> loaded = new HashSet<>();
        plugin.getServer().getWorlds().forEach(w -> loaded.add(w.getName()));
        return disk.submit(
                () -> {
                    try (var paths = Files.list(root)) {
                        for (var path :
                                paths.filter(p -> ownedName(p.getFileName().toString())).toList()) {
                            if (loaded.contains(path.getFileName().toString()))
                                throw new IOException(
                                        "Runtime world unexpectedly loaded at startup");
                            if (Files.exists(path.resolve(MARKER))) erase(path);
                        }
                    }
                    return null;
                });
    }

    public CompletableFuture<String> allocate(UUID id) {
        return allocate(id, null);
    }

    public CompletableFuture<String> allocate(UUID id, MapTemplate map) {
        return allocate(
                id,
                map == null ? "blueprint" : map.worldSource(),
                map == null ? "none" : map.worldDigest());
    }

    public CompletableFuture<String> allocate(UUID id, String sourceId, String expectedDigest) {
        if (!sourceId.equals("blueprint")
                && (!sourceId.matches("[0-9a-f]{32}") || !expectedDigest.matches("[0-9a-f]{64}")))
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid snapshot reference"));
        return disk.submit(
                () -> {
                    String name = PREFIX + id.toString().replace("-", "");
                    Path path = checked(root.resolve(name));
                    Files.createDirectory(path);
                    DurableFiles.replace(
                            path.resolve(MARKER),
                            name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try {
                        if (!sourceId.equals("blueprint")) {
                            Path source =
                                    plugin.getDataFolder()
                                            .toPath()
                                            .resolve("maps/worlds")
                                            .resolve(sourceId);
                            String digest =
                                    net.fries.zombies.persistence.WorldSnapshot.copy(
                                            source, path.resolve("snapshot"));
                            if (!digest.equals(expectedDigest))
                                throw new IOException("Published world checksum mismatch");
                            try (var files = Files.list(path.resolve("snapshot"))) {
                                for (var entry : files.toList())
                                    Files.move(entry, path.resolve(entry.getFileName()));
                            }
                            Files.delete(path.resolve("snapshot"));
                        }
                        return name;
                    } catch (Exception failure) {
                        try {
                            erase(path);
                        } catch (IOException cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                });
    }

    public World open(String name, MapTemplate.Point spawnPoint) {
        World world =
                new WorldCreator(name)
                        .generator(new EmptyGenerator(spawnPoint))
                        .generateStructures(false)
                        .createWorld();
        if (world == null) throw new IllegalStateException("Could not create match world");
        world.setAutoSave(false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setTime(18000);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, false);
        world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        return world;
    }

    public World build(String name, MapTemplate map) {
        World world = open(name, map.playerSpawn());
        for (var fill : map.blocks())
            each(world, fill.box(), block -> block.setType(material(fill.material()), false));
        // Verify final template state, including intentional overlays, before admission.
        var expected = new HashMap<String, Material>();
        for (var fill : map.blocks())
            each(
                    world,
                    fill.box(),
                    b ->
                            expected.put(
                                    b.getX() + ":" + b.getY() + ":" + b.getZ(),
                                    material(fill.material())));
        for (var fill : map.blocks())
            each(
                    world,
                    fill.box(),
                    b -> {
                        if (b.getType() != expected.get(b.getX() + ":" + b.getY() + ":" + b.getZ()))
                            throw new IllegalStateException("Template verification failed");
                    });
        MapGeometry.validate(world, map);
        var spawn = location(world, map.playerSpawn());
        world.setSpawnLocation(spawn);
        plugin.getLogger()
                .info(
                        "event=map_prepared map="
                                + map.id().value()
                                + " version="
                                + map.version()
                                + " world="
                                + name);
        return world;
    }

    public CompletableFuture<Void> release(String name) {
        World world = plugin.getServer().getWorld(name);
        if (world != null) {
            if (!world.getPlayers().isEmpty())
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Match world still occupied"));
            world.getEntities().forEach(org.bukkit.entity.Entity::remove);
            if (!plugin.getServer().unloadWorld(world, false))
                return CompletableFuture.failedFuture(
                        new IllegalStateException("World unload rejected"));
        }
        return disk.submit(
                () -> {
                    erase(root.resolve(name));
                    return null;
                });
    }

    private Path checked(Path path) throws IOException {
        var normalized = path.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(root)
                || !ownedName(normalized.getFileName().toString())
                || Files.isSymbolicLink(normalized)
                || !root.toRealPath().equals(normalized.getParent().toRealPath()))
            throw new IOException("Unsafe runtime world path");
        return normalized;
    }

    private void erase(Path path) throws IOException {
        path = checked(path);
        if (!Files.exists(path)) return;
        Path marker = path.resolve(MARKER);
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker)
                || !Files.readString(marker).equals(path.getFileName().toString()))
            throw new IOException("World ownership not verified");
        try (var walk = Files.walk(path)) {
            var entries = walk.toList();
            for (var entry : entries)
                if (Files.isSymbolicLink(entry))
                    throw new IOException("Runtime world contains a symbolic link");
            for (var entry : entries.stream().sorted(Comparator.reverseOrder()).toList())
                if (!entry.equals(path) && !entry.equals(marker)) Files.delete(entry);
        }
        // Keep ownership proof until the very end, so a locked region file can be retried safely.
        try {
            Files.delete(marker);
            Files.delete(path);
        } catch (IOException error) {
            if (Files.isDirectory(path) && !Files.exists(marker))
                DurableFiles.replace(
                        marker,
                        path.getFileName()
                                .toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw error;
        }
        if (Files.exists(path)) throw new IOException("World restoration deletion not verified");
    }

    private static boolean ownedName(String name) {
        return name.matches("fnz_[0-9a-f]{32}");
    }

    public static Material material(String name) {
        var material = Material.matchMaterial(name);
        if (material == null
                || !material.isBlock()
                || material == Material.TNT
                || material == Material.COMMAND_BLOCK
                || material == Material.CHAIN_COMMAND_BLOCK
                || material == Material.REPEATING_COMMAND_BLOCK)
            throw new IllegalArgumentException("Unsupported template material");
        return material;
    }

    public static Location location(World world, MapTemplate.Point p) {
        return new Location(world, p.x(), p.y(), p.z());
    }

    public static void each(World world, MapTemplate.Box b, Consumer<Block> consumer) {
        for (int x = b.x1(); x <= b.x2(); x++)
            for (int y = b.y1(); y <= b.y2(); y++)
                for (int z = b.z1(); z <= b.z2(); z++) consumer.accept(world.getBlockAt(x, y, z));
    }

    private static final class EmptyGenerator extends ChunkGenerator {
        private final MapTemplate.Point spawn;

        private EmptyGenerator(MapTemplate.Point spawn) {
            this.spawn = spawn;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return location(world, spawn);
        }

        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }
}
