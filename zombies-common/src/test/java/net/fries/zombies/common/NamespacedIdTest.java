package net.fries.zombies.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamespacedIdTest {
    @Test void identifiersAreStableAndEditionNeutral() {
        assertEquals("friesnet:weapon/ak47/diamond_skin", new NamespacedId("friesnet:weapon/ak47/diamond_skin").value());
        for (String invalid : new String[]{"1001", "Friesnet:weapon", "friesnet:../secret", "friesnet:weapon\\ak47", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new NamespacedId(invalid));
        }
    }
}
