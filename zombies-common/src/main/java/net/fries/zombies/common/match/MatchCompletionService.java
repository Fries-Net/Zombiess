package net.fries.zombies.common.match;

import net.fries.zombies.common.persistence.AccountRepository;
import net.fries.zombies.common.persistence.ResultReceipt;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Persistence gates lifecycle release; adapters retain the immutable result for retries on failure. */
public final class MatchCompletionService {
    private final AccountRepository accounts;
    public MatchCompletionService(AccountRepository accounts) { this.accounts = Objects.requireNonNull(accounts); }
    public CompletableFuture<ResultReceipt> complete(MatchSession session, MatchResult result) {
        session.finish(result);
        return accounts.commit(result).thenApply(receipt -> {
            session.acknowledge(receipt);
            return receipt;
        });
    }
}
