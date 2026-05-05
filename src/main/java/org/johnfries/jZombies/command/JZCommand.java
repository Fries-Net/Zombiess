package org.johnfries.jZombies.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.game.GameManager;
import org.johnfries.jZombies.model.SkillDefinition;
import org.johnfries.jZombies.model.SkillTree;
import org.johnfries.jZombies.model.WeaponDefinition;
import org.johnfries.jZombies.model.WeaponSkin;
import org.johnfries.jZombies.service.SkillService;
import org.johnfries.jZombies.service.WeaponService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class JZCommand {
    private final ConfigService configService;
    private final LangService lang;
    private final GameManager gameManager;
    private final PlayerDataStore dataStore;
    private final SkillService skillService;
    private final WeaponService weaponService;
    private final Runnable reloadAction;

    public JZCommand(
            ConfigService configService,
            LangService lang,
            GameManager gameManager,
            PlayerDataStore dataStore,
            SkillService skillService,
            WeaponService weaponService,
            Runnable reloadAction
    ) {
        this.configService = configService;
        this.lang = lang;
        this.gameManager = gameManager;
        this.dataStore = dataStore;
        this.skillService = skillService;
        this.weaponService = weaponService;
        this.reloadAction = reloadAction;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage(lang.msg("general.no-permission"));
                    return true;
                }
                gameManager.startGame();
                return true;
            }
            case "stop" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage(lang.msg("general.no-permission"));
                    return true;
                }
                gameManager.stopGame();
                return true;
            }
            case "nextwave" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage(lang.msg("general.no-permission"));
                    return true;
                }
                gameManager.forceNextWave();
                sender.sendMessage(lang.msg("game.next-wave-forced"));
                return true;
            }
            case "reload" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage(lang.msg("general.no-permission"));
                    return true;
                }
                reloadAction.run();
                sender.sendMessage(lang.msg("general.reloaded"));
                return true;
            }
            case "weapons" -> {
                sender.sendMessage(lang.msg("weapon.list-header"));
                List<WeaponDefinition> definitions = weaponService.getWeapons().values().stream()
                        .sorted(Comparator.comparing(WeaponDefinition::id))
                        .toList();
                for (WeaponDefinition weapon : definitions) {
                    sender.sendMessage(lang.msg("weapon.list-line", Map.of(
                            "id", weapon.id(),
                            "display", weapon.displayName()
                    )));
                }
                return true;
            }
            case "buy" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.msg("general.player-only"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(lang.msg("weapon.buy-usage"));
                    return true;
                }
                gameManager.buyWeapon(player, args[1].toLowerCase(Locale.ROOT));
                return true;
            }
            case "stats" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.msg("general.player-only"));
                    return true;
                }
                PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
                String selectedTree = profile.getSelectedSkillTree() == null ? lang.msg("general.none-value") : profile.getSelectedSkillTree();
                sender.sendMessage(lang.msg("player.stats", Map.of(
                        "coins", String.valueOf(profile.getCoins()),
                        "tokens", String.valueOf(profile.getSkinTokens()),
                        "skillPoints", String.valueOf(profile.getSkillPoints()),
                        "tree", selectedTree
                )));
                return true;
            }
            case "skin", "skins" -> {
                return handleSkin(sender, args);
            }
            case "skills" -> {
                return handleSkills(sender, args);
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private boolean handleSkin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("general.player-only"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(lang.msg("skin.usage"));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        switch (action) {
            case "list" -> {
                if (args.length < 3) {
                    sender.sendMessage(lang.msg("skin.list-usage"));
                    return true;
                }
                String weaponId = args[2].toLowerCase(Locale.ROOT);
                Optional<WeaponDefinition> optionalWeapon = weaponService.getWeapon(weaponId);
                if (optionalWeapon.isEmpty()) {
                    sender.sendMessage(lang.msg("weapon.not-found"));
                    return true;
                }
                sender.sendMessage(lang.msg("skin.list-header", Map.of("weapon", weaponId)));
                List<WeaponSkin> skins = weaponService.getSkinsForWeapon(weaponId);
                if (skins.isEmpty()) {
                    sender.sendMessage(lang.msg("skin.none-for-weapon"));
                    return true;
                }
                for (WeaponSkin skin : skins.stream().sorted(Comparator.comparing(WeaponSkin::id)).toList()) {
                    boolean owned = profile.ownsSkin(skin.id());
                    String line = owned
                            ? lang.msg("skin.list-line-owned", Map.of("id", skin.id(), "display", skin.displayName()))
                            : lang.msg("skin.list-line-price", Map.of(
                            "id", skin.id(),
                            "display", skin.displayName(),
                            "price", String.valueOf(skin.price())
                    ));
                    sender.sendMessage(line);
                }
                return true;
            }
            case "buy" -> {
                if (args.length < 3) {
                    sender.sendMessage(lang.msg("skin.buy-usage"));
                    return true;
                }
                String skinId = args[2].toLowerCase(Locale.ROOT);
                Optional<WeaponSkin> optional = weaponService.getSkin(skinId);
                if (optional.isEmpty()) {
                    sender.sendMessage(lang.msg("skin.not-found"));
                    return true;
                }
                WeaponSkin skin = optional.get();
                if (profile.ownsSkin(skin.id())) {
                    sender.sendMessage(lang.msg("skin.already-owned"));
                    return true;
                }
                if (!profile.removeSkinTokens(skin.price())) {
                    sender.sendMessage(lang.msg("skin.not-enough-tokens", Map.of(
                            "price", String.valueOf(skin.price()),
                            "tokens", String.valueOf(profile.getSkinTokens())
                    )));
                    return true;
                }
                profile.grantSkin(skin.id());
                sender.sendMessage(lang.msg("skin.bought", Map.of(
                        "skin", skin.displayName(),
                        "tokens", String.valueOf(profile.getSkinTokens())
                )));
                return true;
            }
            case "equip" -> {
                if (args.length < 4) {
                    sender.sendMessage(lang.msg("skin.equip-usage"));
                    return true;
                }
                if (!gameManager.isPreGame()) {
                    sender.sendMessage(lang.msg("skin.pregame-only"));
                    return true;
                }
                String weaponId = args[2].toLowerCase(Locale.ROOT);
                String skinId = args[3].toLowerCase(Locale.ROOT);
                if ("default".equalsIgnoreCase(skinId)) {
                    profile.clearEquippedSkin(weaponId);
                    sender.sendMessage(lang.msg("skin.default-equipped", Map.of("weapon", weaponId)));
                    return true;
                }
                Optional<WeaponDefinition> weaponOptional = weaponService.getWeapon(weaponId);
                Optional<WeaponSkin> skinOptional = weaponService.getSkin(skinId);
                if (weaponOptional.isEmpty()) {
                    sender.sendMessage(lang.msg("weapon.not-found"));
                    return true;
                }
                if (skinOptional.isEmpty()) {
                    sender.sendMessage(lang.msg("skin.not-found"));
                    return true;
                }
                WeaponSkin skin = skinOptional.get();
                if (!skin.canApplyTo(weaponId)) {
                    sender.sendMessage(lang.msg("skin.weapon-not-allowed"));
                    return true;
                }
                if (!profile.ownsSkin(skin.id())) {
                    sender.sendMessage(lang.msg("skin.must-own"));
                    return true;
                }
                profile.equipSkin(weaponId, skin.id());
                sender.sendMessage(lang.msg("skin.equipped", Map.of(
                        "skin", skin.displayName(),
                        "weapon", weaponId
                )));
                return true;
            }
            default -> {
                sender.sendMessage(lang.msg("skin.usage"));
                return true;
            }
        }
    }

    private boolean handleSkills(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.msg("general.player-only"));
            return true;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (args.length == 1) {
            sender.sendMessage(lang.msg("skills.header", Map.of(
                    "tree", profile.getSelectedSkillTree() == null ? lang.msg("general.none-value") : profile.getSelectedSkillTree(),
                    "points", String.valueOf(profile.getSkillPoints())
            )));
            String treeId = profile.getSelectedSkillTree();
            if (treeId == null) {
                sender.sendMessage(lang.msg("skills.pick-tree"));
                listTrees(sender);
                return true;
            }
            SkillTree tree = skillService.getTrees().get(treeId);
            if (tree == null) {
                sender.sendMessage(lang.msg("skills.pick-tree"));
                listTrees(sender);
                return true;
            }
            for (SkillDefinition skill : tree.skills().values().stream().sorted(Comparator.comparing(SkillDefinition::id)).toList()) {
                int level = profile.getSkillLevel(skill.id());
                int nextCost = level >= skill.maxLevel() ? -1 : skill.costForLevel(level + 1);
                String line = nextCost > 0
                        ? lang.msg("skills.list-line", Map.of(
                        "id", skill.id(),
                        "level", String.valueOf(level),
                        "max", String.valueOf(skill.maxLevel()),
                        "cost", String.valueOf(nextCost)
                ))
                        : lang.msg("skills.list-line-max", Map.of(
                        "id", skill.id(),
                        "level", String.valueOf(level),
                        "max", String.valueOf(skill.maxLevel())
                ));
                sender.sendMessage(line);
            }
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "tree" -> {
                if (args.length < 3) {
                    sender.sendMessage(lang.msg("skills.tree-usage"));
                    listTrees(sender);
                    return true;
                }
                String treeId = args[2].toLowerCase(Locale.ROOT);
                if (!skillService.getTrees().containsKey(treeId)) {
                    sender.sendMessage(lang.msg("skills.tree-not-found"));
                    listTrees(sender);
                    return true;
                }
                String selected = profile.getSelectedSkillTree();
                if (selected != null && !selected.equalsIgnoreCase(treeId)) {
                    sender.sendMessage(lang.msg("skills.tree-locked"));
                    return true;
                }
                if (!skillService.selectTree(profile, treeId)) {
                    sender.sendMessage(lang.msg("skills.tree-locked"));
                    return true;
                }
                sender.sendMessage(lang.msg("skills.tree-selected", Map.of("tree", treeId)));
                return true;
            }
            case "upgrade" -> {
                if (args.length < 3) {
                    sender.sendMessage(lang.msg("skills.upgrade-usage"));
                    return true;
                }
                if (profile.getSelectedSkillTree() == null) {
                    sender.sendMessage(lang.msg("skills.pick-tree"));
                    return true;
                }
                String skillId = args[2].toLowerCase(Locale.ROOT);
                SkillService.UpgradeResult result = skillService.upgrade(profile, skillId);
                switch (result) {
                    case SUCCESS -> {
                        int level = profile.getSkillLevel(skillId);
                        sender.sendMessage(lang.msg("skills.upgraded", Map.of(
                                "skill", skillId,
                                "level", String.valueOf(level),
                                "points", String.valueOf(profile.getSkillPoints())
                        )));
                    }
                    case NO_SUCH_SKILL -> sender.sendMessage(lang.msg("skills.no-such-skill"));
                    case NOT_ENOUGH_POINTS -> sender.sendMessage(lang.msg("skills.not-enough-points"));
                    case MAXED -> sender.sendMessage(lang.msg("skills.maxed"));
                }
                gameManager.refreshPlayerStats(player);
                return true;
            }
            case "reset" -> {
                skillService.resetProgression(profile);
                gameManager.refreshPlayerStats(player);
                sender.sendMessage(lang.msg("skills.reset"));
                return true;
            }
            default -> {
                sender.sendMessage(lang.msg("skills.usage"));
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang.msg("command.help-title"));
        sender.sendMessage(lang.msg("command.help-admin"));
        sender.sendMessage(lang.msg("command.help-stats"));
        sender.sendMessage(lang.msg("command.help-weapons"));
        sender.sendMessage(lang.msg("command.help-buy"));
        sender.sendMessage(lang.msg("command.help-skin-list"));
        sender.sendMessage(lang.msg("command.help-skin-buy"));
        sender.sendMessage(lang.msg("command.help-skin-equip"));
        sender.sendMessage(lang.msg("command.help-skills"));
        sender.sendMessage(lang.msg("command.help-skill-tree"));
        sender.sendMessage(lang.msg("command.help-skill-upgrade"));
        sender.sendMessage(lang.msg("command.help-skill-reset"));
    }

    private void listTrees(CommandSender sender) {
        sender.sendMessage(lang.msg("skills.available-trees"));
        for (String treeId : skillService.getTrees().keySet().stream().sorted().toList()) {
            SkillTree tree = skillService.getTrees().get(treeId);
            if (tree == null) {
                continue;
            }
            sender.sendMessage(lang.msg("skills.tree-line", Map.of(
                    "id", tree.id(),
                    "display", tree.displayName()
            )));
        }
    }

    private boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission("jzombies.admin") || ! (sender instanceof Player);
    }

    public List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return prefix(args[0], List.of("start", "stop", "nextwave", "reload", "stats", "weapons", "buy", "skin", "skills"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            return prefix(args[1], weaponService.getWeapons().keySet().stream().sorted().toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("skins"))) {
            return prefix(args[1], List.of("list", "buy", "equip"));
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("skins")) && args[1].equalsIgnoreCase("list")) {
            return prefix(args[2], weaponService.getWeapons().keySet().stream().sorted().toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("skins")) && args[1].equalsIgnoreCase("buy")) {
            return prefix(args[2], weaponService.getSkins().keySet().stream().sorted().toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("skins")) && args[1].equalsIgnoreCase("equip")) {
            return prefix(args[2], weaponService.getWeapons().keySet().stream().sorted().toList());
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("skins")) && args[1].equalsIgnoreCase("equip")) {
            String weaponId = args[2].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            options.add("default");
            options.addAll(weaponService.getSkinsForWeapon(weaponId).stream().map(WeaponSkin::id).sorted().toList());
            return prefix(args[3], options);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("skills")) {
            return prefix(args[1], List.of("tree", "upgrade", "reset"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("tree")) {
            return prefix(args[2], skillService.getTrees().keySet().stream().sorted().toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("upgrade")) {
            if (!(sender instanceof Player player)) {
                return List.of();
            }
            PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
            String treeId = profile.getSelectedSkillTree();
            if (treeId == null) {
                return List.of();
            }
            SkillTree tree = skillService.getTrees().get(treeId);
            if (tree == null) {
                return List.of();
            }
            return prefix(args[2], tree.skills().keySet().stream().sorted().toList());
        }
        return List.of();
    }

    public String permission() {
        return "jzombies.use";
    }

    private List<String> prefix(String entered, List<String> values) {
        String prefix = entered.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
