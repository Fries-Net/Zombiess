package net.fries.zombies.game.player;

import net.fries.zombies.persistence.DurableFiles;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.Scoreboard;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The original inventory is forced to disk before match equipment is installed. */
public final class PlayerBackups {
    private final Path directory;
    private final DurableFiles disk;
    private final Map<UUID, Scoreboard> scoreboards = new HashMap<>();

    public void forgetScoreboard(UUID id) {
        scoreboards.remove(id);
    }

    public PlayerBackups(Path directory, DurableFiles disk) {
        this.directory = directory;
        this.disk = disk;
    }

    public CompletableFuture<Void> capture(Player p) {
        try {
            byte[] bytes = encode(p);
            scoreboards.put(p.getUniqueId(), p.getScoreboard());
            return disk.submit(
                    () -> {
                        var path = file(p.getUniqueId());
                        if (Files.exists(path))
                            throw new IOException("Player has an unrestored backup");
                        DurableFiles.replace(path, bytes);
                        return null;
                    });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<byte[]> read(UUID id) {
        return disk.submit(
                () -> {
                    var path = file(id);
                    if (!Files.exists(path)) return null;
                    if (Files.isSymbolicLink(path) || Files.size(path) > 4 * 1024 * 1024)
                        throw new IOException("Invalid player backup");
                    return Files.readAllBytes(path);
                });
    }

    public CompletableFuture<Void> discard(UUID id) {
        return disk.submit(
                () -> {
                    DurableFiles.delete(file(id));
                    return null;
                });
    }

    public void restore(Player p, byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1 || !in.readUTF().equals(p.getUniqueId().toString()))
                throw new IOException("Invalid player backup identity");
            World world = Bukkit.getWorld(UUID.fromString(in.readUTF()));
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat();
            var location =
                    world == null
                            ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                            : new Location(world, x, y, z, yaw, pitch);
            var mode = GameMode.valueOf(in.readUTF());
            double health = in.readDouble();
            int food = in.readInt();
            float saturation = in.readFloat();
            float exhaustion = in.readFloat();
            int fire = in.readInt();
            int level = in.readInt();
            float experience = in.readFloat();
            int total = in.readInt();
            boolean flight = in.readBoolean(), flying = in.readBoolean();
            float walk = in.readFloat(), fly = in.readFloat();
            double absorption = in.readDouble();
            int held = in.readInt();
            int count = in.readInt();
            if (count != p.getInventory().getContents().length || count > 64)
                throw new IOException("Invalid player inventory size");
            var items = new ItemStack[count];
            for (int n = 0; n < count; n++) {
                int length = in.readInt();
                if (length < 0 || length > 1048576) throw new IOException("Invalid inventory item");
                if (length > 0) {
                    byte[] data = in.readNBytes(length);
                    if (data.length != length) throw new EOFException();
                    items[n] = ItemStack.deserializeBytes(data);
                }
            }
            int effectCount = in.readInt();
            if (effectCount < 0 || effectCount > 128)
                throw new IOException("Invalid potion effects");
            var effects = new ArrayList<PotionEffect>();
            for (int n = 0; n < effectCount; n++) {
                var type =
                        org.bukkit.Registry.EFFECT.get(
                                Objects.requireNonNull(NamespacedKey.fromString(in.readUTF())));
                int duration = in.readInt(), amplifier = in.readInt();
                boolean ambient = in.readBoolean(),
                        particles = in.readBoolean(),
                        icon = in.readBoolean();
                if (type != null)
                    effects.add(
                            new PotionEffect(type, duration, amplifier, ambient, particles, icon));
            }
            if (in.available() != 0) throw new IOException("Trailing player backup data");
            p.clearActiveItem();
            p.closeInventory();
            p.getInventory().setContents(items);
            p.getInventory().setHeldItemSlot(held);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.addPotionEffects(effects);
            p.setGameMode(mode);
            p.setAllowFlight(flight);
            p.setFlying(flight && flying);
            p.setWalkSpeed(walk);
            p.setFlySpeed(fly);
            p.setHealth(
                    Math.max(
                            1,
                            Math.min(
                                    health,
                                    Objects.requireNonNull(
                                                    p.getAttribute(
                                                            org.bukkit.attribute.Attribute
                                                                    .MAX_HEALTH))
                                            .getValue())));
            p.setFoodLevel(food);
            p.setSaturation(saturation);
            p.setExhaustion(exhaustion);
            p.setFireTicks(fire);
            p.setLevel(level);
            p.setExp(experience);
            p.setTotalExperience(total);
            p.setAbsorptionAmount(absorption);
            p.setFallDistance(0);
            p.setScoreboard(
                    scoreboards.getOrDefault(
                            p.getUniqueId(), Bukkit.getScoreboardManager().getMainScoreboard()));
            if (!p.teleport(location)) throw new IOException("Player return teleport rejected");
            p.sendActionBar(net.kyori.adventure.text.Component.empty());
            // Save before removing the backup. Replaying an interrupted restore replaces contents
            // idempotently.
            p.saveData();
            scoreboards.remove(p.getUniqueId());
        } catch (RuntimeException e) {
            throw new IOException("Player restoration failed", e);
        }
    }

    private byte[] encode(Player p) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            var l = p.getLocation();
            out.writeInt(1);
            out.writeUTF(p.getUniqueId().toString());
            out.writeUTF(p.getWorld().getUID().toString());
            out.writeDouble(l.getX());
            out.writeDouble(l.getY());
            out.writeDouble(l.getZ());
            out.writeFloat(l.getYaw());
            out.writeFloat(l.getPitch());
            out.writeUTF(p.getGameMode().name());
            out.writeDouble(p.getHealth());
            out.writeInt(p.getFoodLevel());
            out.writeFloat(p.getSaturation());
            out.writeFloat(p.getExhaustion());
            out.writeInt(p.getFireTicks());
            out.writeInt(p.getLevel());
            out.writeFloat(p.getExp());
            out.writeInt(p.getTotalExperience());
            out.writeBoolean(p.getAllowFlight());
            out.writeBoolean(p.isFlying());
            out.writeFloat(p.getWalkSpeed());
            out.writeFloat(p.getFlySpeed());
            out.writeDouble(p.getAbsorptionAmount());
            out.writeInt(p.getInventory().getHeldItemSlot());
            var items = p.getInventory().getContents();
            out.writeInt(items.length);
            for (var item : items) {
                byte[] data = item == null ? new byte[0] : item.serializeAsBytes();
                out.writeInt(data.length);
                out.write(data);
            }
            var effects = p.getActivePotionEffects();
            out.writeInt(effects.size());
            for (var e : effects) {
                out.writeUTF(e.getType().getKey().toString());
                out.writeInt(e.getDuration());
                out.writeInt(e.getAmplifier());
                out.writeBoolean(e.isAmbient());
                out.writeBoolean(e.hasParticles());
                out.writeBoolean(e.hasIcon());
            }
        }
        return bytes.toByteArray();
    }

    private Path file(UUID id) {
        return directory.resolve(id + ".player");
    }
}
