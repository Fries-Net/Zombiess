package org.johnfries.jZombies.service;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.model.WeaponDefinition;
import org.johnfries.jZombies.model.WeaponSkin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WeaponService {
    private final NamespacedKey weaponIdKey;
    private final NamespacedKey skinIdKey;
    private final Map<String, WeaponDefinition> weapons = new HashMap<>();
    private final Map<String, WeaponSkin> skins = new HashMap<>();

    public WeaponService(JavaPlugin plugin, Map<String, WeaponDefinition> weapons, Map<String, WeaponSkin> skins) {
        this.weaponIdKey = new NamespacedKey(plugin, "weapon_id");
        this.skinIdKey = new NamespacedKey(plugin, "weapon_skin_id");
        setCatalog(weapons, skins);
    }

    public void setCatalog(Map<String, WeaponDefinition> updatedWeapons, Map<String, WeaponSkin> updatedSkins) {
        weapons.clear();
        skins.clear();
        weapons.putAll(updatedWeapons);
        skins.putAll(updatedSkins);
    }

    public Map<String, WeaponDefinition> getWeapons() {
        return weapons;
    }

    public Map<String, WeaponSkin> getSkins() {
        return skins;
    }

    public Optional<WeaponDefinition> getWeapon(String weaponId) {
        return Optional.ofNullable(weapons.get(weaponId.toLowerCase(Locale.ROOT)));
    }

    public Optional<WeaponSkin> getSkin(String skinId) {
        return Optional.ofNullable(skins.get(skinId.toLowerCase(Locale.ROOT)));
    }

    public List<WeaponSkin> getSkinsForWeapon(String weaponId) {
        String normalized = weaponId.toLowerCase(Locale.ROOT);
        return skins.values().stream().filter(skin -> skin.canApplyTo(normalized)).toList();
    }

    public ItemStack createWeaponItem(PlayerProfile profile, String weaponId) {
        WeaponDefinition definition = weapons.get(weaponId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return null;
        }

        ItemStack stack = new ItemStack(definition.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        String appliedSkinId = profile.getEquippedSkin(definition.id());
        WeaponSkin skin = appliedSkinId == null ? null : skins.get(appliedSkinId.toLowerCase(Locale.ROOT));
        int modelData = definition.defaultCustomModelData();

        if (skin != null && skin.canApplyTo(definition.id())) {
            modelData = skin.customModelData();
            meta.getPersistentDataContainer().set(skinIdKey, PersistentDataType.STRING, skin.id());
        }
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }

        meta.getPersistentDataContainer().set(weaponIdKey, PersistentDataType.STRING, definition.id());
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', definition.displayName()));
        meta.setLore(List.of(ChatColor.GRAY + "jZombies Weapon"));
        stack.setItemMeta(meta);
        return stack;
    }

    public String getWeaponId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().get(weaponIdKey, PersistentDataType.STRING);
    }
}
