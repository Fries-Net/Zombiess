package net.fries.zombies.common.content;

import java.nio.charset.StandardCharsets;

/** Updates only an unchanged shipped template. Custom maps require explicit editor migration. */
public final class MapContentMigration {
    public static byte[] upgradeDefault(byte[] current, byte[] legacy, byte[] replacement) {
        String old = new String(current, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String shipped = new String(legacy, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return old.equals(shipped) ? replacement.clone() : current.clone();
    }
}
