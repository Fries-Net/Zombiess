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
                    world.save();
                    check(Bukkit.unloadWorld(world, true), "source unloaded before import");
                }
            }
            case "editor-opened" -> {
                var s = editor.require(p);
                check(s.world == p.getWorld() && !s.busy, "isolated editor admission");
                initialObjects = s.draft.nodes("objects").size();
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.GOLD_BLOCK,
                        "imported real world geometry");
                check(p.getGameMode() == GameMode.CREATIVE, "creative building");
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
                check(
                        s.draft.nodes("objects").size() == initialObjects + 14,
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
                check(map.objects().size() == initialObjects + 14, "published complete metadata");
                publishedVersion = map.version();
                check(
                        s.world.getBlockAt(8, 64, 7).getType() == Material.DIAMOND_BLOCK,
                        "terrain edits survived save");
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
            case "editor-concurrent" -> {
                var author = Bukkit.getPlayerExact("SurvivorTwo");
                var s = editor.require(author);
                check(
                        s.world == author.getWorld(),
                        "second client owns isolated draft while match runs");
                s.world.getBlockAt(8, 64, 7).setType(Material.EMERALD_BLOCK, false);
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
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
