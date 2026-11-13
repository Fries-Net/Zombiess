package org.johnfries.jZombies.model;

import org.bukkit.Material;

public record WeaponDefinition(
        String id,
        String displayName,
        Material material,
        int price,
        int defaultCustomModelData,
        int clipSize,
        int maxReserveAmmo,
        double damage,
        double headshotMultiplier,
        int pellets,
        double spread,
        double range,
        int fireCooldownTicks,
        int reloadTicks,
        boolean lootBoxWeapon,
        int lootWeight,
        String shootSound,
        String reloadSound
) {
}
