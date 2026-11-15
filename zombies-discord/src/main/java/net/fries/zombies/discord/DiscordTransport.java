package net.fries.zombies.discord;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.data.*;
import net.dv8tion.jda.api.utils.messages.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Optional JDA gateway/bot with an explicit webhook boundary. Construct off the server thread. */
public final class DiscordTransport implements DeliveryQueue.Transport, AutoCloseable {
    private final DiscordSettings settings;
    private final URI webhook;
    private final HttpClient http;
    private final ExecutorService httpThreads =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        var t = new Thread(r, "zombies-discord-http");
                        t.setDaemon(true);
                        return t;
                    });
    private final JDA bot;

    public DiscordTransport(
            DiscordSettings settings,
            Function<String, String> environment,
            Consumer<String> incoming) {
        this.settings = settings;
        String hook = settings.outgoing() ? environment.apply(settings.webhookEnv()) : null;
        webhook = hook == null || hook.isBlank() ? null : DiscordSettings.webhook(hook);
        if (settings.outgoing() && webhook == null)
            throw new IllegalArgumentException("Missing webhook environment credential");
        http =
                HttpClient.newBuilder()
                        .executor(httpThreads)
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        if (settings.botRequired()) {
            String token = environment.apply(settings.tokenEnv());
            if (token == null || token.isBlank()) {
                closeHttp();
                throw new IllegalArgumentException("Missing bot environment credential");
            }
            var receiver = new IncomingBridge(settings.guild(), settings.chatChannel());
            try {
                bot =
                        JDABuilder.createLight(
                                        token,
                                        settings.incoming()
                                                ? List.of(
                                                        GatewayIntent.GUILD_MESSAGES,
                                                        GatewayIntent.MESSAGE_CONTENT)
                                                : List.of())
                                .setEnableShutdownHook(false)
                                .setAutoReconnect(true)
                                .setMaxReconnectDelay(60)
                                .addEventListeners(
                                        new ListenerAdapter() {
                                            @Override
                                            public void onMessageReceived(MessageReceivedEvent e) {
                                                if (!settings.incoming() || !e.isFromGuild())
                                                    return;
                                                String content =
                                                        receiver.accept(
                                                                e.getGuild().getId(),
                                                                e.getChannel().getId(),
                                                                e.getMessageId(),
                                                                e.getAuthor().isBot(),
                                                                e.isWebhookMessage(),
                                                                e.getAuthor().getName(),
                                                                e.getMessage().getContentRaw(),
                                                                e.getMessage()
                                                                        .getAttachments()
                                                                        .size(),
                                                                System.currentTimeMillis());
                                                if (content != null) incoming.accept(content);
                                            }
                                        })
                                .build();
            } catch (RuntimeException invalid) {
                closeHttp();
                throw new IllegalArgumentException("Discord bot could not initialize");
            }
        } else bot = null;
    }

    public boolean ready() {
        return bot == null || bot.getStatus() == JDA.Status.CONNECTED;
    }

    public String status() {
        return bot == null ? "webhook-only" : bot.getStatus().name();
    }

    public static MessageCreateData botPayload(String text) {
        return new MessageCreateBuilder()
                .setContent(text)
                .setAllowedMentions(Collections.emptyList())
                .mentionRepliedUser(false)
                .setSuppressEmbeds(true)
                .build();
    }

    public static DataObject webhookPayload(DeliveryQueue.Message message) {
        var json =
                DataObject.empty()
                        .put("content", message.content())
                        .put("username", BridgeText.identity(message.username()))
                        .put(
                                "allowed_mentions",
                                DataObject.empty()
                                        .put("parse", DataArray.empty())
                                        .put("users", DataArray.empty())
                                        .put("roles", DataArray.empty())
                                        .put("replied_user", false))
                        .put("flags", 4);
        if (message.avatar() != null) json.put("avatar_url", message.avatar());
        return json;
    }

    public static CompletionStage<DeliveryQueue.Result> post(
            HttpClient client, URI endpoint, DeliveryQueue.Message message) {
        var request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FriesNetZombies-Discord/1.0")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        webhookPayload(message).toString()))
                        .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle(
                        (response, error) -> {
                            if (error != null)
                                return new DeliveryQueue.Result(false, false, true, 0);
                            int status = response.statusCode();
                            if (status >= 200 && status < 300) return DeliveryQueue.Result.ok();
                            long delay = 1000;
                            try {
                                delay =
                                        (long)
                                                (Double.parseDouble(
                                                                response.headers()
                                                                        .firstValue("Retry-After")
                                                                        .orElse("1"))
                                                        * 1000);
                            } catch (RuntimeException ignored) {
                            }
                            // A webhook has no enforceable nonce. Retry only an explicit rejection;
                            // ambiguous
                            // timeouts/server failures may already have posted and must not produce
                            // duplicates.
                            return new DeliveryQueue.Result(
                                    false,
                                    status == 429,
                                    status >= 500,
                                    Math.clamp(delay, 1000, 30000));
                        });
    }

    @Override
    public CompletionStage<DeliveryQueue.Result> send(DeliveryQueue.Message message) {
        if (message.chat())
            return webhook == null
                    ? CompletableFuture.completedFuture(
                            new DeliveryQueue.Result(false, false, false, 0))
                    : post(http, URI.create(webhook + "?wait=true"), message);
        if (bot == null)
            return CompletableFuture.completedFuture(
                    new DeliveryQueue.Result(false, false, false, 0));
        var channel = bot.getTextChannelById(message.channel());
        if (channel == null || !channel.getGuild().getId().equals(settings.guild()))
            return CompletableFuture.completedFuture(
                    new DeliveryQueue.Result(false, false, false, 0));
        // JDA 6.7 sends enforce_nonce=true. Reuse the same nonce on bounded retries.
        return channel.sendMessage(botPayload(message.content()))
                .setNonce(message.id())
                .timeout(15, TimeUnit.SECONDS)
                .submit()
                .handle(
                        (sent, error) ->
                                error == null
                                        ? DeliveryQueue.Result.ok()
                                        : new DeliveryQueue.Result(false, true, true, 1000));
    }

    private void closeHttp() {
        httpThreads.shutdownNow();
    }

    @Override
    public void close() {
        if (bot != null) bot.shutdownNow();
        http.shutdownNow();
        closeHttp();
    }
}
