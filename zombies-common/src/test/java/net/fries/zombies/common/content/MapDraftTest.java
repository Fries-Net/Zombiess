package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MapDraftTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    private MapDraft draft() throws Exception {
        return new MapDraft(getClass().getResourceAsStream("/phase2-map.yml").readAllBytes());
    }

    @Test
    void legacyStationsAndSpawnsMigrateWithoutLosingReferences() throws Exception {
        var d = draft();
        var file = directory.resolve("gameplay.yml");
        java.nio.file.Files.write(
                file, getClass().getResourceAsStream("/phase2-gameplay.yml").readAllBytes());
        var map = d.validate(Gameplay.load(file));
        assertTrue(map.shops().isEmpty());
        assertTrue(map.zombieSpawns().isEmpty());
        assertEquals(2, map.wallShops().size());
        assertTrue(map.objects().stream().anyMatch(o -> o.kind() == MapObject.Kind.ZOMBIE_REGION));
        assertArrayEquals(d.bytes(), new MapDraft(d.bytes()).bytes());
    }

    @Test
    void migrationUndoRedoAndPublishedIsolation() throws Exception {
        var d = draft();
        var before = d.bytes();
        assertEquals(3, ContentYaml.parse(before).integer("schema-version", 3, 3));
        d.change(root -> root.put("name", "Edited"));
        assertEquals("Edited", ContentYaml.parse(d.bytes()).text("name"));
        assertTrue(d.undo());
        assertArrayEquals(before, d.bytes());
        assertTrue(d.redo());
        d.change(root -> root.put("name", "Second"));
        assertFalse(d.redo());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stationSettingsSurvivePublicationAndInvalidReferencesFail() throws Exception {
        var file = directory.resolve("gameplay.yml");
        java.nio.file.Files.write(
                file, getClass().getResourceAsStream("/phase2-gameplay.yml").readAllBytes());
        var gameplay = Gameplay.load(file);
        var d = draft();
        var id =
                d.nodes("objects").stream()
                        .filter(n -> n.get("kind").equals("WALL_WEAPON"))
                        .findFirst()
                        .orElseThrow()
                        .get("id")
                        .toString();
        d.change(
                root -> {
                    var station = d.node("objects", id);
                    station.put("rooms", new ArrayList<>(List.of("workshop")));
                    var props = (Map<String, Object>) station.get("properties");
                    props.put("price", "123");
                    props.put("ammo-price", "17");
                });
        var shop = d.validate(gameplay).wallShops().getFirst();
        assertEquals(123, shop.price());
        assertEquals(17, shop.ammoPrice());
        assertEquals(Set.of("workshop"), shop.rooms());
        assertFalse(shop.ammoOnly());
        d.change(root -> d.node("objects", id).put("kind", "AMMO"));
        assertTrue(d.validate(gameplay).wallShops().getFirst().ammoOnly());
        d.change(
                root ->
                        ((Map<String, Object>) d.node("objects", id).get("properties"))
                                .put("price", "free"));
        assertThrows(IllegalArgumentException.class, () -> d.validate(gameplay));
        assertTrue(d.undo());
        d.change(root -> d.node("objects", id).put("rooms", List.of("missing_room")));
        assertThrows(IllegalArgumentException.class, () -> d.validate(gameplay));
    }

    @Test
    void failedMutationRollsBackAndObjectsAreImmutable() throws Exception {
        var d = draft();
        byte[] before = d.bytes();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        d.change(
                                root -> {
                                    root.put("name", "bad");
                                    throw new IllegalArgumentException();
                                }));
        assertArrayEquals(before, d.bytes());
        var o =
                new MapObject(
                        "test",
                        MapObject.Kind.HOLOGRAM,
                        new MapTemplate.Point(0, 64, 0),
                        new MapTemplate.Box(0, 64, 0, 0, 64, 0),
                        Set.of("spawn"),
                        new net.fries.zombies.common.NamespacedId("friesnet:hologram/test"),
                        Map.of("text", "Gold: {gold}"));
        assertThrows(
                UnsupportedOperationException.class, () -> o.properties().put("text", "changed"));
    }
}
