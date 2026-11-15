package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.gameplay.InteractionRay;
import net.fries.zombies.game.match.MatchRun;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

/** One crosshair target shared by every purchase handler and prompt. */
public final class InteractionTargeting {
    public static final double REACH=3.5;
    private record Cached(long tick,Location eye,String target) {}
    private final MatchRun run;
    private final List<InteractionRay.Target> targets;
    private final Map<UUID,Cached> cache=new HashMap<>();
    public InteractionTargeting(MatchRun run) { this.run=run; targets=definitions(run.map); }
    public static InteractionRay.Box point(MapTemplate.Point p) { return new InteractionRay.Box(p.x()-.5,p.y()-.125,p.z()-.5,p.x()+.5,p.y()+1.75,p.z()+.5); }
    public static InteractionRay.Box box(MapTemplate.Box b) { return new InteractionRay.Box(b.x1(),b.y1(),b.z1(),b.x2()+1,b.y2()+1,b.z2()+1); }
    public static InteractionRay.Box object(MapObject o) { return o.property("hitbox","POINT").equals("REGION") ? box(o.region()) : point(o.point()); }
    public static List<InteractionRay.Target> definitions(MapTemplate map) {
        var list=new ArrayList<InteractionRay.Target>();
        for(var gate:map.gates()) list.add(new InteractionRay.Target("gate:"+gate.id(),box(gate.box())));
        int index=0;
        for(var shop:map.shops()) list.add(new InteractionRay.Target("shop:"+(index++),point(shop.point())));
        for(var o:map.objects()) {
            switch(o.kind()) {
                case WALL_WEAPON,AMMO -> list.add(new InteractionRay.Target("shop:"+(index++),object(o)));
                case PERK,PACK_A_PUNCH,MYSTERY_BOX -> list.add(new InteractionRay.Target("object:"+o.id(),object(o)));
                default -> {}
            }
        }
        return List.copyOf(list);
    }
    public String target(Player p) {
        if(p.getWorld()!=run.world) return null;
        var eye=p.getEyeLocation(); var previous=cache.get(p.getUniqueId());
        if(previous!=null && previous.tick==run.tick && previous.eye.equals(eye)) return previous.target;
        var direction=eye.getDirection();
        var block=run.world.rayTraceBlocks(eye,direction,REACH,FluidCollisionMode.NEVER,true);
        double obstruction=block==null ? REACH : block.getHitPosition().distance(eye.toVector());
        var active=targets.stream().filter(t->!t.id().startsWith("gate:") || !run.interactions.open(t.id().substring(5))).toList();
        String selected=InteractionRay.pick(active,eye.getX(),eye.getY(),eye.getZ(),direction.getX(),direction.getY(),direction.getZ(),REACH,obstruction);
        cache.put(p.getUniqueId(),new Cached(run.tick,eye,selected));
        return selected;
    }
    public boolean matches(Player p,String key) { return key.equals(target(p)); }
    public void invalidate() { cache.clear(); }
}
