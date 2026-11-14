package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WindowDefinitionTest {
    private String definition() {
        return """
id: north
point: {x: 0.5, y: 65, z: -8.5}
orientation: NORTH
boards: [{x: 0, y: 64, z: -10, material: OAK_TRAPDOOR}, {x: 0, y: 65, z: -10, material: OAK_TRAPDOOR}]
spawn-region: {x1: -1, y1: 64, z1: -13, x2: 1, y2: 64, z2: -12}
approach: {x: 0.5, y: 64, z: -10.8}
entry: {x: 0.5, y: 64, z: -8.5}
break-ticks: 40
repair-ticks: 40
repair-gold: 10
round-gold-cap: 60
deny-repair-under-attack: true
required-rooms: [spawn]
asset: friesnet:window/wooden
""";
    }

    private WindowDefinition load(String yaml) {
        return WindowDefinition.load(
                ContentYaml.parse(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void entryHandoffRequiresTheWholeBodyBeyondTheBoardPlaneInAllOrientations() {
        var north = load(definition());
        assertFalse(north.clearsBoardPlane(new MapTemplate.Point(.5, 64, -8.95), .35));
        assertTrue(north.clearsBoardPlane(new MapTemplate.Point(.5, 64, -8.5), .35));
        var values =
                (java.util.Map<String, Object>)
                        ContentYaml.parse(
                                        definition()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .values();
        values.put("orientation", "SOUTH");
        values.put("approach", MapDraft.point(.5, 64, -8.5));
        values.put("entry", MapDraft.point(.5, 64, -10.8));
        values.put("spawn-region", MapDraft.box(-1, 64, -8, 1, 64, -7));
        var south = WindowDefinition.load(new ContentYaml(values));
        assertFalse(south.clearsBoardPlane(new MapTemplate.Point(.5, 64, -10.05), .35));
        assertTrue(south.clearsBoardPlane(new MapTemplate.Point(.5, 64, -10.8), .35));
        String westYaml =
                definition()
                        .replace("x", "!")
                        .replace("z", "x")
                        .replace("!", "z")
                        .replace("NORTH", "WEST");
        var west = load(westYaml);
        assertFalse(west.clearsBoardPlane(new MapTemplate.Point(-8.95, 64, .5), .35));
        assertTrue(west.clearsBoardPlane(new MapTemplate.Point(-8.5, 64, .5), .35));
        var eastValues =
                (java.util.Map<String, Object>)
                        ContentYaml.parse(
                                        westYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .values();
        eastValues.put("orientation", "EAST");
        eastValues.put("approach", MapDraft.point(-8.5, 64, .5));
        eastValues.put("entry", MapDraft.point(-10.8, 64, .5));
        eastValues.put("spawn-region", MapDraft.box(-8, 64, -1, -7, 64, 1));
        var east = WindowDefinition.load(new ContentYaml(eastValues));
        assertFalse(east.clearsBoardPlane(new MapTemplate.Point(-10.05, 64, .5), .35));
        assertTrue(east.clearsBoardPlane(new MapTemplate.Point(-10.8, 64, .5), .35));
        assertThrows(
                IllegalArgumentException.class,
                () -> north.clearsBoardPlane(north.entry(), Double.NaN));
    }

    @Test
    void validWindowRetainsImmutableBoardAndRoomConfiguration() {
        var w = load(definition());
        assertEquals(2, w.boards().size());
        assertEquals(40, w.breakTicks());
        assertEquals(60, w.roundGoldCap());
        assertThrows(UnsupportedOperationException.class, () -> w.boards().clear());
        assertThrows(UnsupportedOperationException.class, () -> w.requiredRooms().clear());
    }

    @Test
    void reversedApproachAndInteriorSpawnsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> load(definition().replace("z: -10.8", "z: -8.5")));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(definition().replace("z1: -13", "z1: -8").replace("z2: -12", "z2: -7")));
    }

    @Test
    void duplicateBoardsAndMixedPlanesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        load(
                                definition()
                                        .replace(
                                                "y: 65, z: -10, material",
                                                "y: 64, z: -10, material")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        load(
                                definition()
                                        .replace(
                                                "y: 65, z: -10, material",
                                                "y: 65, z: -9, material")));
    }

    @Test
    void invalidDurationsAndUnknownPropertiesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> load(definition().replace("break-ticks: 40", "break-ticks: 0")));
        assertThrows(IllegalArgumentException.class, () -> load(definition() + "unknown: true\n"));
    }
}
