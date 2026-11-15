package net.fries.zombies.discord;

import net.fries.zombies.common.content.ContentYaml;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Contains environment variable names only, never credential values. */
public record DiscordSettings(boolean enabled,String tokenEnv,String webhookEnv,String guild,String chatChannel,
        boolean outgoing,boolean incoming,String minecraftChannel,boolean playerNames,String avatarTemplate,Set<String> avatarHosts,
        int capacity,int messagesPerSecond,Map<Event,String> channels) {
    public enum Event {JOIN_LEAVE,MATCH,STAFF,EDITOR,ECONOMY,SERVER,ERROR}
    public DiscordSettings {
        channels=Map.copyOf(channels);avatarHosts=Set.copyOf(avatarHosts);
        if(!tokenEnv.matches("[A-Z_][A-Z0-9_]{0,63}") || !webhookEnv.matches("[A-Z_][A-Z0-9_]{0,63}")
            || !snowflake(guild) || !snowflake(chatChannel) || !Set.of("GLOBAL","MATCH").contains(minecraftChannel)
            || capacity<8 || capacity>2048 || messagesPerSecond<1 || messagesPerSecond>5 || channels.size()!=Event.values().length || channels.values().stream().anyMatch(v->!snowflake(v))) throw new IllegalArgumentException("Invalid Discord settings");
        if(!avatarTemplate.equals("NONE")) trustedAvatar(avatarTemplate.replace("{uuid}",UUID.randomUUID().toString()),avatarHosts);
    }
    private static boolean snowflake(String id) {return id!=null && id.matches("0|[1-9][0-9]{16,19}");}
    private static URI trustedAvatar(String url,Set<String> hosts) {
        var uri=URI.create(url);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getPort()!=-1) throw new IllegalArgumentException("Untrusted avatar source");
        return uri;
    }
    public String avatar(UUID player) {return avatarTemplate.equals("NONE") ? null : trustedAvatar(avatarTemplate.replace("{uuid}",player.toString()),avatarHosts).toString();}
    public boolean botRequired() {return incoming || channels.values().stream().anyMatch(v->!v.equals("0"));}
    public static URI webhook(String value) {
        try {
            var uri=URI.create(value);
            if(!"https".equals(uri.getScheme()) || !Set.of("discord.com","discordapp.com").contains(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().matches("/api(?:/v10)?/webhooks/[0-9]{17,20}/[A-Za-z0-9_-]{20,200}")) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("Invalid Discord webhook credential");}
    }
    public static DiscordSettings load(Path file) throws IOException {
        var n=ContentYaml.parse(ContentYaml.readBytes(file));n.keys("enabled","token-env","webhook-env","guild-id","chat-channel-id","outgoing-chat","incoming-chat","minecraft-channel","include-player-names","avatar-template","avatar-hosts","queue-capacity","messages-per-second","staff-channels");
        var channels=new EnumMap<Event,String>(Event.class);var root=n.node("staff-channels");root.keys(Arrays.stream(Event.values()).map(Enum::name).toArray(String[]::new));
        for(var e:Event.values()) channels.put(e,root.text(e.name()));
        var hosts=new HashSet<String>();var h=n.values().get("avatar-hosts");if(!(h instanceof List<?> list)) throw new IllegalArgumentException("Avatar hosts must be a list");
        for(var host:list) {if(!(host instanceof String s) || !s.matches("[a-z0-9.-]{1,100}")) throw new IllegalArgumentException("Invalid avatar host");hosts.add(s);}
        return new DiscordSettings(n.bool("enabled"),n.text("token-env"),n.text("webhook-env"),n.text("guild-id"),n.text("chat-channel-id"),n.bool("outgoing-chat"),n.bool("incoming-chat"),n.text("minecraft-channel"),n.bool("include-player-names"),n.text("avatar-template"),hosts,n.integer("queue-capacity",8,2048),n.integer("messages-per-second",1,5),channels);
    }
}
