package org.johnfries.jZombies.model;

import org.bukkit.Material;

public record WeaponDefinition(
        String id,
        String displayName,
        Material material,
        int price,
        int defaultCustomModelData
) {
}
