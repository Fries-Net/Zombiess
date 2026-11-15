# Optional Discord adapter

Discord is disabled by default and isolated in `zombies-discord`. Gameplay, purchases, persistent
results and recovery never depend on Discord availability. The Paper adapter queues plain data;
gateway/HTTP operations and delivery retries run off the server thread. Restart after changing
`discord.yml` or its environment credentials. `/zombies discord` requires `zombies.admin` and reports
gateway state, queue depth, in-flight work, successes, failures, drops, retries and ambiguous results.

## Setup

1. Create a Discord application/bot and invite it only to the intended guild. Grant **View Channel**
   and **Send Messages** for configured staff channels. It does not require Administrator, member
   lists, presence, voice, moderation, message management or command execution permissions.
2. For incoming chat, also grant View Channel in the bridge channel and enable **Message Content
   Intent** in the developer portal. The adapter requests only Guild Messages and Message Content
   when incoming chat is enabled; outbound staff logging does not request message-content access.
   It listens to new messages and does not fetch message history. Large verified applications may
   require Discord approval for message-content access.
3. To render Minecraft usernames/skin heads, create one webhook in the chat channel. A staff member
   needs Manage Webhooks to create it; the running bot does not. Put its full HTTPS URL in the
   environment variable named by `webhook-env` (default `FNZ_DISCORD_WEBHOOK`). Put the bot token in
   `token-env` (default `FNZ_DISCORD_TOKEN`). Use protected service environment settings, never source
   files, command arguments or committed configuration. The adapter never logs credential values,
   request URLs, raw exception messages or private chat in diagnostics.
4. Set `enabled`, the guild/channel IDs and the required outgoing/incoming switches. Staff event
   categories each select a channel ID; `'0'` disables that category. Restrict staff channels using
   Discord permissions. `GLOBAL` bridges normal uncancelled Minecraft chat; `MATCH` bridges active
   match-world participants and delivers incoming content only to that world.

The avatar template accepts a UUID substitution and HTTPS hosts from an explicit allowlist. The
server never downloads avatar images; Discord fetches that URL. The default trusted host is
`mc-heads.net`. Set `avatar-template: NONE` to disable avatars. Consider third-party avatar access and
inform players that opted-in chat is relayed to Discord. `include-player-names: false` anonymizes
staff join/leave/action records; chat identity remains the Minecraft username when chat is enabled.
No IP addresses, private messages, command arguments, SQL, passwords or raw exceptions are relayed.

## Events and safety

Staff filters cover joins/leaves, match start/finish with victory/failure outcome, administrative
command attempts, successful draft saves/publications, economy/commit/exploit structured events,
startup/shutdown and critical errors. Command records contain the verb/subcommand with arguments
redacted. Suspicious interaction rates (30 main-hand events in one second inside a match) produce a
review signal when ECONOMY logging is enabled; they never impose an automatic penalty.

All outbound payloads explicitly set empty allowed mention sets, including everyone/here, users,
roles and reply mentions. Content is also escaped and mention text neutralized. Webhook usernames
are plain identities. Incoming messages carry a fixed `[Discord]` origin, use literal text components,
strip control/colour/formatting characters, have a 300-character content bound and omit attachment
URLs/files. Bots and webhooks are ignored, configured guild/channel identity is required and message
IDs are deduplicated in a bounded ten-minute window. Server-generated chat never re-enters player
chat events, preventing bridge loops. No Discord message can invoke a Minecraft command.

The delivery queue has one request in flight, configurable capacity/rate, staff batching and a
two-minute event lifetime. Saturation drops chat before replacing staff work where possible; counters
expose loss. Incoming delivery and Minecraft chat capture have separate bounded queues/rate limits.
Discord downtime never blocks a tick. JDA reconnects with bounded backoff. Missing credentials disable
the connection with a generic diagnostic while gameplay remains available.

Bot requests reuse an enforced nonce on up to three retries. Webhooks lack an equivalent idempotency
guarantee: only an explicit HTTP 429 rejection is retried. Ambiguous network/5xx failures are counted
and dropped to avoid duplicate chat; this deliberately permits message loss. Shutdown gives delivery
a two-second best-effort asynchronous drain, then closes the gateway/HTTP client. Queues are not
durable, so crashes can lose logs/chat; they never replay purchases or match results.

## Verification

Tests inspect real payload mention fields, escaping/identities, gateway filtering/deduplication,
queue bounds/batching, retry nonce/backoff, ambiguous-failure policy, credential-free diagnostics and
an actual local HTTP 429/204 exchange. CI runs normal multiplayer/recovery tests with Discord disabled
and a separate real Paper match with intentionally missing credentials. A live Discord guild test is
still required before production: verify all permissions, category routes, webhook name/avatar,
two-way chat, mention attempts, attachments, reconnects, queue diagnostics and shutdown delivery.
No live token or webhook was provided for this development checkpoint.

The pinned implementation uses [JDA 6.7.0](https://github.com/discord-jda/JDA/releases/tag/v6.7.0),
including its enforced message nonce support. Protocol rules follow the official
[webhook documentation](https://github.com/discord/discord-api-docs/blob/main/developers/resources/webhook.mdx)
and [gateway intents](https://github.com/discord/discord-api-docs/blob/main/developers/events/gateway.mdx).
