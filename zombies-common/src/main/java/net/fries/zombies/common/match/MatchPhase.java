package net.fries.zombies.common.match;

public enum MatchPhase {
    IDLE, RESERVED, WAITING, COUNTDOWN, STARTING, IN_PROGRESS, PAUSED,
    ENDING, CLEANUP, READY, FAILED, DRAINING
}
