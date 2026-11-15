package net.fries.zombies.common.gameplay;

import java.util.*;

/** Slab intersections measured along a unit eye ray. Equal overlapping targets fail closed. */
public final class InteractionRay {
    public record Box(double x1,double y1,double z1,double x2,double y2,double z2) {
        public Box {
            if(!Double.isFinite(x1+y1+z1+x2+y2+z2) || x1>=x2 || y1>=y2 || z1>=z2)
                throw new IllegalArgumentException("Invalid interaction box");
        }
        public double distance(double x,double y,double z,double dx,double dy,double dz,double reach) {
            double lo=0,hi=reach;
            double[] origins={x,y,z}, dirs={dx,dy,dz}, mins={x1,y1,z1}, maxs={x2,y2,z2};
            for(int i=0;i<3;i++) {
                if(Math.abs(dirs[i])<1e-9) { if(origins[i]<mins[i] || origins[i]>maxs[i]) return Double.POSITIVE_INFINITY; }
                else {
                    double a=(mins[i]-origins[i])/dirs[i], b=(maxs[i]-origins[i])/dirs[i];
                    lo=Math.max(lo,Math.min(a,b)); hi=Math.min(hi,Math.max(a,b));
                    if(lo>hi) return Double.POSITIVE_INFINITY;
                }
            }
            return lo;
        }
        public double volume() { return (x2-x1)*(y2-y1)*(z2-z1); }
    }
    public record Target(String id,Box box) {}
    public static String pick(List<Target> targets,double x,double y,double z,double dx,double dy,double dz,double reach,double obstruction) {
        if(!Double.isFinite(x+y+z+dx+dy+dz+reach) || reach<=0 || Math.abs(dx*dx+dy*dy+dz*dz-1)>1e-5)
            throw new IllegalArgumentException("Invalid interaction ray");
        Target best=null; double nearest=Math.min(reach,obstruction+.025); boolean ambiguous=false;
        for(var target:targets) {
            double distance=target.box().distance(x,y,z,dx,dy,dz,reach);
            if(!Double.isFinite(distance) || distance>Math.min(reach,obstruction+.025)) continue;
            if(best==null || distance<nearest-1e-6 || Math.abs(distance-nearest)<1e-6 && target.box().volume()<best.box().volume()-1e-6) {
                best=target; nearest=distance; ambiguous=false;
            } else if(Math.abs(distance-nearest)<1e-6 && Math.abs(target.box().volume()-best.box().volume())<1e-6) ambiguous=true;
        }
        return best==null || ambiguous ? null : best.id();
    }
}
