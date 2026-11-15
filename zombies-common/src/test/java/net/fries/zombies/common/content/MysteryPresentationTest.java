package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class MysteryPresentationTest {
    @Test
    void previewsSlowDownAndRemainBoundedForShortAndLongRolls() {
        var p = MysteryPresentation.defaults();
        for (int duration : List.of(1, 20, 60, 1200)) {
            assertEquals(0, p.rise(0, duration));
            assertEquals(1, p.rise(duration, duration));
            assertEquals(p.slowestCycle(), p.interval(duration, duration));
            assertTrue(
                    p.interval(duration / 4, duration) <= p.interval(duration * 3 / 4, duration));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysteryPresentation(8, 10, 12, 2, 1.25, .5, true, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysteryPresentation(8, 10, 2, 12, 1.25, Double.NaN, true, false));
    }

    @Test
    void existingPoolsRetainPresentationFallbacks() throws Exception {
        var root = Path.of("../zombies-game/src/main/resources");
        var g = Gameplay.load(root.resolve("gameplay.yml"));
        var bytes = Files.readString(root.resolve("mystery-boxes.yml"));
        var legacy =
                bytes.substring(0, bytes.indexOf("presentation:"))
                        + bytes.substring(bytes.indexOf("pools:"));
        assertEquals(
                MysteryPresentation.defaults(),
                MysteryPools.parse(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8), g)
                        .presentation());
        assertFalse(
                MysteryPools.parse(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8), g)
                        .presentation()
                        .itemModels());
    }

    @Test
    void demonstrationHasEveryStationWithoutReplacingLegacyTraining() throws Exception {
        var root = Path.of("../zombies-game/src/main/resources");
        var g = Gameplay.load(root.resolve("gameplay.yml"));
        var map = MapTemplate.load(root.resolve("maps/foundry_lab.yml"), g);
        var perks = Perks.load(root.resolve("perks.yml"));
        perks.validate(map);
        WeaponUpgrades.load(root.resolve("weapon-upgrades.yml"), g).validate(map);
        MysteryPools.load(root.resolve("mystery-boxes.yml"), g).validate(map);
        var stations = map.objects().stream().filter(o -> o.kind() == MapObject.Kind.PERK).toList();
        assertEquals(8, stations.size());
        assertEquals(8, stations.stream().map(o -> o.property("perk", "")).distinct().count());
        for (var a : map.objects())
            for (var b : map.objects())
                if (a != b)
                    assertTrue(
                            Math.abs(a.point().x() - b.point().x()) >= 1.01
                                    || Math.abs(a.point().z() - b.point().z()) >= 1.01,
                            "station hitboxes remain separate");
        assertEquals(3, map.windows().size());
        assertEquals(2, map.shops().size());
        assertEquals(
                "friesnet:map/training",
                MapTemplate.load(root.resolve("maps/training.yml"), g).id().value());
        assertEquals(1, MapTemplate.load(root.resolve("maps/training.yml"), g).objects().size());
    }
}
