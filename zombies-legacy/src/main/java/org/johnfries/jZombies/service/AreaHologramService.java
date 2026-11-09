package org.johnfries.jZombies.service;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.java.JavaPlugin;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.model.BuyableArea;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AreaHologramService {
    private final JavaPlugin plugin;
    private final LangService lang;
    private final Map<String, List<ArmorStand>> holograms = new HashMap<>();

    public AreaHologramService(JavaPlugin plugin, LangService lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void rebuild(List<BuyableArea> areas, Set<String> openedAreas) {
        clear();
        for (BuyableArea area : areas) {
            spawnAreaHologram(area, openedAreas.contains(area.id()));
        }
    }

    public void clear() {
        for (List<ArmorStand> stands : holograms.values()) {
            for (ArmorStand stand : stands) {
                if (stand != null && stand.isValid()) {
                    stand.remove();
                }
            }
        }
        holograms.clear();
    }

    public void setAreaOpened(BuyableArea area, boolean opened) {
        List<ArmorStand> stands = holograms.get(area.id());
        if (stands == null || stands.size() < 2) {
            return;
        }
        ArmorStand stateLine = stands.get(1);
        if (stateLine == null || !stateLine.isValid()) {
            return;
        }
        stateLine.setCustomName(opened
                ? lang.msg("areas.hologram-opened")
                : lang.msg("areas.hologram-price", Map.of("price", String.valueOf(area.price()))));
    }

    public void syncState(List<BuyableArea> areas, Set<String> openedAreas) {
        for (BuyableArea area : areas) {
            setAreaOpened(area, openedAreas.contains(area.id()));
        }
    }

    private void spawnAreaHologram(BuyableArea area, boolean opened) {
        if (area.interactBlock().getWorld() == null) {
            return;
        }
        Location base = area.interactBlock().clone().add(0.5D, 2.45D, 0.5D);
        ArmorStand title = line(base, lang.msg("areas.hologram-title", Map.of("area", area.displayName())));
        ArmorStand info = line(base.clone().add(0.0D, -0.28D, 0.0D), opened
                ? lang.msg("areas.hologram-opened")
                : lang.msg("areas.hologram-price", Map.of("price", String.valueOf(area.price()))));
        holograms.put(area.id(), List.of(title, info));
    }

    private ArmorStand line(Location location, String text) {
        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setVisible(false);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setPersistent(true);
            stand.setCustomNameVisible(true);
            stand.setCustomName(text);
            stand.setSilent(true);
            stand.setCanPickupItems(false);
            stand.setRemoveWhenFarAway(false);
        });
    }
}
