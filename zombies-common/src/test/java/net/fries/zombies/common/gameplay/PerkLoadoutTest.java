package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class PerkLoadoutTest {
    @org.junit.jupiter.api.io.TempDir Path directory;

    private Survivor survivor() throws Exception {
        Path file = directory.resolve("gameplay.yml");
        Files.write(file, getClass().getResourceAsStream("/phase2-gameplay.yml").readAllBytes());
        return new Survivor(UUID.randomUUID(), Gameplay.load(file));
    }

    private Perks.Definition def(Perks.Type type, int weight) {
        return new Perks.Definition(
                new NamespacedId("test:perk/" + type.name().toLowerCase(Locale.ROOT)),
                type,
                type.name(),
                new NamespacedId("test:asset/perk"),
                10,
                true,
                1,
                true,
                true,
                3,
                weight,
                type == Perks.Type.JUGGERNOG ? 40 : 2,
                type == Perks.Type.DOUBLE_TAP ? 1.5 : 0,
                type == Perks.Type.QUICK_REVIVE ? 20 : 0,
                0);
    }

    private Perks rules(int cap, Perks.Definition... defs) {
        var map = new LinkedHashMap<NamespacedId, Perks.Definition>();
        for (var d : defs) map.put(d.id(), d);
        return new Perks(cap, 3, map);
    }

    @Test
    void purchasesAreAtomicBoundedAndNeverStack() throws Exception {
        var s = survivor();
        var a = def(Perks.Type.SPEED_COLA, 2);
        var b = def(Perks.Type.DOUBLE_TAP, 1);
        var r = rules(1, a, b);
        int before = s.points();
        assertFalse(s.perks().purchase(s, r, a, a, before + 1, 1, false, 1));
        assertEquals(before, s.points());
        assertTrue(s.perks().purchase(s, r, a, a, 10, 1, false, 1));
        assertFalse(s.perks().purchase(s, r, a, a, 10, 1, false, 20));
        assertFalse(s.perks().purchase(s, r, b, b, 10, 1, false, 20));
        assertEquals(before - 10, s.points());
        assertEquals(2, s.reloadSpeed());
        s.perks().down(false, 30, 100);
        assertEquals(1, s.reloadSpeed());
    }

    @Test
    void wunderfizzExcludesOwnedAndUsesExactWeights() throws Exception {
        var s = survivor();
        var a = def(Perks.Type.SPEED, 2);
        var b = def(Perks.Type.MULE_KICK, 1);
        var w = def(Perks.Type.WUNDERFIZZ, 0);
        var r = rules(4, a, b, w);
        var eligible = s.perks().rewards(s, r, 1, true, 10);
        assertEquals(a, PerkLoadout.choose(eligible, 0));
        assertEquals(a, PerkLoadout.choose(eligible, 1));
        assertEquals(b, PerkLoadout.choose(eligible, 2));
        assertThrows(IllegalArgumentException.class, () -> PerkLoadout.choose(eligible, 3));
        assertTrue(s.perks().purchase(s, r, w, a, 10, 1, true, 10));
        assertEquals(List.of(b), s.perks().rewards(s, r, 1, true, 20));
        assertFalse(s.perks().purchase(s, r, w, w, 10, 1, true, 30));
        assertTrue(s.perks().purchase(s, r, w, b, 10, 1, true, 30));
        assertEquals(5, s.perks().slots(r));
    }

    @Test
    void soloReviveConsumesPerkAndHonorsLifetimeLimit() throws Exception {
        var s = survivor();
        var q = def(Perks.Type.QUICK_REVIVE, 1);
        var r = rules(4, q);
        for (int i = 0; i < 3; i++) {
            long tick = i * 100;
            assertTrue(s.perks().purchase(s, r, q, q, 0, 1, true, tick));
            s.down(tick, 100);
            s.perks().down(true, tick, 100);
            assertFalse(s.selfRevive(tick + 19));
            assertTrue(s.selfRevive(tick + 20));
            assertTrue(s.perks().owned().isEmpty());
            assertFalse(s.selfRevive(tick + 21));
        }
        assertFalse(s.perks().purchase(s, r, q, q, 0, 1, true, 400));
    }

    @Test
    void teamQuickReviveNeverSelfRevivesAndDownedCannotPurchase() throws Exception {
        var s = survivor();
        var q = def(Perks.Type.QUICK_REVIVE, 1);
        var r = rules(4, q);
        s.perks().purchase(s, r, q, q, 0, 1, false, 0);
        s.down(1, 100);
        s.perks().down(false, 1, 100);
        assertFalse(s.perks().awaitingSoloRevive());
        assertFalse(s.selfRevive(100));
        assertFalse(s.perks().purchase(s, r, q, q, 0, 1, false, 100));
    }

    @Test
    void parserRejectsUnsafeAndUnknownEffectSettings() {
        String yaml =
                """
schema-version: 1
max-owned: 4
base-weapon-slots: 3
perks:
  - {id: 'test:perk/speed', type: SPEED, name: Speed, asset: 'test:perk/speed', price: 10, enabled: true, min-round: 1, solo: true, team: false, purchase-limit: 2, weight: 1, effects: {movement-multiplier: 1.3}}
""";
        var r = Perks.parse(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1.3, r.require("test:perk/speed").strength());
        for (String bad :
                List.of(
                        yaml.replace("1.3", ".nan"),
                        yaml.replace("1.3", "5"),
                        yaml.replace("movement-multiplier", "unknown"),
                        yaml.replace("price: 10", "price: -1"),
                        yaml.replace("team: false", "team: false, typo: 1")))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Perks.parse(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(r.require("test:perk/speed").allowed(1, false));
    }
}
