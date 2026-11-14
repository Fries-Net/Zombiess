package net.fries.zombies.common.gameplay;

import net.fries.zombies.common.content.Perks;
import net.fries.zombies.common.content.Perks.*;

import java.util.*;

/**
 * Match-only ownership and repeat-purchase limits. All mutations happen on the owning server
 * thread.
 */
public final class PerkLoadout {
    private final Map<Type, Definition> owned = new EnumMap<>(Type.class);
    private final Map<Type, Integer> purchases = new EnumMap<>(Type.class);
    private long nextPurchase;
    private long soloReviveAt = -1;

    public Collection<Definition> owned() {
        return List.copyOf(owned.values());
    }

    public Definition get(Type type) {
        return owned.get(type);
    }

    public String rejection(
            Survivor s, Perks rules, Definition d, int round, boolean solo, long tick) {
        if (s.life() != Survivor.Life.ALIVE) return "Only living survivors can buy perks";
        if (!d.allowed(round, solo)) return "Perk unavailable for this round or team size";
        if (tick < nextPurchase) return "Wait before your next perk purchase";
        if (purchases.getOrDefault(d.type(), 0) >= d.purchaseLimit())
            return "Per-match purchase limit reached";
        if (d.type() != Type.WUNDERFIZZ) {
            if (owned.containsKey(d.type())) return "Already owned";
            if (owned.size() >= rules.maxOwned()) return "Perk ownership limit reached";
        }
        return "";
    }

    public List<Definition> rewards(Survivor s, Perks rules, int round, boolean solo, long tick) {
        return rules.definitions().values().stream()
                .filter(
                        d ->
                                d.type() != Type.WUNDERFIZZ
                                        && d.weight() > 0
                                        && rejection(s, rules, d, round, solo, tick).isEmpty())
                .toList();
    }

    public static Definition choose(List<Definition> eligible, int ticket) {
        int total = eligible.stream().mapToInt(Definition::weight).sum();
        if (ticket < 0 || ticket >= total)
            throw new IllegalArgumentException("Invalid perk ticket");
        for (var d : eligible) {
            if (ticket < d.weight()) return d;
            ticket -= d.weight();
        }
        throw new IllegalStateException("Unreachable ticket");
    }

    public boolean purchase(
            Survivor s,
            Perks rules,
            Definition station,
            Definition reward,
            int price,
            int round,
            boolean solo,
            long tick) {
        if (price < 0 || price > 100000) throw new IllegalArgumentException("Invalid perk price");
        if (!rejection(s, rules, station, round, solo, tick).isEmpty()
                || reward.type() == Type.WUNDERFIZZ
                || !rules.definitions().containsValue(station)
                || !rules.definitions().containsValue(reward)
                || !rejection(s, rules, reward, round, solo, tick).isEmpty()
                || station.type() != Type.WUNDERFIZZ && station != reward
                || station.type() == Type.WUNDERFIZZ && reward.weight() == 0
                || !s.spend(price)) return false;
        owned.put(reward.type(), reward);
        purchases.merge(reward.type(), 1, Integer::sum);
        if (station.type() == Type.WUNDERFIZZ) purchases.merge(Type.WUNDERFIZZ, 1, Integer::sum);
        nextPurchase = tick + 10;
        return true;
    }

    public double strength(Type type, double fallback) {
        var d = get(type);
        return d == null ? fallback : d.strength();
    }

    public int slots(Perks rules) {
        return rules.baseWeaponSlots() + (int) strength(Type.MULE_KICK, 0);
    }

    public void down(boolean solo, long tick, int bleedTicks) {
        var revive = get(Type.QUICK_REVIVE);
        soloReviveAt =
                solo && revive != null
                        ? tick + Math.min(revive.duration(), Math.max(1, bleedTicks - 1))
                        : -1;
        owned.clear();
    }

    public boolean awaitingSoloRevive() {
        return soloReviveAt >= 0;
    }

    public boolean soloReady(long tick) {
        return soloReviveAt >= 0 && tick >= soloReviveAt;
    }

    public void clear() {
        owned.clear();
        soloReviveAt = -1;
    }
}
