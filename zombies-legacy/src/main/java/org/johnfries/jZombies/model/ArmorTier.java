package org.johnfries.jZombies.model;

import org.bukkit.Material;

public record ArmorTier(
        String id,
        String displayName,
        Material helmet,
        Material chestplate,
        Material leggings,
        Material boots,
        int price
) {
}
