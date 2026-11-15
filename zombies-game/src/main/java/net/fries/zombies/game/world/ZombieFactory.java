package net.fries.zombies.game.world;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.ZombieArchetypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Real provider boundary: alternative render/entity adapters may register before catalog use. */
public final class ZombieFactory {
    @FunctionalInterface
    public interface Provider {
        Zombie create(Location at);
    }

    private final Map<NamespacedId, Provider> providers;

    public ZombieFactory(Map<NamespacedId, Provider> providers) {
        this.providers = Map.copyOf(providers);
    }

    public ZombieFactory() {
        this(
                Map.of(
                        new NamespacedId("minecraft:zombie"),
                        at -> at.getWorld().spawn(at, Zombie.class)));
    }

    public Zombie spawn(Location at, ZombieArchetypes.Type type, ZombieArchetypes.Stats stats) {
        var provider = providers.get(type.provider());
        if (provider == null)
            throw new IllegalArgumentException("Unavailable zombie provider " + type.provider());
        var z = provider.create(at);
        z.setBaby(type.baby());
        z.setCanBreakDoors(false);
        z.setShouldBurnInDay(false);
        z.setCanPickupItems(false);
        z.setRemoveWhenFarAway(false);
        z.setPersistent(false);
        for (var key :
                List.of(
                        Attribute.MAX_HEALTH,
                        Attribute.ATTACK_DAMAGE,
                        Attribute.ARMOR,
                        Attribute.ARMOR_TOUGHNESS,
                        Attribute.MOVEMENT_SPEED)) {
            var attribute = Objects.requireNonNull(z.getAttribute(key));
            attribute.getModifiers().forEach(attribute::removeModifier);
        }
        z.getEquipment().clear();
        if (!type.equipment().equals("NONE")) {
            String prefix = type.equipment();
            var gear = z.getEquipment();
            gear.setHelmet(armour(prefix + "_HELMET"));
            gear.setChestplate(armour(prefix + "_CHESTPLATE"));
            gear.setLeggings(armour(prefix + "_LEGGINGS"));
            gear.setBoots(armour(prefix + "_BOOTS"));
            gear.setHelmetDropChance(0);
            gear.setChestplateDropChance(0);
            gear.setLeggingsDropChance(0);
            gear.setBootsDropChance(0);
        }
        // Protection is the configured total, not an accidental sum of random equipment modifiers.
        Objects.requireNonNull(z.getAttribute(Attribute.ARMOR)).setBaseValue(type.armour());
        Objects.requireNonNull(z.getAttribute(Attribute.ARMOR_TOUGHNESS)).setBaseValue(0);
        Objects.requireNonNull(z.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(stats.health());
        z.setHealth(stats.health());
        Objects.requireNonNull(z.getAttribute(Attribute.MOVEMENT_SPEED))
                .setBaseValue(stats.speed());
        Objects.requireNonNull(z.getAttribute(Attribute.ATTACK_DAMAGE))
                .setBaseValue(stats.damage());
        Objects.requireNonNull(z.getAttribute(Attribute.FOLLOW_RANGE)).setBaseValue(100);
        if (type.elite()) {
            z.customName(Component.text(type.name(), NamedTextColor.RED));
            z.setCustomNameVisible(true);
            z.setGlowing(true);
        }
        z.getPersistentDataContainer()
                .set(
                        new NamespacedKey("friesnetzombies", "archetype"),
                        PersistentDataType.STRING,
                        type.id().value());
        z.getPersistentDataContainer()
                .set(
                        new NamespacedKey("friesnetzombies", "asset"),
                        PersistentDataType.STRING,
                        type.asset().value());
        return z;
    }

    private static ItemStack armour(String material) {
        var item = new ItemStack(Material.valueOf(material));
        item.setData(
                io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS,
                io.papermc.paper.datacomponent.item.ItemAttributeModifiers.itemAttributes()
                        .build());
        return item;
    }
}
