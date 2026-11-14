package net.fries.zombies.game.editor;

import net.fries.zombies.common.content.*;
import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Identity-checked menus, editor tools and per-author particle previews. */
final class EditorMenus implements Listener {
    private final JavaPlugin plugin;
    private final EditorService service;
    private final EditorActions actions;
    private final NamespacedKey toolKey;
    private long sequence;

    private static final class Menu implements InventoryHolder {
        final UUID player;
        final Map<Integer, Runnable> actions = new HashMap<>();
        final Inventory inventory;

        Menu(Player p, String title) {
            player = p.getUniqueId();
            inventory = Bukkit.createInventory(this, 54, Component.text(title));
        }

        public Inventory getInventory() {
            return inventory;
        }
    }

    EditorMenus(JavaPlugin plugin, EditorService service, EditorActions actions) {
        this.plugin = plugin;
        this.service = service;
        this.actions = actions;
        toolKey = new NamespacedKey(plugin, "editor_tool");
    }

    public void open(Player p) {
        var s = service.session(p.getUniqueId());
        var menu = new Menu(p, s == null ? "Zombies • Map editor" : "Draft • " + s.map);
        if (s == null) {
            button(
                    menu,
                    53,
                    Material.WRITABLE_BOOK,
                    "Recover saved drafts",
                    () -> {
                        p.closeInventory();
                        service.drafts(p);
                    });
            int slot = 0;
            button(
                    menu,
                    52,
                    Material.MAP,
                    "Create map: /zombies editor create <name>",
                    () -> {
                        p.closeInventory();
                        p.sendMessage(
                                Component.text("[Choose a new map name]")
                                        .clickEvent(
                                                net.kyori.adventure.text.event.ClickEvent
                                                        .suggestCommand(
                                                                "/zombies editor create ")));
                    });
            for (var map : new TreeSet<>(service.catalog().maps().keySet())) {
                String selected = map;
                button(
                        menu,
                        slot++,
                        Material.FILLED_MAP,
                        "Open " + map,
                        () -> service.execute(p, new String[] {"open", selected}));
            }
        } else {
            button(menu, 0, Material.CHEST, "Add gameplay object", () -> create(p));
            button(menu, 5, Material.IRON_DOOR, "Initially unlocked rooms", () -> rooms(p));
            button(
                    menu,
                    6,
                    Material.ENDER_EYE,
                    "Toggle spectator geometry preview",
                    () -> service.execute(p, new String[] {"preview"}));
            button(menu, 1, Material.COMPASS, "Browse objects", () -> browse(p, 0));
            button(
                    menu,
                    2,
                    Material.SLIME_BALL,
                    "Set main player spawn here",
                    () -> service.execute(p, new String[] {"spawn"}));
            button(
                    menu,
                    3,
                    Material.STRUCTURE_VOID,
                    "Set map bounds from selection",
                    () -> service.execute(p, new String[] {"bounds"}));
            button(
                    menu,
                    4,
                    Material.GLASS,
                    "Set playable bounds from selection",
                    () -> service.execute(p, new String[] {"playable"}));
            button(
                    menu,
                    9,
                    Material.PAPER,
                    "Validate geometry and references",
                    () -> service.execute(p, new String[] {"validate"}));
            button(
                    menu,
                    10,
                    Material.WRITABLE_BOOK,
                    "Save draft (may be incomplete)",
                    () -> service.execute(p, new String[] {"save"}));
            button(
                    menu,
                    11,
                    Material.ENCHANTED_BOOK,
                    "Publish validated immutable version",
                    () -> service.execute(p, new String[] {"publish"}));
            button(
                    menu,
                    18,
                    Material.ARROW,
                    "Undo metadata",
                    () -> service.execute(p, new String[] {"undo"}));
            button(
                    menu,
                    19,
                    Material.SPECTRAL_ARROW,
                    "Redo metadata",
                    () -> service.execute(p, new String[] {"redo"}));
            button(
                    menu,
                    20,
                    Material.CLOCK,
                    "Published version history",
                    () -> service.execute(p, new String[] {"versions", s.map}));
            button(
                    menu,
                    26,
                    Material.BARRIER,
                    "Close draft and restore equipment",
                    () -> service.execute(p, new String[] {"close"}));
            button(
                    menu,
                    45,
                    Material.OAK_SIGN,
                    "Tools: axe region • stick point • rod boards",
                    () -> {});
        }
        p.openInventory(menu.inventory);
    }

    private void rooms(Player p) {
        var s = service.require(p);
        var initial = ContentYaml.parse(s.draft.bytes()).strings("initial-rooms");
        var ids = new TreeSet<>(initial);
        s.draft.nodes("gates").forEach(g -> ids.add(g.get("unlocks-room").toString()));
        var menu = new Menu(p, "Room starting state");
        int slot = 0;
        for (String id : ids) {
            boolean unlocked = initial.contains(id);
            button(
                    menu,
                    slot++,
                    unlocked ? Material.LIME_DYE : Material.GRAY_DYE,
                    id + (unlocked ? " • initially unlocked" : " • requires door"),
                    () -> {
                        service.execute(
                                p, new String[] {"room", id, unlocked ? "locked" : "unlocked"});
                        rooms(p);
                    });
        }
        button(menu, 53, Material.ARROW, "Back", () -> open(p));
        p.openInventory(menu.inventory);
    }

    private void create(Player p) {
        var menu = new Menu(p, "Add gameplay object");
        int slot = 0;
        for (var kind : MapObject.Kind.values()) {
            var k = kind;
            button(
                    menu,
                    slot++,
                    icon(kind),
                    kind.name().replace('_', ' ')
                            + (MapObject.active(kind) ? "" : " [INACTIVE placeholder]"),
                    () -> add(p, k.name()));
        }
        button(
                menu,
                18,
                Material.OAK_TRAPDOOR,
                "Window • select existing boards first",
                () -> add(p, "window"));
        button(
                menu,
                19,
                Material.IRON_DOOR,
                "Door and room • select blocks first",
                () -> add(p, "door"));
        button(menu, 53, Material.ARROW, "Back", () -> open(p));
        p.openInventory(menu.inventory);
    }

    private void add(Player p, String kind) {
        String id;
        do {
            id = "object_" + (++sequence);
        } while (exists(service.require(p), id));
        service.execute(p, new String[] {"add", kind, id});
    }

    private boolean exists(EditorSession s, String id) {
        return List.of("objects", "windows", "gates", "barricades").stream()
                .anyMatch(
                        list -> s.draft.nodes(list).stream().anyMatch(n -> id.equals(n.get("id"))));
    }

    private void browse(Player p, int page) {
        var s = service.require(p);
        var menu = new Menu(p, "Objects • page " + (page + 1));
        var entries = new ArrayList<String[]>();
        for (var list : List.of("objects", "windows", "gates", "barricades"))
            for (var n : s.draft.nodes(list))
                entries.add(new String[] {list, (String) n.get("id")});
        for (int i = page * 45; i < Math.min(entries.size(), page * 45 + 45); i++) {
            var entry = entries.get(i);
            button(
                    menu,
                    i - page * 45,
                    Material.PAPER,
                    entry[0] + " / " + entry[1],
                    () -> service.execute(p, new String[] {"select", entry[0], entry[1]}));
        }
        if (page > 0) button(menu, 45, Material.ARROW, "Previous", () -> browse(p, page - 1));
        if (entries.size() > (page + 1) * 45)
            button(menu, 46, Material.ARROW, "Next", () -> browse(p, page + 1));
        button(menu, 53, Material.COMPASS, "Editor", () -> open(p));
        p.openInventory(menu.inventory);
    }

    public void details(Player p) {
        var s = service.require(p);
        if (s.selectedId.isEmpty()) {
            open(p);
            return;
        }
        Map<String, Object> selected;
        try {
            selected = s.selected();
        } catch (IllegalArgumentException removed) {
            s.selectedId = "";
            open(p);
            return;
        }
        var menu = new Menu(p, s.selectedList + " • " + s.selectedId);
        int slot = 0;
        for (var entry : selected.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (field.equals("id")) continue;
            if (Set.of("point", "approach", "entry").contains(field))
                button(
                        menu,
                        slot++,
                        Material.STICK,
                        "Place " + field + " • click then use point tool",
                        () -> {
                            s.field = field;
                            s.tool = "point";
                            p.getInventory().setHeldItemSlot(1);
                            p.closeInventory();
                            message(p, "Point tool now sets " + field);
                        });
            else if (Set.of("box", "region", "spawn-region").contains(field))
                button(
                        menu,
                        slot++,
                        Material.WOODEN_AXE,
                        "Apply region selection to " + field,
                        () -> service.execute(p, new String[] {"region", field}));
            else if (field.equals("boards"))
                button(
                        menu,
                        slot++,
                        Material.BLAZE_ROD,
                        "Toggle individual boards",
                        () -> {
                            s.tool = "board";
                            p.getInventory().setHeldItemSlot(3);
                            p.closeInventory();
                        });
            else if (field.equals("properties")) {
                for (var prop : ((Map<?, ?>) value).entrySet()) {
                    String key = prop.getKey().toString();
                    String v = prop.getValue().toString();
                    button(menu, slot++, Material.NAME_TAG, key + ": " + v, () -> edit(p, key));
                }
                button(
                        menu,
                        slot++,
                        Material.NAME_TAG,
                        "Set other property • /zombies editor set <key> <value>",
                        () -> {});
            } else if (!field.equals("kind"))
                button(menu, slot++, Material.NAME_TAG, field + ": " + value, () -> edit(p, field));
        }
        button(menu, 45, Material.LAVA_BUCKET, "Delete selected object", () -> confirmDelete(p));
        button(
                menu,
                46,
                Material.ENDER_PEARL,
                "Teleport to object",
                () -> {
                    var point =
                            MapTemplate.point(
                                    new ContentYaml((Map<?, ?>) s.selected().get("point")));
                    p.closeInventory();
                    p.teleport(new Location(s.world, point.x(), point.y(), point.z()));
                });
        button(menu, 53, Material.ARROW, "All objects", () -> browse(p, 0));
        p.openInventory(menu.inventory);
    }

    private void edit(Player p, String field) {
        var menu = new Menu(p, "Value for " + field);
        if (field.equals("weapons")) {
            button(
                    menu,
                    9,
                    Material.NETHER_STAR,
                    "All configured upgrades",
                    () -> service.execute(p, new String[] {"set", "weapons", "*"}));
            int slot = 10;
            for (var id : service.catalog().upgrades().weapons().keySet())
                button(
                        menu,
                        slot++,
                        Material.ANVIL,
                        id.value(),
                        () -> service.execute(p, new String[] {"set", "weapons", id.value()}));
        }
        if (field.equals("weapon")) {
            int slot = 9;
            for (var weapon : service.catalog().gameplay().weapons().values())
                button(
                        menu,
                        slot++,
                        Material.IRON_HOE,
                        weapon.name(),
                        () -> service.execute(p, new String[] {"set", field, weapon.id().value()}));
        }
        if (field.equals("perk")) {
            int slot = 9;
            for (var perk : service.catalog().perks().definitions().values())
                button(
                        menu,
                        slot++,
                        Material.POTION,
                        perk.name() + " • " + perk.price() + " gold",
                        () -> service.execute(p, new String[] {"set", "perk", perk.id().value()}));
        }
        if (field.equals("box")) {
            int slot = 9;
            for (var pool : service.catalog().mysteryPools().pools().values())
                button(
                        menu,
                        slot++,
                        Material.CHEST,
                        pool.id().value(),
                        () -> service.execute(p, new String[] {"set", "box", pool.id().value()}));
        }
        button(
                menu,
                0,
                Material.OAK_SIGN,
                "Type /zombies editor set " + field + " <value>",
                () -> {
                    p.closeInventory();
                    p.sendMessage(
                            Component.text("Set " + field + ": ")
                                    .append(
                                            Component.text("[enter value]")
                                                    .clickEvent(
                                                            net.kyori.adventure.text.event
                                                                    .ClickEvent.suggestCommand(
                                                                    "/zombies editor set "
                                                                            + field
                                                                            + " "))));
                });
        button(
                menu,
                1,
                Material.REDSTONE,
                "Toggle boolean",
                () -> {
                    var s = service.require(p);
                    Object v = s.selected().get(field);
                    service.execute(
                            p,
                            new String[] {"set", field, Boolean.toString(!Boolean.TRUE.equals(v))});
                });
        for (int i = 0; i < 4 && !Set.of("weapon", "weapons", "box", "perk").contains(field); i++) {
            int delta = new int[] {-10, -1, 1, 10}[i];
            button(
                    menu,
                    9 + i,
                    Material.GOLD_NUGGET,
                    (delta > 0 ? "+" : "") + delta,
                    () -> {
                        var s = service.require(p);
                        Object v = s.selected().get(field);
                        if (v == null && s.selected().get("properties") instanceof Map<?, ?> props)
                            v = props.get(field);
                        if (v instanceof String text && text.matches("[0-9]{1,6}"))
                            v = Integer.parseInt(text);
                        if (v instanceof Number n)
                            service.execute(
                                    p,
                                    new String[] {
                                        "set", field, Integer.toString(n.intValue() + delta)
                                    });
                        else message(p, "Use a typed text value");
                    });
        }
        button(menu, 53, Material.ARROW, "Back", () -> details(p));
        p.openInventory(menu.inventory);
    }

    private static void button(Menu menu, int slot, Material type, String name, Runnable action) {
        if (slot < 0 || slot >= 54) return;
        var item = new ItemStack(type);
        item.editMeta(meta -> meta.displayName(Component.text(name)));
        menu.inventory.setItem(slot, item);
        menu.actions.put(slot, action);
    }

    public void tools(Player p) {
        String[] tools = {"select", "point", "region", "board"};
        Material[] materials = {
            Material.NETHER_STAR, Material.STICK, service.require(p).wand, Material.BLAZE_ROD
        };
        for (int i = 0; i < tools.length; i++) {
            String tool = tools[i];
            var item = new ItemStack(materials[i]);
            item.editMeta(
                    meta -> {
                        meta.displayName(Component.text("Editor • " + tool));
                        meta.getPersistentDataContainer()
                                .set(toolKey, PersistentDataType.STRING, tool);
                    });
            p.getInventory().setItem(i, item);
        }
    }

    public void confirmDelete(Player p) {
        var s = service.require(p);
        s.selected();
        String list = s.selectedList, id = s.selectedId;
        var menu = new Menu(p, "Delete " + id + "?");
        button(
                menu,
                11,
                Material.LAVA_BUCKET,
                "Confirm deletion (metadata undo available)",
                () -> {
                    if (service.require(p) != s
                            || !s.selectedList.equals(list)
                            || !s.selectedId.equals(id))
                        throw new IllegalStateException(
                                "Selection changed; select the object again");
                    actions.execute(p, s, new String[] {"delete"});
                });
        button(menu, 15, Material.ARROW, "Cancel", () -> details(p));
        p.openInventory(menu.inventory);
    }

    public void confirmExit(Player p) {
        var menu = new Menu(p, "Exit draft?");
        button(
                menu,
                11,
                Material.BARRIER,
                "Discard changes since last save and exit",
                () -> service.close(p));
        button(
                menu,
                15,
                Material.WRITABLE_BOOK,
                "Save and continue editing",
                () -> service.execute(p, new String[] {"save"}));
        button(menu, 22, Material.ARROW, "Cancel", () -> open(p));
        p.openInventory(menu.inventory);
        message(
                p,
                "Exit discards unsaved changes. Confirm in the menu or /zombies editor exit"
                        + " confirm");
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Menu menu) {
            e.setCancelled(true);
            if (!menu.player.equals(p.getUniqueId())) return;
            var s = service.session(p.getUniqueId());
            if (s != null && s.busy) return;
            var action = menu.actions.get(e.getRawSlot());
            if (action != null)
                Bukkit.getScheduler()
                        .runTask(
                                plugin,
                                () -> {
                                    if (!p.isOnline()
                                            || p.getOpenInventory().getTopInventory()
                                                    != menu.inventory) return;
                                    try {
                                        action.run();
                                    } catch (IllegalArgumentException
                                            | IllegalStateException error) {
                                        message(p, error.getMessage());
                                    }
                                });
        } else if (service.contains(p.getUniqueId())
                && (e.getInventory().getType() != InventoryType.CRAFTING
                        || (service.session(p.getUniqueId()) == null
                                || service.session(p.getUniqueId()).busy))) e.setCancelled(true);
    }

    @EventHandler
    public void drag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu
                || service.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void drop(PlayerDropItemEvent e) {
        if (service.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void pickup(EntityPickupItemEvent e) {
        if (service.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void interact(PlayerInteractEvent e) {
        var p = e.getPlayer();
        var s = service.session(p.getUniqueId());
        if (s == null) return;
        if (s.busy || p.getWorld() != s.world) {
            e.setCancelled(true);
            return;
        }
        var item = e.getItem();
        if (item == null || e.getHand() != EquipmentSlot.HAND) return;
        String tool = item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (tool == null) return;
        e.setCancelled(true);
        try {
            if (tool.equals("select")) {
                if (p.isSneaking()) open(p);
                else
                    actions.selectNearest(
                            p,
                            s,
                            e.getClickedBlock() == null
                                    ? p.getLocation()
                                    : e.getClickedBlock().getLocation().add(.5, .5, .5));
                return;
            }
            if (e.getClickedBlock() == null) return;
            var at = e.getClickedBlock().getLocation();
            if (tool.equals("region")) {
                if (e.getAction().isLeftClick()) s.first = at;
                else s.second = at;
                message(
                        p,
                        "Region corner selected: "
                                + at.getBlockX()
                                + ", "
                                + at.getBlockY()
                                + ", "
                                + at.getBlockZ());
            } else if (e.getAction().isRightClick() && tool.equals("board"))
                actions.board(s, e.getClickedBlock());
            else if (e.getAction().isRightClick() && tool.equals("point"))
                actions.point(s, s.field, at.add(.5, 1, .5));
        } catch (IllegalArgumentException | IllegalStateException error) {
            message(p, error.getMessage());
        }
    }

    @EventHandler
    public void block(BlockPlaceEvent e) {
        var s = service.session(e.getPlayer().getUniqueId());
        if (s != null && (s.busy || e.getBlock().getWorld() != s.world)) e.setCancelled(true);
    }

    @EventHandler
    public void block(BlockBreakEvent e) {
        var s = service.session(e.getPlayer().getUniqueId());
        if (s != null
                && (s.busy
                        || e.getBlock().getWorld() != s.world
                        || isTool(e.getPlayer().getInventory().getItemInMainHand())))
            e.setCancelled(true);
    }

    private boolean isTool(ItemStack item) {
        return item.getPersistentDataContainer().has(toolKey);
    }

    @EventHandler
    public void damage(EntityDamageEvent e) {
        if (service.contains(e.getEntity().getUniqueId())
                || e instanceof EntityDamageByEntityEvent by
                        && service.contains(by.getDamager().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void portal(PlayerPortalEvent e) {
        if (service.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void move(PlayerMoveEvent e) {
        var s = service.session(e.getPlayer().getUniqueId());
        if (s != null && s.busy && !(e instanceof PlayerTeleportEvent)) e.setTo(e.getFrom());
    }

    public void markers(Player p, EditorSession s) {
        for (var list : List.of("objects", "windows", "gates", "barricades"))
            for (var n : s.draft.nodes(list)) {
                var point = MapTemplate.point(new ContentYaml((Map<?, ?>) n.get("point")));
                var at = new Location(s.world, point.x(), point.y() + .2, point.z());
                if (at.distanceSquared(p.getLocation()) < 48 * 48)
                    p.spawnParticle(
                            s.selectedId.equals(n.get("id"))
                                    ? Particle.END_ROD
                                    : Particle.HAPPY_VILLAGER,
                            at,
                            2,
                            .12,
                            .2,
                            .12,
                            0);
            }
        if (s.first != null && s.second != null) {
            double x1 = Math.min(s.first.getX(), s.second.getX()),
                    x2 = Math.max(s.first.getX(), s.second.getX()) + 1,
                    y1 = Math.min(s.first.getY(), s.second.getY()),
                    y2 = Math.max(s.first.getY(), s.second.getY()) + 1,
                    z1 = Math.min(s.first.getZ(), s.second.getZ()),
                    z2 = Math.max(s.first.getZ(), s.second.getZ()) + 1;
            for (int i = 0; i <= 16; i++) {
                double t = i / 16.0;
                for (double y : new double[] {y1, y2})
                    for (double z : new double[] {z1, z2})
                        particle(p, s.world, x1 + (x2 - x1) * t, y, z);
                for (double x : new double[] {x1, x2})
                    for (double z : new double[] {z1, z2})
                        particle(p, s.world, x, y1 + (y2 - y1) * t, z);
                for (double x : new double[] {x1, x2})
                    for (double y : new double[] {y1, y2})
                        particle(p, s.world, x, y, z1 + (z2 - z1) * t);
            }
        }
        p.sendActionBar(
                Component.text(
                        "DRAFT • "
                                + s.map
                                + " • "
                                + (s.selectedId.isEmpty()
                                        ? "Sneak + star: menu"
                                        : s.selectedId + " / " + s.field)
                                + " • /zombies editor menu"));
    }

    private static void particle(Player p, World w, double x, double y, double z) {
        p.spawnParticle(Particle.END_ROD, new Location(w, x, y, z), 1, 0, 0, 0, 0);
    }

    private static Material icon(MapObject.Kind k) {
        return switch (k) {
            case PLAYER_SPAWN -> Material.SLIME_BALL;
            case SPECTATOR_SPAWN -> Material.ENDER_EYE;
            case ZOMBIE_REGION, BOSS_SPAWN -> Material.ZOMBIE_HEAD;
            case WALL_WEAPON -> Material.IRON_HOE;
            case AMMO -> Material.ARROW;
            case MYSTERY_BOX -> Material.CHEST;
            case PERK -> Material.POTION;
            case PACK_A_PUNCH -> Material.ANVIL;
            case POWER -> Material.LEVER;
            case HOLOGRAM -> Material.OAK_SIGN;
            case RANGE_TARGET -> Material.TARGET;
            case CUSTOM -> Material.COMPARATOR;
        };
    }

    private static void message(Player p, String text) {
        EditorService.message(p, text);
    }
}
