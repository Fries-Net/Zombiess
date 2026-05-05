package org.johnfries.jZombies.model;

public record SkillDefinition(
        String id,
        String displayName,
        String description,
        SkillType type,
        int maxLevel,
        int baseCost,
        double valuePerLevel
) {

    public int costForLevel(int nextLevel) {
        return baseCost * nextLevel;
    }
}
