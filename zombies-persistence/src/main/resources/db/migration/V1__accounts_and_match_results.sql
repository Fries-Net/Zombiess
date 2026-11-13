CREATE TABLE accounts (
    player_id UUID PRIMARY KEY,
    tokens BIGINT NOT NULL DEFAULT 0 CHECK (tokens >= 0),
    experience BIGINT NOT NULL DEFAULT 0 CHECK (experience >= 0),
    matches_played BIGINT NOT NULL DEFAULT 0 CHECK (matches_played >= 0),
    kills BIGINT NOT NULL DEFAULT 0 CHECK (kills >= 0),
    highest_round INTEGER NOT NULL DEFAULT 0 CHECK (highest_round >= 0)
);

CREATE TABLE match_results (
    match_id UUID PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    map_id VARCHAR(256) NOT NULL,
    map_version VARCHAR(64) NOT NULL,
    pack_version VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('WIN', 'LOSS', 'ABANDONED', 'FAILED')),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (ended_at >= started_at)
);

CREATE TABLE match_players (
    match_id UUID NOT NULL REFERENCES match_results(match_id),
    player_id UUID NOT NULL REFERENCES accounts(player_id),
    rounds INTEGER NOT NULL CHECK (rounds >= 0),
    kills INTEGER NOT NULL CHECK (kills >= 0),
    headshots INTEGER NOT NULL CHECK (headshots >= 0 AND headshots <= kills),
    revives INTEGER NOT NULL CHECK (revives >= 0),
    tokens BIGINT NOT NULL CHECK (tokens >= 0),
    experience BIGINT NOT NULL CHECK (experience >= 0),
    PRIMARY KEY (match_id, player_id)
);

-- Append-only to the runtime role. The match/player/currency key is the reward idempotency key.
CREATE TABLE reward_ledger (
    match_id UUID NOT NULL REFERENCES match_results(match_id),
    player_id UUID NOT NULL REFERENCES accounts(player_id),
    currency VARCHAR(16) NOT NULL CHECK (currency IN ('TOKENS', 'EXPERIENCE')),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (match_id, player_id, currency)
);
CREATE INDEX match_players_history ON match_players(player_id, match_id);
CREATE INDEX match_results_ended ON match_results(ended_at);
CREATE INDEX accounts_highest_round ON accounts(highest_round DESC, player_id);
