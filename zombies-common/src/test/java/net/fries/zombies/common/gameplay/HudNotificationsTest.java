package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HudNotificationsTest {
    @Test
    void headshotsSurviveBodyHitsAndAggregateWithoutFlicker() {
        var q = new HudNotifications();
        q.offer("combat", "HEADSHOT", 20, 0, 20, true);
        q.offer("combat", "HIT", 10, 1, 8, true);
        assertEquals("HEADSHOT", q.visible(1));
        q.offer("combat", "HEADSHOT", 20, 5, 20, true);
        assertEquals("HEADSHOT x2", q.visible(20));
        assertEquals("", q.visible(25));
    }

    @Test
    void warningsTakePriorityAndDoNotLoseLowerPriorityMessages() {
        var q = new HudNotifications();
        q.offer("combat", "HEADSHOT", 20, 0, 20, true);
        q.offer("warning", "Low health", 80, 1, 10, false);
        assertEquals("Low health", q.visible(5));
        assertEquals("HEADSHOT", q.visible(11));
        assertEquals("", q.visible(20));
    }
}
