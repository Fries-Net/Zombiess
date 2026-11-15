package net.fries.zombies.discord;

import java.text.Normalizer;
import java.util.UUID;

/** Plain-text boundary. Never parse bridged content as Markdown, commands or Minecraft components. */
public final class BridgeText {
    private BridgeText() {}
    public static String plain(String input,int limit) {
        if(input==null) return "";
        String normalized=Normalizer.normalize(input,Normalizer.Form.NFKC).replaceAll("(?i)§[0-9a-fk-orx]","");
        var out=new StringBuilder();
        normalized.codePoints().filter(c->!Character.isISOControl(c) && Character.getType(c)!=Character.FORMAT && c!=0x7f && c!=0x00a7).limit(limit).forEach(out::appendCodePoint);
        return out.toString().strip();
    }
    public static String discord(String input) {
        var clean=plain(input,600);var out=new StringBuilder();
        clean.codePoints().forEach(c->{if("\\`*_~|><[]()#".indexOf(c)>=0) out.append('\\');out.appendCodePoint(c);if(c=='@') out.append('\u200b');});
        return out.toString();
    }
    public static String identity(String input) {var value=plain(input,32).replaceAll("[^\\p{L}\\p{N} _.-]","").strip();return value.isEmpty() ? "Discord user" : value;}
    public static String minecraft(String identity,String content,int attachments) {
        String text=plain(content,300).replace('@','＠');
        return "[Discord] "+identity(identity)+": "+text+(attachments>0 ? " ["+Math.min(attachments,10)+" attachments omitted]" : "");
    }
}
