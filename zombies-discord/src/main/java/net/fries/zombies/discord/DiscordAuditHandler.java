package net.fries.zombies.discord;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.*;

/** Select structured fields only. Never forward exception messages, command arguments or raw logs. */
public final class DiscordAuditHandler extends Handler {
    private final BiConsumer<DiscordSettings.Event,String> sink;
    private static final Set<String> FIELDS=Set.of("event","match","from","to","map","version","outcome","cause");
    public DiscordAuditHandler(BiConsumer<DiscordSettings.Event,String> sink) {this.sink=sink;}
    @Override public void publish(LogRecord record) {
        if(record==null || record.getMessage()==null) return;
        var fields=new LinkedHashMap<String,String>();
        for(var token:record.getMessage().split(" ")) {
            int at=token.indexOf('=');if(at<1) continue;String key=token.substring(0,at),value=token.substring(at+1);
            if(FIELDS.contains(key) && value.matches("[a-zA-Z0-9_:/.-]{1,100}")) fields.put(key,value);
        }
        var event=fields.get("event");if(event==null || event.startsWith("discord_")) return;
        DiscordSettings.Event category;
        if(record.getLevel().intValue()>=Level.SEVERE.intValue()) category=DiscordSettings.Event.ERROR;
        else if(Set.of("match_playable","match_finished").contains(event)) category=DiscordSettings.Event.MATCH;
        else if(event.startsWith("map_") && (event.contains("published") || event.contains("modified"))) category=DiscordSettings.Event.EDITOR;
        else if(event.contains("economy") || event.contains("exploit") || event.contains("commit") || event.contains("result_retry")) category=DiscordSettings.Event.ECONOMY;
        else if(Set.of("instance_ready","storage_closed").contains(event)) category=DiscordSettings.Event.SERVER;
        else return;
        sink.accept(category,fields.toString());
    }
    @Override public void flush() {}
    @Override public void close() {}
}
