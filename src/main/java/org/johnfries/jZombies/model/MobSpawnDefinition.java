package org.johnfries.jZombies.model;

import org.bukkit.entity.EntityType;

public record MobSpawnDefinition(
        String id,
        EntityType entityType,
        String mythicMobId,
        int amount,
        double health,
        double speedMultiplier,
        double damage,
        int killReward
) {

    public boolean isMythic() {
        return mythicMobId != null && !mythicMobId.isBlank();
    }
}
