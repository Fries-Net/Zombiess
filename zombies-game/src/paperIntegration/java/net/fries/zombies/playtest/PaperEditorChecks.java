package net.fries.zombies.playtest;

import net.fries.zombies.common.content.*;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.world.MapWorlds;
import net.fries.zombies.persistence.DurableFiles;

import org.bukkit.*;

import java.util.*;

/** Live authoring checks; production never exposes these fixture operations. */
final class PaperEditorChecks {
    private static String publishedVersion;
    private static int initialObjects;
    private static String sourceDigest;
    private static int mysteryGold;

    static boolean handle(String step, ZombiesPlugin plugin) throws Exception {
        var p = Bukkit.getPlayerExact("SurvivorOne");
        var editor = plugin.matches().editor();
        switch (step) {
            case "editor-source" -> {
                p.setOp(true);
                Bukkit.getPlayerExact("SurvivorTwo").setOp(true);
                try (var disk = new DurableFiles()) {
                    var world =
                            new MapWorlds(plugin, disk)
                                    .build(
                                            "import_fixture",
                                            plugin.matches().catalog().maps().get("training"));
                    world.getBlockAt(8, 64, 7).setType(Material.GOLD_BLOCK, false);
                    var chestBlock = world.getBlockAt(9, 64, 7);
                    chestBlock.setType(Material.CHEST, false);
                    var facing = (org.bukkit.block.data.Directional) chestBlock.getBlockData();
                    facing.setFacing(org.bukkit.block.BlockFace.EAST);
                    chestBlock.setBlockData(facing, false);
                    ((org.bukkit.block.Chest) chestBlock.getState())
                            .getBlockInventory()
                            .setItem(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND, 3));
                    world.save();
                    check(Bukkit.unloadWorld(world, true), "source unloaded before import");
                    sourceDigest =
                            net.fries.zombies.persistence.WorldSnapshot.digest(
                                    Bukkit.getWorldContainer().toPath().resolve("import_fixture"));
                }
            }
            case "editor-opened" -> {
                var s = editor.require(p);
                check(s.world == p.getWorld() && !s.busy, "isolated editor admission");
                initialObjects = s.draft.nodes("objects").size();
                editor.execute(p, "room workshop unlocked".split(" "));
                check(
                        ContentYaml.parse(s.draft.bytes())
                                .strings("initial-rooms")
                                .contains("workshop"),
                        "initial room unlocked through authoring");
                editor.execute(p, "room workshop locked".split(" "));
                check(
                        !ContentYaml.parse(s.draft.bytes())
                                .strings("initial-rooms")
                                .contains("workshop"),
                        "room restored to door-dependent access");
                check(
                        editor.suggest(p, new String[] {"en"}).contains("enter"),
                        "editor command completion");
                check(
                        editor.suggest(p, new String[] {"enter", "tr"}).contains("training"),
                        "map completion");
                editor.execute(p, new String[] {"wand", "BONE"});
                check(
                        p.getInventory().getItem(2).getType() == Material.BONE,
                        "configurable selection wand");
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.GOLD_BLOCK,
                        "imported real world geometry");
                check(p.getGameMode() == GameMode.CREATIVE, "creative building");
                check(
                        ((org.bukkit.block.Chest) s.world.getBlockAt(9, 64, 7).getState())
                                        .getBlockInventory()
                                        .getItem(0)
                                        .getAmount()
                                == 3,
                        "imported tile entity inventory");
                check(
                        ((org.bukkit.block.data.Directional)
                                                s.world.getBlockAt(9, 64, 7).getBlockData())
                                        .getFacing()
                                == org.bukkit.block.BlockFace.EAST,
                        "imported block state");
                check(p.getInventory().getItem(4) == null, "original equipment safely captured");
            }
            case "editor-menu-created" -> {
                var s = editor.require(p);
                check(
                        s.draft.nodes("objects").size() == initialObjects + 1,
                        "native inventory menu added one object");
                check(
                        s.draft.nodes("objects").getLast().get("kind").equals("HOLOGRAM"),
                        "hologram selected through menu");
            }
            case "editor-metadata" -> {
                var s = editor.require(p);
                p.closeInventory();
                for (var kind : MapObject.Kind.values()) {
                    editor.execute(
                            p,
                            new String[] {
                                "add", kind.name(), "test_" + kind.name().toLowerCase(Locale.ROOT)
                            });
                }
                editor.execute(p, "select objects test_mystery_box".split(" "));
                editor.execute(p, "set box friesnet:box/test".split(" "));
                editor.execute(p, "set price 41".split(" "));
                p.teleport(new Location(s.world, -10.5, 64, -4.5));
                editor.execute(p, new String[] {"point"});
                editor.execute(p, "add mystery_box test_box_destination".split(" "));
                editor.execute(p, "set box friesnet:box/test".split(" "));
                editor.execute(p, "set price 41".split(" "));
                p.teleport(new Location(s.world, -1.5, 64, -4.5));
                editor.execute(p, new String[] {"point"});
                check(
                        s.draft.nodes("objects").size() == initialObjects + 15,
                        "all gameplay object kinds authored");
                editor.execute(p, "select objects test_hologram".split(" "));
                editor.execute(p, "set text Gold {gold} / round {round}".split(" "));
                check(
                        ((Map<?, ?>) s.selected().get("properties"))
                                .get("text")
                                .equals("Gold {gold} / round {round}"),
                        "text property edited");
                s.draft.undo();
                check(
                        !((Map<?, ?>) s.selected().get("properties"))
                                .get("text")
                                .equals("Gold {gold} / round {round}"),
                        "undo");
                s.draft.redo();
                editor.execute(p, new String[] {"delete"});
                check(
                        s.draft.node("objects", "test_hologram") != null,
                        "deletion waits for confirmation");
                p.closeInventory();
                editor.execute(p, "select objects test_wall_weapon".split(" "));
                editor.execute(p, "set weapon friesnet:weapon/mac10".split(" "));
                editor.execute(p, "set price 123".split(" "));
                editor.execute(p, "set ammo-price 17".split(" "));
                editor.execute(p, "set rooms workshop".split(" "));
                p.teleport(new Location(s.world, -10.5, 64, 4.5));
                editor.execute(p, "point".split(" "));
                editor.execute(p, "select objects test_ammo".split(" "));
                editor.execute(p, "set weapon friesnet:weapon/mac10".split(" "));
                editor.execute(p, "set ammo-price 11".split(" "));
                editor.execute(p, "set rooms workshop".split(" "));
                p.teleport(new Location(s.world, -1.5, 64, 5.5));
                editor.execute(p, "point".split(" "));
                editor.execute(p, "select gates workshop".split(" "));
                editor.execute(p, "set price 71".split(" "));
                s.world.getBlockAt(8, 64, 7).setType(Material.DIAMOND_BLOCK, false);
                editor.execute(p, "select windows north".split(" "));
                p.teleport(new Location(s.world, -4.5, 64, -8.5));
                p.closeInventory();
                p.getInventory().setHeldItemSlot(3);
            }
            case "editor-board-removed" ->
                    check(
                            ((List<?>) editor.require(p).selected().get("boards")).size() == 5,
                            "native board tool removed one component; boards="
                                    + ((List<?>) editor.require(p).selected().get("boards"))
                                            .size());
            case "editor-board-restored" -> {
                check(
                        ((List<?>) editor.require(p).selected().get("boards")).size() == 6,
                        "native board tool restored component");
                // Restore the central lower/upper ordering used for entry, independent of editor
                // click order.
                var s = editor.require(p);
                s.draft.undo();
                s.draft.undo();
                editor.validate(s);
                // Both points remain safe, but a sealed approach must block publication.
                var wall = new ArrayList<org.bukkit.block.Block>();
                for (int x = -6; x <= -4; x++)
                    for (int z = -12; z <= -11; z++)
                        for (int y = 64; y <= 66; y++) {
                            if (x == -5 && z == -11) continue;
                            var b = s.world.getBlockAt(x, y, z);
                            wall.add(b);
                            b.setType(Material.BEDROCK, false);
                        }
                boolean rejected = false;
                try {
                    editor.validate(s);
                } catch (IllegalArgumentException invalid) {
                    rejected = true;
                }
                check(rejected, "blocked exterior route prevents publication");
                wall.forEach(b -> b.setType(Material.AIR, false));
                editor.validate(s);
            }
            case "editor-incomplete" -> {
                var s = editor.require(p);
                check(!s.busy && s.world == p.getWorld(), "incomplete draft saved and reopened");
                check(
                        ((Number) s.draft.node("windows", "north").get("break-ticks")).intValue()
                                == 0,
                        "invalid duration retained in draft");
                check(
                        !plugin.matches().catalog().maps().containsKey("authored"),
                        "incomplete draft not published");
                editor.execute(p, "set break-ticks 40".split(" "));
            }
            case "editor-invalid" -> editor.execute(p, "set break-ticks 0".split(" "));
            case "editor-published" -> {
                var s = editor.require(p);
                check(!s.busy && s.world == p.getWorld(), "save resumed author");
                var map = plugin.matches().catalog().maps().get("authored");
                check(
                        map != null && !map.worldSource().equals("blueprint"),
                        "published snapshot installed");
                check(map.objects().size() == initialObjects + 15, "published complete metadata");
                publishedVersion = map.version();
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.DIAMOND_BLOCK,
                        "terrain edits survived save");
                check(
                        sourceDigest.equals(
                                net.fries.zombies.persistence.WorldSnapshot.digest(
                                        Bukkit.getWorldContainer()
                                                .toPath()
                                                .resolve("import_fixture"))),
                        "original imported world remains byte-for-byte unchanged");
            }
            case "editor-closed" -> {
                check(!editor.contains(p.getUniqueId()), "session closed");
                check(
                        p.getInventory().getItem(4) != null
                                && p.getInventory().getItem(4).getType() == Material.EMERALD,
                        "equipment restored after editor");
            }
            case "editor-match" -> {
                var r = plugin.matches().current();
                check(r != null && r.playing(), "authored map playable");
                check(r.map.version().equals(publishedVersion), "published version pinned");
                check(
                        r.world.getBlockAt(8, 64, 7).getType() == Material.DIAMOND_BLOCK,
                        "published world copied into match");
                r.world.getBlockAt(8, 64, 7).setType(Material.AIR, false);
                // An attempt to enter the editor while playing cannot restore or replace match
                // gear.
                editor.execute(p, "open authored".split(" "));
                check(!editor.contains(p.getUniqueId()), "active match admission rejected");
                check(r.combat.held(p) != null, "active match gear preserved");
            }
            case "editor-runtime" -> {
                var r = plugin.matches().current();
                var survivor = r.survivor(p.getUniqueId());
                var weaponId = new net.fries.zombies.common.NamespacedId("friesnet:weapon/mac10");
                p.teleport(new Location(r.world, -10.5, 64, 4.5));
                int before = survivor.points();
                r.interactions.interact(p);
                check(
                        survivor.points() == before && !survivor.weapons().containsKey(weaponId),
                        "published room restriction prevents purchase without charging");
                p.teleport(new Location(r.world, 5, 64, .5));
                r.interactions.interact(p);
                check(
                        r.interactions.roomUnlocked("workshop") && survivor.points() == before - 71,
                        "edited door opens room at configured price");
                check(
                        r.world.getBlockAt(6, 64, 0).getType() == Material.AIR,
                        "published physical door removed");
                p.teleport(new Location(r.world, -10.5, 64, 4.5));
                r.interactions.interact(p);
                var weapon = survivor.weapons().get(weaponId);
                check(
                        weapon != null && survivor.points() == before - 71 - 123,
                        "published wall weapon sells selected weapon at selected price");
                weapon.shoot(r.tick);
                weapon.reload(r.tick);
                weapon.tick(r.tick + 1000);
                p.teleport(new Location(r.world, -1.5, 64, 5.5));
                r.interactions.interact(p);
                check(
                        survivor.points() == before - 71 - 123 - 11
                                && weapon.reserve() == r.content.weapons().get(weaponId).reserve(),
                        "published ammunition station refills at configured price");
                check(survivor.weapons().get(weaponId) == weapon, "ammo preserves weapon instance");
            }
            case "editor-concurrent" -> {
                var author = Bukkit.getPlayerExact("SurvivorTwo");
                var s = editor.require(author);
                check(
                        s.world == author.getWorld(),
                        "second client owns isolated draft while match runs");
                s.world.getBlockAt(8, 64, 7).setType(Material.EMERALD_BLOCK, false);
            }
            case "mystery-start" -> {
                var r = plugin.matches().current();
                p.teleport(new Location(r.world, -10.5, 64, -4.5));
                var other = Bukkit.getPlayerExact("SurvivorTwo");
                other.teleport(p.getLocation());
                mysteryGold = r.survivor(p.getUniqueId()).points();
                int otherGold = r.survivor(other.getUniqueId()).points();
                check(r.mysteryBoxes.interact(p), "published mystery station starts purchase");
                r.mysteryBoxes.interact(p);
                r.mysteryBoxes.interact(other);
                check(
                        r.survivor(p.getUniqueId()).points() == mysteryGold - 41,
                        "opening input cannot duplicate charge");
                check(
                        r.survivor(other.getUniqueId()).points() == otherGold,
                        "teammate cannot steal or buy occupied reward");
                check(
                        !r.world.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).isEmpty(),
                        "native opening preview created");
            }
            case "mystery-collected" -> {
                var r = plugin.matches().current();
                var s = r.survivor(p.getUniqueId());
                var awp = new net.fries.zombies.common.NamespacedId("friesnet:weapon/awp");
                r.mysteryBoxes.interact(p);
                check(
                        s.weapons().containsKey(awp) && s.points() == mysteryGold - 41,
                        "rare reward collected once at configured price");
                var instance = s.weapons().get(awp).instanceId();
                r.mysteryBoxes.interact(p);
                check(
                        s.points() == mysteryGold - 41
                                && s.weapons().get(awp).instanceId().equals(instance),
                        "relocated station cannot duplicate purchase or reward");
                check(
                        r.world.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).isEmpty(),
                        "collected preview removed");
                p.teleport(new Location(r.world, -1.5, 64, -4.5));
                for (int i = 0; i < 8; i++)
                    if (p.getInventory().getItem(i) == null)
                        p.getInventory()
                                .setItem(i, new org.bukkit.inventory.ItemStack(Material.BARRIER));
                r.mysteryBoxes.interact(p);
                check(s.points() == mysteryGold - 41, "full inventory rejected without charging");
                r.combat.equip(p);
                r.mysteryBoxes.interact(p);
                check(
                        s.points() == mysteryGold - 82,
                        "relocated destination accepts next purchase");
            }
            case "mystery-expired" -> {
                var r = plugin.matches().current();
                var s = r.survivor(p.getUniqueId());
                check(s.points() == mysteryGold - 41, "expired reward refunded exactly once");
                check(
                        r.world.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).isEmpty(),
                        "expired preview removed");
                r.mysteryBoxes.tick();
                check(s.points() == mysteryGold - 41, "refund not repeated");
            }
            case "mystery-capacity-start" -> {
                var r = plugin.matches().current();
                r.survivor(p.getUniqueId())
                        .weapons()
                        .remove(new net.fries.zombies.common.NamespacedId("friesnet:weapon/awp"));
                r.combat.equip(p);
                r.mysteryBoxes.interact(p);
                check(
                        r.survivor(p.getUniqueId()).points() == mysteryGold - 82,
                        "capacity recheck fixture paid once");
                for (int i = 0; i < 8; i++)
                    if (p.getInventory().getItem(i) == null)
                        p.getInventory()
                                .setItem(i, new org.bukkit.inventory.ItemStack(Material.BARRIER));
            }
            case "mystery-capacity-refund" -> {
                var r = plugin.matches().current();
                r.mysteryBoxes.interact(p);
                check(
                        r.survivor(p.getUniqueId()).points() == mysteryGold - 41,
                        "inventory filled during opening causes full refund");
                check(
                        !r.survivor(p.getUniqueId())
                                .weapons()
                                .containsKey(
                                        new net.fries.zombies.common.NamespacedId(
                                                "friesnet:weapon/awp")),
                        "failed collection cannot create a hidden weapon");
                r.combat.equip(p);
            }
            case "editor-version-pinned" -> {
                var author = Bukkit.getPlayerExact("SurvivorTwo");
                check(!editor.require(author).busy, "concurrent publication complete");
                var r = plugin.matches().current();
                check(
                        r.map.version().equals(publishedVersion),
                        "active match kept original immutable version");
                check(
                        !plugin.matches()
                                .catalog()
                                .maps()
                                .get("authored")
                                .version()
                                .equals(publishedVersion),
                        "future matches receive new publication");
                check(
                        r.world.getBlockAt(8, 64, 7).getType() == Material.AIR,
                        "publication did not rebuild active world");
            }
            case "editor-survived-cleanup" -> {
                var author = Bukkit.getPlayerExact("SurvivorTwo");
                var s = editor.require(author);
                check(
                        author.getWorld() == s.world && author.getGameMode() == GameMode.CREATIVE,
                        "match cleanup preserved unrelated author session");
            }
            case "editor-exported" ->
                    check(
                            java.nio.file.Files.isRegularFile(
                                    Bukkit.getWorldContainer()
                                            .toPath()
                                            .resolve("export_fixture/friesnet-map.yml")),
                            "exported saved world and metadata");
            case "editor-restored-match" -> {
                var r = plugin.matches().current();
                check(
                        r != null
                                && r.playing()
                                && r.world.getBlockAt(8, 64, 7).getType() == Material.EMERALD_BLOCK,
                        "next match loaded new immutable imported geometry");
            }
            case "editor-created" -> {
                var s = editor.require(p);
                check(
                        s.map.equals("created_fixture") && s.world == p.getWorld(),
                        "create enters fresh isolated draft");
                check(
                        !plugin.matches().catalog().maps().containsKey(s.map),
                        "create does not publish");
                editor.execute(p, "create created_fixture".split(" "));
                check(
                        editor.require(p) == s,
                        "create refuses overwrite without replacing active session");
                editor.execute(p, new String[] {"exit"});
                check(
                        editor.contains(p.getUniqueId()),
                        "exit requires explicit discard confirmation");
            }
            case "editor-duplicate" -> {
                var s = editor.require(p);
                check(s.map.equals("duplicate_fixture"), "duplicate opens new draft");
                check(
                        ContentYaml.parse(s.draft.bytes())
                                .text("id")
                                .equals("friesnet:map/duplicate_fixture"),
                        "duplicate has independent identity");
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.EMERALD_BLOCK,
                        "duplicate retains source geometry");
                s.world.getBlockAt(8, 64, 7).setType(Material.REDSTONE_BLOCK, false);
                s.savedAt = -2400;
            }
            case "editor-autosaved" -> {
                var s = editor.require(p);
                check(!s.busy && s.world == p.getWorld(), "autosave restored author");
                var path =
                        plugin.getDataFolder()
                                .toPath()
                                .resolve("maps/drafts/duplicate_fixture.yml");
                check(
                        !ContentYaml.parse(ContentYaml.readBytes(path))
                                .text("world-source")
                                .equals("blueprint"),
                        "autosave forced recoverable world snapshot");
                // The author's durable backup is under test; unrelated control profiles are vanilla
                // state and must be forced before terminating the whole JVM without a clean quit.
                for (var control : Bukkit.getOnlinePlayers())
                    if (!editor.contains(control.getUniqueId())) control.saveData();
            }
            case "editor-draft-recovered" -> {
                var s = editor.require(p);
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.REDSTONE_BLOCK,
                        "autosaved terrain and draft survived forced process termination");
            }
            case "editor-roundtrip" -> {
                var s = editor.require(p);
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.DIAMOND_BLOCK,
                        "export/import geometry roundtrip");
                check(
                        s.draft.node("objects", "test_wall_weapon").get("properties")
                                        instanceof Map<?, ?> props
                                && props.get("price").equals("123"),
                        "export/import metadata roundtrip");
            }
            case "editor-history-open" -> {
                try (var files =
                        java.nio.file.Files.list(
                                plugin.getDataFolder()
                                        .toPath()
                                        .resolve("maps/published/authored"))) {
                    var versions =
                            files.filter(f -> f.getFileName().toString().endsWith(".yml"))
                                    .sorted(
                                            Comparator.comparingLong(
                                                    f -> f.toFile().lastModified()))
                                    .toList();
                    check(versions.size() >= 2, "immutable publication history retained");
                    editor.checkout(
                            p,
                            "authored",
                            versions.getFirst().getFileName().toString().replace(".yml", ""));
                }
            }
            case "editor-history-restored" -> {
                var s = editor.require(p);
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.DIAMOND_BLOCK,
                        "older immutable geometry checked out as editable draft");
                check(
                        plugin.matches()
                                .catalog()
                                .maps()
                                .get("authored")
                                .worldSource()
                                .equals(
                                        ContentYaml.parse(
                                                        ContentYaml.readBytes(
                                                                plugin.getDataFolder()
                                                                        .toPath()
                                                                        .resolve(
                                                                                "maps/authored.yml")))
                                                .text("world-source")),
                        "history checkout does not change published pointer");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void awaitClosed(ZombiesPlugin plugin, org.bukkit.plugin.java.JavaPlugin assertions) {
        new org.bukkit.scheduler.BukkitRunnable() {
            private int ticks;

            public void run() {
                var p = Bukkit.getPlayerExact("SurvivorOne");
                if (++ticks > 200 || p == null) {
                    assertions
                            .getLogger()
                            .severe("PLAYTEST_FAIL editor-closed restoration deadline exceeded");
                    cancel();
                    return;
                }
                if (plugin.matches().editor().contains(p.getUniqueId())
                        || plugin.matches().managed(p.getUniqueId())) return;
                try {
                    check(
                            p.getInventory().getItem(4) != null
                                    && p.getInventory().getItem(4).getType() == Material.EMERALD,
                            "equipment restored after editor");
                    check(
                            p.getGameMode() == GameMode.SURVIVAL,
                            "original mode restored after editor");
                    assertions.getLogger().info("PLAYTEST_PASS editor-closed");
                } catch (Throwable error) {
                    assertions.getLogger().severe("PLAYTEST_FAIL editor-closed " + error);
                }
                cancel();
            }
        }.runTaskTimer(assertions, 1, 1);
    }
}
