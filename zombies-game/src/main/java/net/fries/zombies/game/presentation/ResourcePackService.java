package net.fries.zombies.game.presentation;

import net.fries.zombies.common.config.ResourcePackPolicy;
import net.fries.zombies.game.ZombiesPlugin;
import net.kyori.adventure.text.Component;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.util.*;

/** Optional client download; never performs network I/O on the server thread. */
public final class ResourcePackService implements Listener {
    private final ZombiesPlugin plugin;
    private final ResourcePackPolicy policy;
    private final NamespacedKey assetKey, instanceKey;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> statuses = new HashMap<>();
    private final Set<UUID> requested = new HashSet<>();

    public ResourcePackService(ZombiesPlugin plugin, ResourcePackPolicy policy) {
        this.plugin = plugin;
        this.policy = policy;
        assetKey = new NamespacedKey(plugin, "asset");
        instanceKey = new NamespacedKey(plugin, "weapon_instance");
    }

    public static ResourcePackService load(ZombiesPlugin plugin) throws IOException {
        var file = plugin.getDataFolder().toPath().resolve("resource-pack.yml");
        var yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException error) {
            throw new IllegalArgumentException("Invalid resource-pack.yml");
        }
        var models = new LinkedHashMap<String, String>();
        var section = yaml.getConfigurationSection("approved-models");
        if (section != null)
            section.getValues(false)
                    .forEach(
                            (key, value) -> {
                                if (!(value instanceof String model))
                                    throw new IllegalArgumentException(
                                            "Resource-pack model mappings must be strings");
                                models.put(key, model);
                            });
        return new ResourcePackService(
                plugin,
                new ResourcePackPolicy(
                        yaml.getBoolean("enabled", false),
                        yaml.getString("url", ""),
                        yaml.getString("sha1", ""),
                        models));
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        offer(event.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        requested.remove(event.getPlayer().getUniqueId());
        statuses.remove(event.getPlayer().getUniqueId());
    }

    public void offer(Player player) {
        if (!policy.enabled() || !requested.add(player.getUniqueId())) return;
        player.setResourcePack(
                policy.id(),
                policy.url(),
                policy.sha1(),
                Component.text(
                        "Optional FriesNet weapon visuals. Gameplay works without this pack."),
                false);
    }

    @EventHandler
    public void status(PlayerResourcePackStatusEvent event) {
        var player = event.getPlayer();
        if (!policy.enabled()
                || !event.getID().equals(policy.id())
                || !requested.contains(player.getUniqueId())) return;
        if (statuses.put(player.getUniqueId(), event.getStatus()) == event.getStatus()) return;
        refresh(player);
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED ->
                    player.sendMessage(Component.text("FriesNet resource pack loaded."));
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED ->
                    player.sendMessage(
                            Component.text(
                                    "Using vanilla weapon visuals; gameplay remains available."));
            default -> {}
        }
    }

    public void apply(Player player, ItemStack item, String asset) {
        if (item == null || item.getType().isAir()) return;
        String model = policy.models().get(asset);
        if (model == null) return; // unapproved/unavailable assets retain vanilla visuals
        boolean loaded =
                policy.enabled()
                        && statuses.get(player.getUniqueId())
                                == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        item.editMeta(
                meta -> {
                    if (loaded) meta.setItemModel(NamespacedKey.fromString(model));
                    else if (meta.hasItemModel() && model.equals(meta.getItemModel().toString()))
                        meta.setItemModel(null);
                });
    }

    private void refresh(Player player) {
        if (!plugin.matches().managed(player.getUniqueId())) return;
        // Update only owned weapon metadata in place; never recreate inventory or weapon
        // identities.
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            var item = inventory.getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;
            var data = item.getItemMeta().getPersistentDataContainer();
            String asset = data.get(assetKey, PersistentDataType.STRING);
            if (asset != null && data.has(instanceKey, PersistentDataType.STRING)) {
                apply(player, item, asset);
                inventory.setItem(slot, item);
            }
        }
    }

    public void close() {
        statuses.clear();
        for (Player player : plugin.getServer().getOnlinePlayers())
            if (requested.contains(player.getUniqueId())) {
                refresh(player);
                player.removeResourcePacks(policy.id());
            }
        requested.clear();
    }
}
