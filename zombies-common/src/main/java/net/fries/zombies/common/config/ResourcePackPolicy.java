package net.fries.zombies.common.config;

import net.fries.zombies.common.NamespacedId;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Immutable startup policy. Model mappings are explicitly approved deployment assets. */
public record ResourcePackPolicy(
        boolean enabled, String url, String sha1, Map<String, String> models) {
    public ResourcePackPolicy {
        if (url == null || sha1 == null || models == null || models.size() > 256)
            throw new IllegalArgumentException("Invalid resource-pack policy");
        models = Map.copyOf(models);
        models.forEach(
                (asset, model) -> {
                    new NamespacedId(asset);
                    new NamespacedId(model);
                });
        if (enabled) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid resource-pack URL");
            }
            if (url.length() > 2048
                    || !"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !url.equals(uri.toASCIIString()))
                throw new IllegalArgumentException(
                        "Resource-pack URL must be an ASCII HTTPS download without credentials or"
                            + " fragment");
            if (!sha1.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException(
                        "Resource-pack SHA-1 must be 40 lowercase hexadecimal characters");
        }
    }

    public UUID id() {
        return UUID.nameUUIDFromBytes(
                ("friesnet-resource-pack:" + sha1).getBytes(StandardCharsets.UTF_8));
    }
}
