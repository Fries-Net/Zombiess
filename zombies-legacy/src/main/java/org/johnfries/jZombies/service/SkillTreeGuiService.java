package org.johnfries.jZombies.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.game.GameManager;
import org.johnfries.jZombies.model.SkillDefinition;
import org.johnfries.jZombies.model.SkillTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SkillTreeGuiService {
    private final JavaPlugin plugin;
    private final LangService lang;
    private final PlayerDataStore dataStore;
    private final SkillService skillService;
    private final GameManager gameManager;
    private final Map<UUID, MenuState> openMenus = new HashMap<>();

    public SkillTreeGuiService(
            JavaPlugin plugin,
            LangService lang,
            PlayerDataStore dataStore,
            SkillService skillService,
            GameManager gameManager
    ) {
        this.plugin = plugin;
        this.lang = lang;
        this.dataStore = dataStore;
        this.skillService = skillService;
        this.gameManager = gameManager;
    }

    public void open(Player player) {
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (profile.getSelectedSkillTree() == null) {
            openTreeMenu(player);
            return;
        }
        openSkillMenu(player, profile.getSelectedSkillTree());
    }

    public boolean handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return false;
        }
        MenuState state = openMenus.get(player.getUniqueId());
        if (state == null) {
            return false;
        }
        if (!event.getView().getTitle().equals(state.title())) {
            return false;
        }
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= state.inventorySize()) {
            return true;
        }

        String action = state.actionsBySlot().get(event.getRawSlot());
        if (action == null || action.isBlank()) {
            return true;
        }
        if (action.equals("close")) {
            player.closeInventory();
            return true;
        }
        if (action.equals("reset")) {
            skillService.resetProgression(dataStore.getProfile(player.getUniqueId()));
            gameManager.refreshPlayerStats(player);
            player.sendMessage(lang.msg("skills.reset"));
            Bukkit.getScheduler().runTask(plugin, () -> openTreeMenu(player));
            return true;
        }
        if (action.equals("back")) {
            Bukkit.getScheduler().runTask(plugin, () -> openTreeMenu(player));
            return true;
        }

        if (action.startsWith("tree:")) {
            String treeId = action.substring("tree:".length()).toLowerCase(Locale.ROOT);
            PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
            if (!skillService.getTrees().containsKey(treeId)) {
                player.sendMessage(lang.msg("skills.tree-not-found"));
                return true;
            }
            String selected = profile.getSelectedSkillTree();
            if (selected != null && !selected.equalsIgnoreCase(treeId)) {
                player.sendMessage(lang.msg("skills.tree-locked"));
                return true;
            }
            if (!skillService.selectTree(profile, treeId)) {
                player.sendMessage(lang.msg("skills.tree-locked"));
                return true;
            }
            player.sendMessage(lang.msg("skills.tree-selected", Map.of("tree", treeId)));
            Bukkit.getScheduler().runTask(plugin, () -> openSkillMenu(player, treeId));
            return true;
        }

        if (action.startsWith("upgrade:")) {
            String skillId = action.substring("upgrade:".length()).toLowerCase(Locale.ROOT);
            PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
            SkillService.UpgradeResult result = skillService.upgrade(profile, skillId);
            switch (result) {
                case SUCCESS -> {
                    int level = profile.getSkillLevel(skillId);
                    player.sendMessage(lang.msg("skills.upgraded", Map.of(
                            "skill", skillId,
                            "level", String.valueOf(level),
                            "points", String.valueOf(profile.getSkillPoints())
                    )));
                    gameManager.refreshPlayerStats(player);
                    Bukkit.getScheduler().runTask(plugin, () -> openSkillMenu(player, profile.getSelectedSkillTree()));
                }
                case NO_SUCH_SKILL -> player.sendMessage(lang.msg("skills.no-such-skill"));
                case NOT_ENOUGH_POINTS -> player.sendMessage(lang.msg("skills.not-enough-points"));
                case MAXED -> player.sendMessage(lang.msg("skills.maxed"));
            }
            return true;
        }
        return true;
    }

    public boolean isMenuView(Player player, String title) {
        MenuState state = openMenus.get(player.getUniqueId());
        return state != null && state.title().equals(title);
    }

    public void handleClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        MenuState state = openMenus.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (event.getView().getTitle().equals(state.title())) {
            openMenus.remove(player.getUniqueId());
        }
    }

    private void openTreeMenu(Player player) {
        String title = lang.msg("skills.gui.tree-title");
        Inventory inventory = Bukkit.createInventory(null, 27, title);
        Map<Integer, String> actions = new HashMap<>();
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        String selected = profile.getSelectedSkillTree();

        List<SkillTree> trees = skillService.getTrees().values().stream()
                .sorted(Comparator.comparing(SkillTree::id))
                .toList();

        int slot = 10;
        for (SkillTree tree : trees) {
            if (slot >= 17) {
                break;
            }
            boolean isSelected = selected != null && selected.equalsIgnoreCase(tree.id());
            inventory.setItem(slot, buildTreeItem(tree, isSelected, selected != null && !isSelected));
            actions.put(slot, "tree:" + tree.id());
            slot++;
        }

        inventory.setItem(22, button(Material.BARRIER, lang.msg("skills.gui.reset-button")));
        actions.put(22, "reset");
        inventory.setItem(26, button(Material.OAK_DOOR, lang.msg("skills.gui.close-button")));
        actions.put(26, "close");

        openMenus.put(player.getUniqueId(), new MenuState(title, inventory.getSize(), actions));
        player.openInventory(inventory);
    }

    private void openSkillMenu(Player player, String treeId) {
        SkillTree tree = skillService.getTrees().get(treeId == null ? "" : treeId.toLowerCase(Locale.ROOT));
        if (tree == null) {
            openTreeMenu(player);
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        String title = lang.msg("skills.gui.skills-title", Map.of(
                "tree", tree.displayName(),
                "points", String.valueOf(profile.getSkillPoints())
        ));
        Inventory inventory = Bukkit.createInventory(null, 54, title);
        Map<Integer, String> actions = new HashMap<>();

        List<SkillDefinition> skills = new ArrayList<>(tree.skills().values());
        skills.sort(Comparator.comparing(SkillDefinition::id));
        int slot = 10;
        for (SkillDefinition skill : skills) {
            if (slot >= 44) {
                break;
            }
            int level = profile.getSkillLevel(skill.id());
            inventory.setItem(slot, buildSkillItem(skill, level));
            actions.put(slot, "upgrade:" + skill.id());
            slot++;
            if (slot % 9 == 8) {
                slot += 2;
            }
        }

        inventory.setItem(45, button(Material.ARROW, lang.msg("skills.gui.back-button")));
        actions.put(45, "back");
        inventory.setItem(49, button(Material.BARRIER, lang.msg("skills.gui.reset-button")));
        actions.put(49, "reset");
        inventory.setItem(53, button(Material.OAK_DOOR, lang.msg("skills.gui.close-button")));
        actions.put(53, "close");

        openMenus.put(player.getUniqueId(), new MenuState(title, inventory.getSize(), actions));
        player.openInventory(inventory);
    }

    private ItemStack buildTreeItem(SkillTree tree, boolean selected, boolean locked) {
        ItemStack stack = new ItemStack(selected ? Material.EMERALD : Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(TextService.colorize(tree.displayName()));
        List<String> lore = new ArrayList<>();
        lore.add(lang.msg("skills.gui.tree-id", Map.of("id", tree.id())));
        lore.add(selected ? lang.msg("skills.gui.tree-selected") : lang.msg("skills.gui.tree-click-select"));
        if (locked) {
            lore.add(lang.msg("skills.gui.tree-locked"));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildSkillItem(SkillDefinition skill, int currentLevel) {
        ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(TextService.colorize(skill.displayName()));
        int nextCost = currentLevel >= skill.maxLevel() ? -1 : skill.costForLevel(currentLevel + 1);
        List<String> lore = new ArrayList<>();
        lore.add(TextService.colorize("&#CFD8DC" + skill.description()));
        lore.add(TextService.colorize("&#FFD54FLevel: &#FFFFFF" + currentLevel + "/" + skill.maxLevel()));
        lore.add(nextCost > 0
                ? TextService.colorize("&#81D4FANext Cost: &#FFFFFF" + nextCost)
                : TextService.colorize("&#A5D6A7Max Level"));
        lore.add(TextService.colorize("&#F8BBD0Click to upgrade"));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack button(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private record MenuState(String title, int inventorySize, Map<Integer, String> actionsBySlot) {
    }
}
