package net.fries.zombies.discord;

import java.util.*;
import java.util.concurrent.*;

/** Bounded, single-in-flight delivery. Network callbacks never run Minecraft work. */
public final class DeliveryQueue {
    public record Message(String id,String channel,String username,String avatar,String content,long created) {
        public boolean chat() {return username!=null;}
    }
    public record Result(boolean success,boolean retryable,boolean ambiguous,long retryAfterMillis) {
        public static Result ok() {return new Result(true,false,false,0);}
    }
    @FunctionalInterface public interface Transport {CompletionStage<Result> send(Message message);}
    public record Health(int queued,boolean inFlight,long sent,long failed,long dropped,long retries,long ambiguous) {}
    private record Pending(Message message,int attempt) {}
    private final ArrayDeque<Pending> queue=new ArrayDeque<>();
    private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();
    private final int capacity,spacing;
    private final Transport transport;
    private final java.util.function.LongSupplier clock;
    private Pending current;
    private long next,sent,failed,dropped,retries,ambiguous;
    private boolean closed;
    public DeliveryQueue(int capacity,int rate,Transport transport) {this(capacity,rate,transport,System::currentTimeMillis);}
    public DeliveryQueue(int capacity,int rate,Transport transport,java.util.function.LongSupplier clock) {if(capacity<1 || rate<1) throw new IllegalArgumentException();this.capacity=capacity;spacing=1000/rate;this.transport=transport;this.clock=clock;}
    public synchronized boolean offer(Message message) {
        if(closed) return false;
        seen.entrySet().removeIf(e->message.created-e.getValue()>120000);
        if(seen.containsKey(message.id)) return false;
        // Batch staff records only, retaining each line and a hard payload bound.
        var tail=queue.peekLast();
        if(!message.chat() && tail!=null && !tail.message.chat() && tail.message.channel.equals(message.channel) && message.created-tail.message.created<1000 && tail.message.content.length()+message.content.length()+1<=1800) {
            queue.removeLast();queue.addLast(new Pending(new Message(tail.message.id,tail.message.channel,null,null,tail.message.content+"\n"+message.content,tail.message.created),tail.attempt));remember(message);return true;
        }
        if(queue.size()+(current==null ? 0 : 1)>=capacity) {
            if(!message.chat()) {var disposable=queue.stream().filter(p->p.message.chat()).findFirst().orElse(null);if(disposable!=null) queue.remove(disposable);else {dropped++;return false;}}
            else {dropped++;return false;}dropped++;
        }
        queue.addLast(new Pending(message,0));remember(message);return true;
    }
    private void remember(Message message) {while(seen.size()>=4096) seen.remove(seen.keySet().iterator().next());seen.put(message.id,message.created);}
    public void tick(long now) {
        Pending p;
        synchronized(this) {
            if(closed || current!=null || now<next) return;
            while(!queue.isEmpty() && now-queue.peekFirst().message.created>120000) {queue.removeFirst();dropped++;}
            if(queue.isEmpty()) return;
            p=queue.removeFirst();current=p;next=now+spacing;
        }
        try {transport.send(p.message).whenComplete((result,error)->complete(p,error==null && result!=null ? result : new Result(false,false,true,0),clock.getAsLong()));}
        catch(RuntimeException error) {complete(p,new Result(false,false,true,0),clock.getAsLong());}
    }
    private synchronized void complete(Pending p,Result result,long now) {
        if(current!=p) return;current=null;
        if(result.success) {sent++;return;}
        if(result.ambiguous) ambiguous++;
        if(!closed && result.retryable && p.attempt<3 && queue.size()<capacity) {
            queue.addFirst(new Pending(p.message,p.attempt+1));next=Math.max(next,now+Math.min(30000,Math.max(result.retryAfterMillis,1000L<<p.attempt)));retries++;
        } else failed++;
    }
    public synchronized Health health() {return new Health(queue.size(),current!=null,sent,failed,dropped,retries,ambiguous);}
    public synchronized void close() {closed=true;dropped+=queue.size();queue.clear();}
}
