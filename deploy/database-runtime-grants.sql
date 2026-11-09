-- Run as schema owner after migrations, replacing zombies_runtime with an existing login role.
-- Supply its password through your deployment secret store; do not put it in this file.
GRANT CONNECT ON DATABASE zombies TO zombies_runtime;
GRANT USAGE ON SCHEMA public TO zombies_runtime;
GRANT SELECT, INSERT ON accounts, match_results, match_players, reward_ledger TO zombies_runtime;
GRANT UPDATE (tokens, experience, matches_played, kills, highest_round) ON accounts TO zombies_runtime;
GRANT SELECT ON flyway_schema_history TO zombies_runtime;
-- No DELETE, ledger UPDATE, DDL, or database-administration privileges.
