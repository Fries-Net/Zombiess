package net.fries.zombies.persistence;

public record DatabaseMetrics(boolean ready, int queued, int active, long completed,
                              long failures, long lastOperationMicros) { }
