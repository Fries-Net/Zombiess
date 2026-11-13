package org.johnfries.jZombies.integration;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class MythicMobsBridge {
    private final JavaPlugin plugin;
    private boolean enabled;

    public MythicMobsBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void refresh() {
        PluginManager manager = plugin.getServer().getPluginManager();
        Plugin mythic = manager.getPlugin("MythicMobs");
        enabled = mythic != null && mythic.isEnabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<UUID> spawn(String mobId, Location location) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);

            try {
                Method spawnMob = mobManager.getClass().getMethod("spawnMob", String.class, Location.class);
                Object activeMob = spawnMob.invoke(mobManager, mobId, location);
                return resolveUuid(activeMob);
            } catch (NoSuchMethodException ignored) {
                Method spawnMobWithLevel = mobManager.getClass().getMethod("spawnMob", String.class, Location.class, int.class);
                Object activeMob = spawnMobWithLevel.invoke(mobManager, mobId, location, 1);
                return resolveUuid(activeMob);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Failed to spawn Mythic mob '" + mobId + "'.", throwable);
            return Optional.empty();
        }
    }

    private Optional<UUID> resolveUuid(Object activeMob) {
        if (activeMob == null) {
            return Optional.empty();
        }
        try {
            Object abstractEntity = activeMob.getClass().getMethod("getEntity").invoke(activeMob);
            Object bukkitEntity = abstractEntity.getClass().getMethod("getBukkitEntity").invoke(abstractEntity);
            if (bukkitEntity instanceof Entity entity) {
                return Optional.of(entity.getUniqueId());
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "Unable to read Mythic spawned entity UUID.", throwable);
        }
        return Optional.empty();
    }
}
