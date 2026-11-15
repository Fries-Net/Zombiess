package net.fries.zombies.discord;

import java.util.*;

/** Bounded gateway duplicate window and strict channel/identity filtering. */
public final class IncomingBridge {
    private final String guild, channel;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private long window;
    private int count;

    public IncomingBridge(String guild, String channel) {
        this.guild = guild;
        this.channel = channel;
    }

    public synchronized String accept(
            String eventGuild,
            String eventChannel,
            String message,
            boolean bot,
            boolean webhook,
            String author,
            String text,
            int attachments,
            long now) {
        seen.entrySet().removeIf(e -> now - e.getValue() > 600000);
        if (bot
                || webhook
                || !guild.equals(eventGuild)
                || !channel.equals(eventChannel)
                || message == null
                || seen.containsKey(message)) return null;
        seen.entrySet().removeIf(e -> now - e.getValue() > 600000);
        if (now - window >= 1000) {
            window = now;
            count = 0;
        }
        if (count >= 5) return null;
        String content = BridgeText.minecraft(author, text, attachments);
        if (BridgeText.plain(text, 300).isEmpty() && attachments == 0) return null;
        while (seen.size() >= 2048) seen.remove(seen.keySet().iterator().next());
        seen.put(message, now);
        count++;
        return content;
    }
}
