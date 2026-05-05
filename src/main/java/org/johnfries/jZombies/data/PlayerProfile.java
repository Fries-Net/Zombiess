package org.johnfries.jZombies.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerProfile {
    private final UUID uuid;
    private int coins;
    private int skinTokens;
    private int skillPoints;
    private String selectedSkillTree;
    private final Map<String, Integer> skillLevels;
    private final Set<String> ownedSkins;
    private final Map<String, String> equippedSkins;

    public PlayerProfile(UUID uuid) {
        this.uuid = uuid;
        this.skillLevels = new HashMap<>();
        this.ownedSkins = new HashSet<>();
        this.equippedSkins = new HashMap<>();
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getCoins() {
        return coins;
    }

    public void setCoins(int coins) {
        this.coins = Math.max(0, coins);
    }

    public void addCoins(int amount) {
        setCoins(coins + amount);
    }

    public boolean removeCoins(int amount) {
        if (coins < amount) {
            return false;
        }
        coins -= amount;
        return true;
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public void setSkillPoints(int skillPoints) {
        this.skillPoints = Math.max(0, skillPoints);
    }

    public void addSkillPoints(int amount) {
        setSkillPoints(skillPoints + amount);
    }

    public boolean removeSkillPoints(int amount) {
        if (skillPoints < amount) {
            return false;
        }
        skillPoints -= amount;
        return true;
    }

    public int getSkinTokens() {
        return skinTokens;
    }

    public void setSkinTokens(int skinTokens) {
        this.skinTokens = Math.max(0, skinTokens);
    }

    public void addSkinTokens(int amount) {
        setSkinTokens(skinTokens + amount);
    }

    public boolean removeSkinTokens(int amount) {
        if (skinTokens < amount) {
            return false;
        }
        skinTokens -= amount;
        return true;
    }

    public String getSelectedSkillTree() {
        return selectedSkillTree;
    }

    public void setSelectedSkillTree(String selectedSkillTree) {
        this.selectedSkillTree = selectedSkillTree;
    }

    public Map<String, Integer> getSkillLevels() {
        return skillLevels;
    }

    public int getSkillLevel(String skillId) {
        return skillLevels.getOrDefault(skillId, 0);
    }

    public void setSkillLevel(String skillId, int level) {
        if (level <= 0) {
            skillLevels.remove(skillId);
            return;
        }
        skillLevels.put(skillId, level);
    }

    public void resetSkills() {
        selectedSkillTree = null;
        skillLevels.clear();
        skillPoints = 0;
    }

    public Set<String> getOwnedSkins() {
        return ownedSkins;
    }

    public boolean ownsSkin(String skinId) {
        return ownedSkins.contains(skinId);
    }

    public void grantSkin(String skinId) {
        ownedSkins.add(skinId);
    }

    public Map<String, String> getEquippedSkins() {
        return equippedSkins;
    }

    public String getEquippedSkin(String weaponId) {
        return equippedSkins.get(weaponId);
    }

    public void equipSkin(String weaponId, String skinId) {
        equippedSkins.put(weaponId, skinId);
    }

    public void clearEquippedSkin(String weaponId) {
        equippedSkins.remove(weaponId);
    }
}
