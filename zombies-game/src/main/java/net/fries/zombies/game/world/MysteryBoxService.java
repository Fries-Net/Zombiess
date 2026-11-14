package net.fries.zombies.game.world;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;
import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Server-thread paid rolls, native previews and owner-only collection in a disposable match. */
public final class MysteryBoxService {
    private record Pending(MapObject station, MysteryRoll roll, ItemDisplay preview) {}

    private final MatchRun run;
    private final Random random = new Random();
    private final List<MapObject> stations;
    private final Map<NamespacedId, String> active = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, TextDisplay> labels = new HashMap<>();

    public MysteryBoxService(MatchRun run) {
        this.run = run;
        stations =
                run.map.objects().stream()
                        .filter(o -> o.kind() == MapObject.Kind.MYSTERY_BOX)
                        .toList();
        for (var station : stations) {
            var pool = pool(station);
            if (unlocked(station)) active.putIfAbsent(pool.id(), station.id());
            labels.put(
                    station.id(),
                    run.world.spawn(
                            at(station).add(0, 1.5, 0),
                            TextDisplay.class,
                            d -> {
                                d.setBillboard(Display.Billboard.CENTER);
                                d.setPersistent(false);
                            }));
        }
        updateLabels();
    }

    private MysteryPools.Pool pool(MapObject s) {
        return run.mysteryPools.require(s.property("box", "friesnet:box/default"));
    }

    private boolean unlocked(MapObject s) {
        return s.rooms().stream().allMatch(run.interactions::roomUnlocked);
    }

    private Location at(MapObject s) {
        return MapWorlds.location(run.world, s.point());
    }

    private int price(MapObject s) {
        return s.integer("price", pool(s).price(), 0, 100000);
    }

    private boolean active(MapObject s) {
        return s.id().equals(active.get(pool(s).id()));
    }

    private int freeSlot(Player p) {
        for (int i = 0; i < 8; i++) {
            var item = p.getInventory().getItem(i);
            if (item == null || item.getType().isAir()) return i;
        }
        return -1;
    }

    public boolean interact(Player p) {
        if (!run.alive(p.getUniqueId())) return false;
        for (var station :
                stations.stream()
                        .sorted(
                                Comparator.comparingDouble(
                                        s -> p.getEyeLocation().distanceSquared(at(s))))
                        .toList())
            if (run.interactions.near(p, station.point(), 3.5)) {
                if (!unlocked(station)) {
                    message(p, "Unlock this mystery box's room first");
                    return true;
                }
                active.putIfAbsent(pool(station).id(), station.id());
                if (!active(station)) {
                    message(p, "Mystery box is active at another location");
                    return true;
                }
                var purchase = pending.get(station.id());
                if (purchase != null) {
                    collect(p, purchase);
                    return true;
                }
                if (pending.values().stream()
                        .anyMatch(v -> v.roll.owner().equals(p.getUniqueId()))) {
                    message(p, "Collect your existing mystery reward first");
                    return true;
                }
                if (freeSlot(p) < 0) {
                    message(p, "Weapon slots are full; no gold charged");
                    return true;
                }
                var pool = pool(station);
                var reward = pool.choose(random.nextInt(pool.totalWeight()));
                int price = price(station);
                var survivor = run.survivor(p.getUniqueId());
                if (!survivor.weapons().containsKey(reward.weapon())
                        && survivor.weapons().size() >= survivor.perks().slots(run.perkRules)) {
                    message(p, "Weapon capacity reached; buy Mule Kick first. No gold charged");
                    return true;
                }
                // Construct preview before charging; a spawn failure cannot lose a purchase.
                var preview =
                        run.world.spawn(
                                at(station).add(0, 1, 0),
                                ItemDisplay.class,
                                d -> {
                                    d.setItemStack(new ItemStack(Material.CHEST));
                                    d.setPersistent(false);
                                    d.setBillboard(Display.Billboard.CENTER);
                                });
                if (!survivor.spend(price)) {
                    preview.remove();
                    message(p, "Need " + price + " gold");
                    return true;
                }
                var roll =
                        new MysteryRoll(
                                p.getUniqueId(),
                                reward,
                                price,
                                run.tick + pool.openTicks(),
                                run.tick + pool.openTicks() + pool.claimTicks());
                pending.put(station.id(), new Pending(station, roll, preview));
                run.world.playSound(at(station), Sound.BLOCK_CHEST_OPEN, 1, .7f);
                message(
                        p,
                        "Mystery box opening • "
                                + price
                                + " gold • right-click to collect when ready");
                updateLabels();
                return true;
            }
        return false;
    }

    private void collect(Player p, Pending purchase) {
        var roll = purchase.roll;
        if (!roll.owner().equals(p.getUniqueId())) {
            message(p, "This reward belongs to another survivor");
            return;
        }
        if (!roll.ready(run.tick)) {
            message(p, "Mystery box is still opening");
            return;
        }
        var survivor = run.survivor(p.getUniqueId());
        var reward = roll.reward();
        var owned = survivor.weapons().get(reward.weapon());
        int slot = freeSlot(p);
        if (owned != null && owned.reserve() == owned.definition().reserve()
                || owned == null
                        && (slot < 0
                                || survivor.weapons().size()
                                        >= survivor.perks().slots(run.perkRules))) {
            refund(purchase, "Reward could not fit or ammo was full; purchase refunded");
            return;
        }
        var state =
                owned == null ? new WeaponState(run.content.weapons().get(reward.weapon())) : owned;
        var item = owned == null ? run.combat.item(state) : null;
        if (!roll.claim(p.getUniqueId(), run.tick)) return;
        if (owned != null) owned.refill();
        else {
            survivor.weapons().put(reward.weapon(), state);
            p.getInventory().setItem(slot, item);
        }
        remove(purchase);
        p.playSound(
                p.getLocation(),
                reward.rare() ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP,
                .7f,
                1);
        message(
                p,
                (reward.rare() ? "RARE • " : "")
                        + state.definition().name()
                        + (owned == null ? " collected" : " ammunition refilled"));
        relocate(purchase.station);
        updateLabels();
    }

    private void refund(Pending purchase, String text) {
        int amount = purchase.roll.refund();
        var survivor = run.survivor(purchase.roll.owner());
        if (survivor != null) survivor.credit(amount);
        var p = Bukkit.getPlayer(purchase.roll.owner());
        if (p != null) message(p, text);
        remove(purchase);
        updateLabels();
    }

    private void remove(Pending p) {
        pending.remove(p.station.id());
        p.preview.remove();
    }

    private void relocate(MapObject from) {
        var pool = pool(from);
        var choices =
                stations.stream()
                        .filter(
                                s ->
                                        !s.id().equals(from.id())
                                                && pool(s).id().equals(pool.id())
                                                && unlocked(s))
                        .toList();
        if (choices.isEmpty() || random.nextDouble() >= pool.relocateChance()) return;
        var destination = choices.get(random.nextInt(choices.size()));
        active.put(pool.id(), destination.id());
        run.world.playSound(at(from), Sound.ENTITY_ENDERMAN_TELEPORT, 1, .7f);
        run.world.getPlayers().forEach(p -> message(p, "Mystery box moved to " + destination.id()));
    }

    private void updateLabels() {
        for (var s : stations) {
            var purchase = pending.get(s.id());
            String text =
                    !unlocked(s)
                            ? "Mystery box • room locked"
                            : !active(s)
                                    ? "Mystery box • dormant"
                                    : purchase == null
                                            ? "Mystery box • " + price(s) + " gold"
                                            : purchase.roll.ready(run.tick)
                                                    ? (purchase.roll.reward().rare()
                                                                    ? "RARE • "
                                                                    : "")
                                                            + run.content
                                                                    .weapons()
                                                                    .get(
                                                                            purchase.roll
                                                                                    .reward()
                                                                                    .weapon())
                                                                    .name()
                                                            + " • owner: right-click to collect"
                                                    : "Mystery box • opening";
            labels.get(s.id()).text(Component.text(text));
        }
    }

    public void tick() {
        for (var purchase : List.copyOf(pending.values())) {
            var p = Bukkit.getPlayer(purchase.roll.owner());
            if (p == null || !run.alive(p.getUniqueId()) || purchase.roll.expired(run.tick)) {
                refund(purchase, "Mystery purchase cancelled or expired; gold refunded");
                continue;
            }
            if (run.tick % 5 == 0) {
                var rewards = pool(purchase.station).rewards();
                var reward =
                        purchase.roll.ready(run.tick)
                                ? purchase.roll.reward()
                                : rewards.get(random.nextInt(rewards.size()));
                var weapon = run.content.weapons().get(reward.weapon());
                purchase.preview.setItemStack(new ItemStack(Material.valueOf(weapon.material())));
                run.world.spawnParticle(
                        Particle.ENCHANT, at(purchase.station).add(0, 1, 0), 3, .3, .3, .3, 0);
            }
        }
        if (run.tick % 10 == 0) updateLabels();
    }

    public void clear() {
        for (var p : List.copyOf(pending.values()))
            refund(p, "Match ended; pending mystery purchase refunded");
        labels.values().forEach(Entity::remove);
        labels.clear();
        active.clear();
    }

    public String context(Player p) {
        for (var station :
                stations.stream()
                        .sorted(
                                Comparator.comparingDouble(
                                        s -> p.getEyeLocation().distanceSquared(at(s))))
                        .toList())
            if (run.interactions.near(p, station.point(), 3.5)) {
                if (!unlocked(station)) return "Mystery box • room locked";
                if (!active(station)) return "Mystery box • dormant location";
                var purchase = pending.get(station.id());
                if (purchase == null)
                    return "Right-click • mystery box • " + price(station) + " gold";
                if (!purchase.roll.owner().equals(p.getUniqueId()))
                    return "Mystery box • another survivor's reward";
                return purchase.roll.ready(run.tick)
                        ? "Right-click to collect your mystery reward"
                        : "Mystery box opening...";
            }
        return "";
    }

    private static void message(Player p, String text) {
        p.sendMessage(Component.text(text));
    }
}
