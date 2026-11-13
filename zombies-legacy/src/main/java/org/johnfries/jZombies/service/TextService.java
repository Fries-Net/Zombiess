package org.johnfries.jZombies.service;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextService {
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private TextService() {
    }

    public static String colorize(String input) {
        if (input == null) {
            return "";
        }
        Matcher matcher = HEX.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
