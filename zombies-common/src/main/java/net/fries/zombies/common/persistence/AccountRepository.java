package net.fries.zombies.common.persistence;

import net.fries.zombies.common.match.MatchResult;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AccountRepository {
    CompletableFuture<AccountSnapshot> account(UUID playerId);
    CompletableFuture<ResultReceipt> commit(MatchResult result);
    CompletableFuture<Boolean> healthy();
}
