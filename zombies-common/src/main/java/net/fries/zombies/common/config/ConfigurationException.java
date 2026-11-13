package net.fries.zombies.common.config;

public final class ConfigurationException extends IllegalArgumentException {
    public ConfigurationException(String path, String problem) { super(path + ": " + problem); }
}
