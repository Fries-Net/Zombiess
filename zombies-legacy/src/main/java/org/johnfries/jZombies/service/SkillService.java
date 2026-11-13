package org.johnfries.jZombies.service;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.model.SkillDefinition;
import org.johnfries.jZombies.model.SkillTree;
import org.johnfries.jZombies.model.SkillType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SkillService {
    private final Map<String, SkillTree> trees = new HashMap<>();

    public SkillService(Map<String, SkillTree> trees) {
        setTrees(trees);
    }

    public void setTrees(Map<String, SkillTree> updatedTrees) {
        trees.clear();
        trees.putAll(updatedTrees);
    }

    public Map<String, SkillTree> getTrees() {
        return trees;
    }

    public boolean selectTree(PlayerProfile profile, String treeId) {
        String normalized = treeId.toLowerCase(Locale.ROOT);
        if (!trees.containsKey(normalized)) {
            return false;
        }
        if (profile.getSelectedSkillTree() == null) {
            profile.setSelectedSkillTree(normalized);
            return true;
        }
        return profile.getSelectedSkillTree().equalsIgnoreCase(normalized);
    }

    public void resetProgression(PlayerProfile profile) {
        profile.resetSkills();
    }

    public Optional<SkillDefinition> getSkill(PlayerProfile profile, String skillId) {
        String selected = profile.getSelectedSkillTree();
        if (selected == null) {
            return Optional.empty();
        }
        SkillTree tree = trees.get(selected.toLowerCase(Locale.ROOT));
        if (tree == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tree.skills().get(skillId.toLowerCase(Locale.ROOT)));
    }

    public UpgradeResult upgrade(PlayerProfile profile, String skillId) {
        Optional<SkillDefinition> optional = getSkill(profile, skillId);
        if (optional.isEmpty()) {
            return UpgradeResult.NO_SUCH_SKILL;
        }
        SkillDefinition definition = optional.get();
        int current = profile.getSkillLevel(definition.id());
        if (current >= definition.maxLevel()) {
            return UpgradeResult.MAXED;
        }
        int next = current + 1;
        int cost = definition.costForLevel(next);
        if (profile.getSkillPoints() < cost) {
            return UpgradeResult.NOT_ENOUGH_POINTS;
        }
        profile.removeSkillPoints(cost);
        profile.setSkillLevel(definition.id(), next);
        return UpgradeResult.SUCCESS;
    }

    public double getModifier(PlayerProfile profile, SkillType type) {
        String selected = profile.getSelectedSkillTree();
        if (selected == null) {
            return 0.0D;
        }
        SkillTree tree = trees.get(selected.toLowerCase(Locale.ROOT));
        if (tree == null) {
            return 0.0D;
        }
        double total = 0.0D;
        for (SkillDefinition definition : tree.skills().values()) {
            if (definition.type() != type) {
                continue;
            }
            int level = profile.getSkillLevel(definition.id());
            total += level * definition.valuePerLevel();
        }
        return total;
    }

    public int getWeaponPrice(PlayerProfile profile, int basePrice) {
        double discount = getModifier(profile, SkillType.WEAPON_DISCOUNT);
        double multiplier = Math.max(0.35D, 1.0D - discount);
        return Math.max(1, (int) Math.round(basePrice * multiplier));
    }

    public int getKillReward(PlayerProfile profile, int baseReward) {
        double bonus = getModifier(profile, SkillType.COIN_GAIN);
        return Math.max(1, (int) Math.round(baseReward * (1.0D + bonus)));
    }

    public double getDamageMultiplier(PlayerProfile profile) {
        return 1.0D + getModifier(profile, SkillType.DAMAGE_BOOST);
    }

    public double getCritChance(PlayerProfile profile) {
        return getModifier(profile, SkillType.CRIT_CHANCE);
    }

    public double getReloadMultiplier(PlayerProfile profile) {
        double buff = getModifier(profile, SkillType.RELOAD_SPEED);
        return Math.max(0.45D, 1.0D - buff);
    }

    public double getReviveDurationSeconds(PlayerProfile profile, int baseSeconds) {
        double speedBonus = getModifier(profile, SkillType.REVIVE_SPEED);
        return Math.max(1.25D, baseSeconds / (1.0D + speedBonus));
    }

    public double getBarricadeDurationTicks(PlayerProfile profile, int baseTicks) {
        double speedBonus = getModifier(profile, SkillType.BARRICADE_SPEED);
        return Math.max(5.0D, baseTicks / (1.0D + speedBonus));
    }

    public void applyHealthModifier(Player player, PlayerProfile profile) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return;
        }
        double extraHealth = getModifier(profile, SkillType.EXTRA_HEALTH);
        double value = Math.max(20.0D, 20.0D + extraHealth);
        attribute.setBaseValue(value);
        if (player.getHealth() > value) {
            player.setHealth(value);
        }
    }

    public enum UpgradeResult {
        SUCCESS,
        NO_SUCH_SKILL,
        NOT_ENOUGH_POINTS,
        MAXED
    }
}
