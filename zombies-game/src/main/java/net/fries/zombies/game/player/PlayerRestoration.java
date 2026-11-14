package net.fries.zombies.game.player;

import net.fries.zombies.persistence.DurableFiles;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Admission locks and idempotent return operations, including disconnect and startup recovery. */
public final class PlayerRestoration {
    private final JavaPlugin plugin;
    private final PlayerBackups backups;
    private final Consumer<Runnable> onServer;
    private final BiConsumer<String, Throwable> log;
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, byte[]> captured = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> restorations = new HashMap<>();

    public PlayerRestoration(
            JavaPlugin plugin,
            Path directory,
            DurableFiles disk,
            Consumer<Runnable> onServer,
            BiConsumer<String, Throwable> log) {
        this.plugin = plugin;
        this.backups = new PlayerBackups(directory, disk);
        this.onServer = onServer;
        this.log = log;
    }

    public void hold(Player player) {
        pending.add(player.getUniqueId());
    }

    public void equip(Player player, net.fries.zombies.game.match.MatchRun run) {
        player.closeInventory();
        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setWalkSpeed(.2f);
        player.getActivePotionEffects()
                .forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setAbsorptionAmount(0);
        player.setFallDistance(0);
        player.setNoDamageTicks(60);
        if (!player.teleport(
                net.fries.zombies.game.world.MapWorlds.location(
                        run.world, run.map.playerSpawn(player.getUniqueId()))))
            throw new IllegalStateException("Admission teleport rejected");
        run.combat.equip(player);
    }

    public void admitted(UUID player) {
        pending.remove(player);
    }

    public boolean managed(UUID player) {
        return pending.contains(player)
                || captured.containsKey(player)
                || restorations.containsKey(player);
    }

    public CompletableFuture<Void> capture(Player player) {
        hold(player);
        UUID id = player.getUniqueId();
        return backups.capture(player)
                .thenCompose(v -> backups.read(id))
                .thenAccept(
                        bytes -> {
                            if (bytes != null) captured.put(id, bytes);
                        });
    }

    public CompletableFuture<Void> restore(Player player) {
        UUID id = player.getUniqueId();
        if (restorations.containsKey(id)) return restorations.get(id);
        hold(player);
        var done = new CompletableFuture<Void>();
        restorations.put(id, done);
        done.whenComplete((v, e) -> restorations.remove(id));
        backups.read(id)
                .whenComplete(
                        (bytes, error) ->
                                onServer.accept(
                                        () -> {
                                            if (error != null) {
                                                log.accept("player_restore_failed", error);
                                                done.completeExceptionally(error);
                                                return;
                                            }
                                            var online = plugin.getServer().getPlayer(id);
                                            if (online == null) {
                                                captured.remove(id);
                                                pending.remove(id);
                                                done.complete(null);
                                                return;
                                            }
                                            try {
                                                if (bytes == null) {
                                                    captured.remove(id);
                                                    pending.remove(id);
                                                    done.complete(null);
                                                    return;
                                                }
                                                backups.restore(online, bytes);
                                                backups.discard(id)
                                                        .whenComplete(
                                                                (v, e) ->
                                                                        onServer.accept(
                                                                                () -> {
                                                                                    if (e != null) {
                                                                                        log.accept(
                                                                                                "player_backup_delete_failed",
                                                                                                e);
                                                                                        done
                                                                                                .completeExceptionally(
                                                                                                        e);
                                                                                    } else {
                                                                                        captured
                                                                                                .remove(
                                                                                                        id);
                                                                                        pending
                                                                                                .remove(
                                                                                                        id);
                                                                                        done
                                                                                                .complete(
                                                                                                        null);
                                                                                    }
                                                                                }));
                                            } catch (IOException e) {
                                                log.accept("player_restore_failed", e);
                                                done.completeExceptionally(e);
                                            }
                                        }));
        return done;
    }

    public CompletableFuture<Void> restoreAll() {
        return restoreAll(id -> true);
    }

    public CompletableFuture<Void> restoreAll(java.util.function.Predicate<UUID> include) {
        var futures = new ArrayList<CompletableFuture<Void>>();
        var returning = new HashSet<>(captured.keySet());
        returning.addAll(pending);
        for (var id : returning) {
            if (!include.test(id)) continue;
            var player = plugin.getServer().getPlayer(id);
            if (player != null) futures.add(restore(player));
            else {
                captured.remove(id);
                pending.remove(id);
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    public void quit(Player player) {
        UUID id = player.getUniqueId();
        byte[] bytes = captured.get(id);
        if (bytes != null)
            try {
                backups.restore(player, bytes);
                captured.remove(id);
                backups.discard(id)
                        .exceptionally(
                                e -> {
                                    log.accept("player_backup_delete_failed", e);
                                    return null;
                                });
            } catch (IOException e) {
                log.accept("player_restore_failed", e);
            }
        pending.remove(id);
        backups.forgetScoreboard(id);
    }

    public void shutdown() {
        for (var player : plugin.getServer().getOnlinePlayers()) {
            byte[] bytes = captured.get(player.getUniqueId());
            if (bytes != null)
                try {
                    backups.restore(player, bytes);
                } catch (IOException e) {
                    log.accept("shutdown_player_restore_failed", e);
                }
        }
    }
}
