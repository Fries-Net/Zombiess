package net.fries.zombies.common.persistence;

import java.util.UUID;

/** Returned only after a result and all its rewards have committed in one transaction. */
public record ResultReceipt(UUID matchId, String fingerprint, boolean newlyCommitted) { }
