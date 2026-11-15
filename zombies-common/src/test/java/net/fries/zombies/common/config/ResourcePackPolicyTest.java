package net.fries.zombies.common.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class ResourcePackPolicyTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void disabledPolicyNeedsNoHostingOrModelActivation() {
        var p = new ResourcePackPolicy(false, "", "", Map.of());
        assertFalse(p.enabled());
        assertTrue(p.models().isEmpty());
    }

    @Test
    void rejectsUnsafeUrlsAndUnpinnedDownloads() {
        for (String url :
                new String[] {
                    "http://example.org/pack.zip",
                    "https://user:secret@example.org/pack.zip",
                    "https://example.org/pack.zip#x",
                    "not a URL"
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ResourcePackPolicy(true, url, HASH, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourcePackPolicy(true, "https://example.org/v3.zip", "", Map.of()));
    }

    @Test
    void mappingsAreImmutableValidatedAndVersionIdentityFollowsHash() {
        var p =
                new ResourcePackPolicy(
                        true,
                        "https://example.org/v3.zip",
                        HASH,
                        Map.of("friesnet:weapon/ak47", "friesnet:weapon/ak47"));
        assertThrows(UnsupportedOperationException.class, () -> p.models().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourcePackPolicy(false, "", "", Map.of("bad", "friesnet:weapon/ak47")));
        assertEquals(
                p.id(),
                new ResourcePackPolicy(true, "https://cdn.example.org/v3.zip", HASH, Map.of())
                        .id());
        assertNotEquals(
                p.id(), new ResourcePackPolicy(true, p.url(), "0".repeat(40), Map.of()).id());
    }
}
