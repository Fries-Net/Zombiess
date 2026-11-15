package net.fries.zombies.playtest;

import net.fries.zombies.common.config.ResourcePackPolicy;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.presentation.ResourcePackService;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;

/** Native inventory/component checks with simulated download statuses, not visual acceptance. */
final class PaperResourcePackChecks {
    static void verify(ZombiesPlugin plugin, MatchRun run, Player player) {
        var weapon = run.survivor(player.getUniqueId()).weapons().get(run.content.starter());
        var original = player.getInventory().getItem(0).clone();
        int magazine = weapon.magazine(), reserve = weapon.reserve();
        var asset = weapon.definition().behaviour().asset().value();
        var policy =
                new ResourcePackPolicy(
                        true,
                        "https://example.invalid/friesnet-v3-preview.zip",
                        "0123456789abcdef0123456789abcdef01234567",
                        Map.of(asset, "friesnet:weapon/glock18"));
        var service = new ResourcePackService(plugin, policy);
        try {
            var item = run.combat.item(weapon, player);
            require(
                    !item.getItemMeta().hasItemModel(),
                    "disabled policy preserves vanilla visuals");
            player.getInventory().setItem(0, item);
            service.offer(player);
            service.offer(player); // optional protocol request, never fetches on server
            service.status(
                    new PlayerResourcePackStatusEvent(
                            player, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED));
            require(
                    !player.getInventory().getItem(0).getItemMeta().hasItemModel(),
                    "foreign pack status ignored");
            service.status(new PlayerResourcePackStatusEvent(player, policy.id(), Status.ACCEPTED));
            require(
                    !player.getInventory().getItem(0).getItemMeta().hasItemModel(),
                    "acceptance alone cannot activate models");
            service.status(
                    new PlayerResourcePackStatusEvent(
                            player, policy.id(), Status.SUCCESSFULLY_LOADED));
            service.status(
                    new PlayerResourcePackStatusEvent(
                            player, policy.id(), Status.SUCCESSFULLY_LOADED));
            var rendered = player.getInventory().getItem(0);
            var consumable =
                    rendered.getData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE);
            require(
                    consumable != null && consumable.consumeSeconds() == 3600,
                    "model metadata retains native held-fire component");
            var beforeEffects =
                    original.getData(io.papermc.paper.datacomponent.DataComponentTypes.USE_EFFECTS);
            var afterEffects =
                    rendered.getData(io.papermc.paper.datacomponent.DataComponentTypes.USE_EFFECTS);
            require(
                    beforeEffects == null
                            || (afterEffects != null
                                    && beforeEffects.canSprint() == afterEffects.canSprint()
                                    && beforeEffects.speedMultiplier()
                                            == afterEffects.speedMultiplier()),
                    "model activation cannot reintroduce native movement slowdown");
            require(
                    "friesnet:weapon/glock18"
                            .equals(rendered.getItemMeta().getItemModel().toString()),
                    "loaded approved model applied");
            require(
                    weapon.instanceId()
                            .toString()
                            .equals(
                                    rendered.getItemMeta()
                                            .getPersistentDataContainer()
                                            .get(
                                                    new NamespacedKey(plugin, "weapon_instance"),
                                                    PersistentDataType.STRING)),
                    "render refresh retains weapon identity");
            var unknown = run.combat.item(weapon);
            service.apply(player, unknown, "friesnet:weapon/unknown");
            require(
                    !unknown.getItemMeta().hasItemModel(),
                    "unapproved assets retain vanilla fallback");
            var teammate = org.bukkit.Bukkit.getPlayerExact("SurvivorTwo");
            service.offer(teammate);
            service.status(
                    new PlayerResourcePackStatusEvent(teammate, policy.id(), Status.DECLINED));
            var fallback = run.combat.item(weapon);
            service.apply(teammate, fallback, asset);
            require(
                    !fallback.getItemMeta().hasItemModel()
                            && player.getInventory().getItem(0).getItemMeta().hasItemModel(),
                    "teammate decline never changes the loaded player's visuals");
            service.status(
                    new PlayerResourcePackStatusEvent(player, policy.id(), Status.FAILED_RELOAD));
            require(
                    !player.getInventory().getItem(0).getItemMeta().hasItemModel(),
                    "failed reload restores vanilla in place");
            require(
                    player.getInventory().getItem(0).getAmount() == 1
                            && weapon.magazine() == magazine
                            && weapon.reserve() == reserve,
                    "status transitions never duplicate inventory");
        } finally {
            service.close();
            player.getInventory().setItem(0, original);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Resource pack: " + message);
    }
}
