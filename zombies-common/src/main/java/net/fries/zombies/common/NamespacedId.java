package net.fries.zombies.common;

import java.util.Objects;

/** Stable logical identifier shared by gameplay and edition-specific rendering adapters. */
public record NamespacedId(String value) {
    public NamespacedId {
        Objects.requireNonNull(value, "value");
        if (value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid namespaced identifier: " + value);
        }
    }
    @Override public String toString() { return value; }
}
