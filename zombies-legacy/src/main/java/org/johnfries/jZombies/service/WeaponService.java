package org.johnfries.jZombies.service;

import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
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
    private final NamespacedKey clipAmmoKey;
    private final NamespacedKey reserveAmmoKey;
    private final NamespacedKey packAPunchKey;
    private final Map<String, WeaponDefinition> weapons = new HashMap<>();
    private final Map<String, WeaponSkin> skins = new HashMap<>();

    public WeaponService(JavaPlugin plugin, Map<String, WeaponDefinition> weapons, Map<String, WeaponSkin> skins) {
        this.weaponIdKey = new NamespacedKey(plugin, "weapon_id");
        this.skinIdKey = new NamespacedKey(plugin, "weapon_skin_id");
        this.clipAmmoKey = new NamespacedKey(plugin, "weapon_clip_ammo");
        this.reserveAmmoKey = new NamespacedKey(plugin, "weapon_reserve_ammo");
        this.packAPunchKey = new NamespacedKey(plugin, "weapon_pack_a_punch");
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
        return buildWeaponItem(profile, definition, definition.clipSize(), definition.maxReserveAmmo(), false);
    }

    private ItemStack buildWeaponItem(PlayerProfile profile, WeaponDefinition definition, int clip, int reserve, boolean packAPunch) {
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
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(weaponIdKey, PersistentDataType.STRING, definition.id());
        meta.getPersistentDataContainer().set(clipAmmoKey, PersistentDataType.INTEGER, Math.max(0, clip));
        meta.getPersistentDataContainer().set(reserveAmmoKey, PersistentDataType.INTEGER, Math.max(0, reserve));
        meta.getPersistentDataContainer().set(packAPunchKey, PersistentDataType.INTEGER, packAPunch ? 1 : 0);

        String display = packAPunch
                ? "&#FF4DD2" + definition.displayName() + " &#FFD700✦"
                : definition.displayName();
        meta.setDisplayName(TextService.colorize(display));
        updateLore(meta, definition, clip, reserve);
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

    public boolean isWeapon(ItemStack stack) {
        return getWeaponId(stack) != null;
    }

    public int getClipAmmo(ItemStack stack) {
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer value = meta.getPersistentDataContainer().get(clipAmmoKey, PersistentDataType.INTEGER);
        return value == null ? 0 : Math.max(0, value);
    }

    public int getReserveAmmo(ItemStack stack) {
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer value = meta.getPersistentDataContainer().get(reserveAmmoKey, PersistentDataType.INTEGER);
        return value == null ? 0 : Math.max(0, value);
    }

    public boolean isPackAPunched(ItemStack stack) {
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Integer value = meta.getPersistentDataContainer().get(packAPunchKey, PersistentDataType.INTEGER);
        return value != null && value == 1;
    }

    public void setAmmo(ItemStack stack, int clip, int reserve) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        String weaponId = getWeaponId(stack);
        if (weaponId == null) {
            return;
        }
        WeaponDefinition definition = weapons.get(weaponId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int maxClip = Math.max(1, definition.clipSize());
        int maxReserve = getMaxReserve(definition, isPackAPunched(stack));
        int clampedClip = Math.max(0, Math.min(maxClip, clip));
        int clampedReserve = Math.max(0, Math.min(maxReserve, reserve));
        pdc.set(clipAmmoKey, PersistentDataType.INTEGER, clampedClip);
        pdc.set(reserveAmmoKey, PersistentDataType.INTEGER, clampedReserve);
        updateLore(meta, definition, clampedClip, clampedReserve);
        stack.setItemMeta(meta);
    }

    public void consumeAmmo(ItemStack stack, int amount) {
        int clip = getClipAmmo(stack);
        int reserve = getReserveAmmo(stack);
        setAmmo(stack, clip - amount, reserve);
    }

    public int reload(ItemStack stack, double reserveMultiplier) {
        String weaponId = getWeaponId(stack);
        if (weaponId == null) {
            return 0;
        }
        WeaponDefinition definition = weapons.get(weaponId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return 0;
        }
        int clip = getClipAmmo(stack);
        int reserve = getReserveAmmo(stack);
        int maxClip = definition.clipSize();
        if (clip >= maxClip || reserve <= 0) {
            return 0;
        }
        int needed = maxClip - clip;
        int used = Math.min(needed, reserve);
        int newClip = clip + used;
        int newReserve = reserve - used;
        int maxReserve = (int) Math.round(getMaxReserve(definition, isPackAPunched(stack)) * reserveMultiplier);
        newReserve = Math.min(newReserve, Math.max(definition.maxReserveAmmo(), maxReserve));
        setAmmo(stack, newClip, newReserve);
        return used;
    }

    public int refillAmmo(ItemStack stack, double reserveMultiplier) {
        String weaponId = getWeaponId(stack);
        if (weaponId == null) {
            return 0;
        }
        WeaponDefinition definition = weapons.get(weaponId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return 0;
        }
        int maxReserve = (int) Math.round(getMaxReserve(definition, isPackAPunched(stack)) * reserveMultiplier);
        setAmmo(stack, definition.clipSize(), maxReserve);
        return maxReserve;
    }

    public boolean applyPackAPunch(PlayerProfile profile, Player player, ItemStack stack, double damageAmmoMultiplier) {
        String weaponId = getWeaponId(stack);
        if (weaponId == null) {
            return false;
        }
        WeaponDefinition definition = weapons.get(weaponId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return false;
        }
        if (isPackAPunched(stack)) {
            return false;
        }
        int clip = Math.max(1, (int) Math.round(definition.clipSize() * damageAmmoMultiplier));
        int reserve = Math.max(definition.maxReserveAmmo(), (int) Math.round(definition.maxReserveAmmo() * damageAmmoMultiplier));
        ItemStack upgraded = buildWeaponItem(profile, definition, clip, reserve, true);
        player.getInventory().setItem(player.getInventory().getHeldItemSlot(), upgraded);
        return true;
    }

    public double getDamage(WeaponDefinition definition, boolean packAPunch, double vendingMultiplier) {
        double pap = packAPunch ? 1.35D : 1.0D;
        return definition.damage() * pap * vendingMultiplier;
    }

    public int countWeapons(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isWeapon(stack)) {
                count++;
            }
        }
        return count;
    }

    public boolean hasWeapon(Player player, String weaponId) {
        String normalized = weaponId.toLowerCase(Locale.ROOT);
        for (ItemStack stack : player.getInventory().getContents()) {
            String id = getWeaponId(stack);
            if (id != null && id.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    public void playShootSound(Player player, WeaponDefinition definition) {
        Sound sound;
        try {
            sound = Sound.valueOf(definition.shootSound().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sound = Sound.ENTITY_FIREWORK_ROCKET_BLAST;
        }
        player.getWorld().playSound(player.getLocation(), sound, 0.9F, 1.2F);
    }

    public void playReloadSound(Player player, WeaponDefinition definition) {
        Sound sound;
        try {
            sound = Sound.valueOf(definition.reloadSound().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sound = Sound.ITEM_ARMOR_EQUIP_IRON;
        }
        player.getWorld().playSound(player.getLocation(), sound, 0.9F, 1.0F);
    }

    private int getMaxReserve(WeaponDefinition definition, boolean packAPunch) {
        return packAPunch ? (int) Math.round(definition.maxReserveAmmo() * 1.25D) : definition.maxReserveAmmo();
    }

    private void updateLore(ItemMeta meta, WeaponDefinition definition, int clip, int reserve) {
        meta.setLore(List.of(
                TextService.colorize("&#90CAF9Right click to shoot"),
                TextService.colorize("&#FFD54FClick to reload"),
                TextService.colorize("&#A5D6A7Ammo " + clip + "/" + (clip + reserve))
        ));
    }
}
