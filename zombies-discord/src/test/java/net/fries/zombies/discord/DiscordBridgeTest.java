package net.fries.zombies.discord;

import org.junit.jupiter.api.Test;
import net.dv8tion.jda.api.utils.data.DataObject;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordBridgeTest {
    private DeliveryQueue.Message message(String id,String username,long now) {return new DeliveryQueue.Message(id,"12345678901234567",username,null,"literal",now);}
    @Test void everyPayloadSuppressesAllMentionsAndEscapesFormatting() {
        String text=BridgeText.discord("@everyone @here <@123> <@&456> **admin** `x` §4red\u202efake");
        assertFalse(text.contains("@everyone"));assertFalse(text.contains("**admin**"));assertFalse(text.contains("§"));assertFalse(text.contains("\u202e"));
        var m=new DeliveryQueue.Message("1","channel","Player_One",null,text,0);
        var body=DiscordTransport.webhookPayload(m).getObject("allowed_mentions");
        assertEquals(0,body.getArray("parse").length());assertEquals(0,body.getArray("roles").length());assertEquals(0,body.getArray("users").length());assertFalse(body.getBoolean("replied_user"));
        try(var data=DiscordTransport.botPayload(text)) {assertTrue(data.getAllowedMentions().isEmpty());assertFalse(data.isMentionRepliedUser());}
        assertEquals("Player_One",DiscordTransport.webhookPayload(m).getString("username"));
        assertEquals("[Discord] Admin: hello [2 attachments omitted]",BridgeText.minecraft("[Admin]§4","hello",2));
    }
    @Test void incomingRejectsBotsWebhooksForeignChannelsAndDuplicateEvents() {
        var bridge=new IncomingBridge("guild","channel");
        assertNull(bridge.accept("other","channel","1",false,false,"x","test",0,0));
        assertNull(bridge.accept("guild","channel","1",true,false,"x","test",0,0));
        assertNull(bridge.accept("guild","channel","1",false,true,"x","test",0,0));
        assertNotNull(bridge.accept("guild","channel","1",false,false,"x","test",0,0));
        assertNull(bridge.accept("guild","channel","1",false,false,"x","test",0,1));
    }
    @Test void queueBoundsBackpressureBatchesAndDeduplicates() {
        var sent=new ArrayList<DeliveryQueue.Message>();var clock=new AtomicLong();
        var q=new DeliveryQueue(2,2,m->{sent.add(m);return CompletableFuture.completedFuture(DeliveryQueue.Result.ok());},clock::get);
        assertTrue(q.offer(message("1","a",0)));assertFalse(q.offer(message("1","a",0)));assertTrue(q.offer(message("2","b",0)));assertFalse(q.offer(message("3","c",0)));
        assertTrue(q.offer(message("4",null,0)));assertTrue(q.offer(message("5",null,0))); // displace chat, then batch staff
        q.tick(0);q.tick(1);assertEquals(1,sent.size());clock.set(500);q.tick(500);assertEquals(2,sent.size());
        assertEquals("literal\nliteral",sent.getLast().content());assertEquals(2,q.health().dropped());
    }
    @Test void retriesRetainNonceAndRespectCompletionBackoff() {
        var clock=new AtomicLong();var deliveries=new ArrayList<String>();var response=new CompletableFuture<DeliveryQueue.Result>();
        var q=new DeliveryQueue(8,2,m->{deliveries.add(m.id());return deliveries.size()==1 ? response : CompletableFuture.completedFuture(DeliveryQueue.Result.ok());},clock::get);
        q.offer(message("stable-nonce",null,0));q.tick(0);clock.set(5000);response.complete(new DeliveryQueue.Result(false,true,false,2000));q.tick(6999);assertEquals(1,deliveries.size());clock.set(7000);q.tick(7000);assertEquals(List.of("stable-nonce","stable-nonce"),deliveries);assertEquals(1,q.health().sent());
    }
    @Test void ambiguousWebhookFailuresAreNeverRetried() {
        var count=new AtomicInteger();var q=new DeliveryQueue(8,2,m->{count.incrementAndGet();return CompletableFuture.completedFuture(new DeliveryQueue.Result(false,false,true,0));},()->0);
        q.offer(message("1","player",0));q.tick(0);q.tick(2000);assertEquals(1,count.get());assertEquals(1,q.health().ambiguous());assertEquals(1,q.health().failed());
    }
    @Test void localHttpExercisesExplicitRateLimitThenSuccessfulDelivery() throws Exception {
        var count=new AtomicInteger();var payload=new AtomicReference<String>();var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{payload.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));int status=count.incrementAndGet()==1 ? 429 : 204;exchange.getResponseHeaders().set("Retry-After","2");exchange.sendResponseHeaders(status,-1);exchange.close();});server.start();
        try(var client=HttpClient.newHttpClient()) {
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            var first=DiscordTransport.post(client,endpoint,message("1","Player",0)).toCompletableFuture().get(5,TimeUnit.SECONDS);assertTrue(first.retryable());assertEquals(2000,first.retryAfterMillis());
            assertTrue(DiscordTransport.post(client,endpoint,message("1","Player",0)).toCompletableFuture().get(5,TimeUnit.SECONDS).success());
            assertEquals(0,DataObject.fromJson(payload.get()).getObject("allowed_mentions").getArray("parse").length());
        } finally {server.stop(0);}
    }
    @Test void auditSelectsSafeFieldsWithoutSecretsOrPrivateText() {
        var lines=new ArrayList<String>();var handler=new DiscordAuditHandler((category,text)->lines.add(text));
        handler.publish(new LogRecord(Level.SEVERE,"event=storage_failed cause=IOException password=secret token=secret jdbc=secret private chat text"));
        assertEquals(1,lines.size());assertFalse(lines.getFirst().contains("secret"));assertFalse(lines.getFirst().contains("chat"));
        assertThrows(IllegalArgumentException.class,()->DiscordSettings.webhook("http://localhost/api/webhooks/12345678901234567/secret"));
    }
}
