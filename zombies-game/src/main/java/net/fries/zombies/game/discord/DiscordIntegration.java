package net.fries.zombies.game.discord;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.fries.zombies.discord.*;
import net.fries.zombies.discord.DiscordSettings.Event;
import net.fries.zombies.game.ZombiesPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.*;
import java.util.concurrent.*;

/** Lifecycle adapter only: gameplay services never depend on a gateway connection. */
public final class DiscordIntegration implements Listener, AutoCloseable {
    private record Chat(UUID player, String text) {}

    private final ZombiesPlugin plugin;
    private final DiscordSettings settings;
    private final ArrayBlockingQueue<Chat> outgoing;
    private final ArrayBlockingQueue<String> incoming = new ArrayBlockingQueue<>(64);
    private final Map<UUID, Long> chatCooldown = new HashMap<>();
    private final Map<String, Long> staffCooldown = new HashMap<>();

    private record Activity(long started, int count) {}

    private final Map<UUID, Activity> interactions = new HashMap<>();
    private final ScheduledExecutorService worker;
    private final DeliveryQueue queue;
    private final DiscordAuditHandler audit;
    private volatile DiscordTransport transport;
    private volatile String state = "disabled";
    private int ticks;

    private DiscordIntegration(ZombiesPlugin plugin, DiscordSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        outgoing = new ArrayBlockingQueue<>(settings.capacity());
        if (!settings.enabled()) {
            worker = null;
            queue = null;
            audit = null;
            return;
        }
        queue =
                new DeliveryQueue(
                        settings.capacity(), settings.messagesPerSecond(), m -> transport.send(m));
        worker =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var t = new Thread(r, "zombies-discord-delivery");
                            t.setDaemon(true);
                            return t;
                        });
        audit = new DiscordAuditHandler(this::log);
        plugin.getLogger().addHandler(audit);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        state = "starting";
        worker.execute(
                () -> {
                    try {
                        transport = new DiscordTransport(settings, System::getenv, incoming::offer);
                        state = "active";
                    } catch (RuntimeException unavailable) {
                        state = "unavailable";
                        plugin.getLogger()
                                .warning(
                                        "event=discord_unavailable"
                                            + " action=check_environment_credentials_and_channel_permissions");
                    }
                });
        worker.scheduleAtFixedRate(
                () -> {
                    var t = transport;
                    if (t != null && t.ready()) queue.tick(System.currentTimeMillis());
                },
                50,
                50,
                TimeUnit.MILLISECONDS);
    }

    public static DiscordIntegration start(ZombiesPlugin plugin) {
        try {
            return new DiscordIntegration(
                    plugin,
                    DiscordSettings.load(plugin.getDataFolder().toPath().resolve("discord.yml")));
        } catch (Exception invalid) {
            plugin.getLogger()
                    .warning(
                            "event=discord_disabled_invalid_settings"
                                    + " action=check_discord_configuration");
            return null;
        }
    }

    public String status() {
        var t = transport;
        return "Discord "
                + state
                + (t == null ? "" : " gateway=" + t.status())
                + (queue == null ? "" : " " + queue.health());
    }

    public void log(Event event, String text) {
        if (queue == null || settings.channels().get(event).equals("0")) return;
        long now = System.currentTimeMillis();
        queue.offer(
                new DeliveryQueue.Message(
                        nonce(),
                        settings.channels().get(event),
                        null,
                        null,
                        BridgeText.discord(event.name() + " " + text),
                        now));
    }

    private static String nonce() {
        return Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits());
    }

    private String player(org.bukkit.entity.Player p) {
        return settings.playerNames() ? p.getName() : "player";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        log(Event.JOIN_LEAVE, player(e.getPlayer()) + " joined");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
        interactions.remove(e.getPlayer().getUniqueId());
        chatCooldown.remove(e.getPlayer().getUniqueId());
        log(Event.JOIN_LEAVE, player(e.getPlayer()) + " left");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        if (settings.outgoing())
            outgoing.offer(
                    new Chat(
                            e.getPlayer().getUniqueId(),
                            BridgeText.plain(
                                    PlainTextComponentSerializer.plainText().serialize(e.message()),
                                    600)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        if (e.getPlayer().hasPermission("zombies.admin")
                || e.getPlayer().hasPermission("zombies.editor"))
            command(player(e.getPlayer()), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void console(ServerCommandEvent e) {
        command("console", e.getCommand());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interaction(PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND
                || settings.channels().get(Event.ECONOMY).equals("0")) return;
        var run = plugin.matches() == null ? null : plugin.matches().current();
        if (run == null || !run.playing() || e.getPlayer().getWorld() != run.world) return;
        long now = System.currentTimeMillis();
        var id = e.getPlayer().getUniqueId();
        var old = interactions.get(id);
        var next =
                old == null || now - old.started >= 1000
                        ? new Activity(now, 1)
                        : new Activity(old.started, old.count + 1);
        interactions.put(id, next);
        if (next.count == 30)
            log(
                    Event.ECONOMY,
                    "suspicious_interaction_rate actor="
                            + player(e.getPlayer())
                            + " events_per_second>=30 (review; no automatic penalty)");
    }

    private void command(String actor, String raw) {
        var parts = raw.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 2
                || !Set.of("zombies", "jz", "friesnetzombies:zombies").contains(parts[0])
                || !Set.of("pause", "resume", "stop", "reload", "validate", "editor")
                        .contains(parts[1])) return;
        String path =
                parts[1]
                        + (parts[1].equals("editor")
                                        && parts.length > 2
                                        && parts[2].matches("[a-z-]{1,24}")
                                ? " " + parts[2]
                                : "");
        long now = System.currentTimeMillis();
        String key = actor + ":" + path;
        if (now - staffCooldown.getOrDefault(key, 0L) < 1000) return;
        if (staffCooldown.size() >= 512) staffCooldown.clear();
        staffCooldown.put(key, now);
        log(
                Event.STAFF,
                "command_attempt actor=" + actor + " /zombies " + path + " arguments=redacted");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        var run = plugin.matches() == null ? null : plugin.matches().current();
        for (int i = 0; i < 10; i++) {
            var chat = outgoing.poll();
            if (chat == null) break;
            var p = Bukkit.getPlayer(chat.player);
            if (p == null || chat.text.isBlank()) continue;
            if (settings.minecraftChannel().equals("MATCH")
                    && (run == null || !run.playing() || p.getWorld() != run.world)) continue;
            if (now - chatCooldown.getOrDefault(chat.player, 0L) < 1000) continue;
            chatCooldown.put(chat.player, now);
            queue.offer(
                    new DeliveryQueue.Message(
                            nonce(),
                            settings.chatChannel(),
                            p.getName(),
                            settings.avatar(p.getUniqueId()),
                            BridgeText.discord(chat.text),
                            now));
        }
        for (int i = 0; i < 4; i++) {
            var message = incoming.poll();
            if (message == null) break;
            var rendered =
                    Component.text(
                            message,
                            NamedTextColor.GRAY); // literal: no MiniMessage, links or commands
            if (settings.minecraftChannel().equals("MATCH")) {
                if (run != null && run.playing())
                    run.world.getPlayers().forEach(p -> p.sendMessage(rendered));
            } else Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(rendered));
        }
        if (++ticks % 200 == 0) chatCooldown.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    @Override
    public void close() {
        if (worker == null) return;
        log(Event.SERVER, "server_shutdown");
        plugin.getLogger().removeHandler(audit);
        state = "stopping";
        // A short best-effort drain runs entirely off-thread. Server shutdown never waits on
        // Discord.
        worker.schedule(
                () -> {
                    queue.close();
                    var t = transport;
                    if (t != null) t.close();
                    worker.shutdown();
                },
                2,
                TimeUnit.SECONDS);
        outgoing.clear();
        incoming.clear();
    }
}
