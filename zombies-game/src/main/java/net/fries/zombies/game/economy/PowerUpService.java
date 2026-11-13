package net.fries.zombies.game.economy;

import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Owned, expiring pickups. Match gold multipliers never touch permanent reward balances. */
public final class PowerUpService {
    private final MatchRun run;
    private final Map<UUID, Long> drops = new HashMap<>();

    public PowerUpService(MatchRun run) {
        this.run = run;
    }

    public void maybeDrop(Location location) {
        if (Math.random() < run.content.scoring().doubleGoldDropChance()) spawn(location);
    }

    public Item spawn(Location location) {
        var item = run.world.dropItem(location, new ItemStack(Material.GOLD_INGOT));
        item.setPickupDelay(10);
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
        item.customName(Component.text("Double Gold", NamedTextColor.GOLD));
        item.setCustomNameVisible(true);
        drops.put(item.getUniqueId(), run.tick + 600);
        return item;
    }

    public boolean pickup(Player player, Item item) {
        if (!drops.containsKey(item.getUniqueId())) return false;
        if (!run.alive(player.getUniqueId()) || item.getPickupDelay() > 0) return true;
        drops.remove(item.getUniqueId());
        item.remove();
        run.points.doubleGold(run.tick, run.content.scoring().doubleGoldTicks());
        for (var p : run.world.getPlayers()) {
            p.sendActionBar(Component.text("DOUBLE GOLD", NamedTextColor.GOLD));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .4f, 1.6f);
        }
        return true;
    }

    public void tick() {
        if (run.tick % 20 != 0) return;
        for (var id : List.copyOf(drops.keySet()))
            if (run.tick >= drops.get(id)) {
                var entity = Bukkit.getEntity(id);
                if (entity != null) entity.remove();
                drops.remove(id);
            }
    }

    public void clear() {
        for (var id : drops.keySet()) {
            var entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        drops.clear();
    }
}
