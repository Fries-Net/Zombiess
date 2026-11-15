package net.fries.zombies.common.gameplay;

import java.util.*;

/** Bounded tick-based messages; transient feedback never owns the ammunition line. */
public final class HudNotifications {
    public record Notice(String text, int priority, long expires, int count) {}

    private final Map<String, Notice> messages = new LinkedHashMap<>();

    public void offer(
            String channel, String text, int priority, long tick, int duration, boolean aggregate) {
        if (channel == null
                || text == null
                || text.length() > 160
                || priority < 0
                || priority > 100
                || duration < 1
                || duration > 200) throw new IllegalArgumentException("Invalid HUD notice");
        prune(tick);
        var previous = messages.get(channel);
        if (previous != null && previous.priority() > priority) return;
        int count =
                aggregate && previous != null && previous.text().equals(text)
                        ? Math.min(9999, previous.count() + 1)
                        : 1;
        if (!messages.containsKey(channel) && messages.size() >= 8) return;
        messages.put(channel, new Notice(text, priority, tick + duration, count));
    }

    public String visible(long tick) {
        prune(tick);
        return messages.values().stream()
                .max(Comparator.comparingInt(Notice::priority).thenComparingLong(Notice::expires))
                .map(n -> n.text() + (n.count() > 1 ? " x" + n.count() : ""))
                .orElse("");
    }

    private void prune(long tick) {
        messages.values().removeIf(n -> n.expires() <= tick);
    }
}
