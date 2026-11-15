package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class InteractionRayTest {
    @Test void crosshairDistanceAndOcclusionDetermineTargetRegardlessOfOrder() {
        var left=new InteractionRay.Target("left",new InteractionRay.Box(-1,0,2,0,2,3));
        var right=new InteractionRay.Target("right",new InteractionRay.Box(.1,0,2,1.1,2,3));
        assertEquals("right",InteractionRay.pick(List.of(left,right),.5,1,0,0,0,1,3.5,10));
        assertNull(InteractionRay.pick(List.of(left,right),.5,1,0,0,0,1,3.5,1.5));
        assertNull(InteractionRay.pick(List.of(left,right),.5,1,0,0,0,-1,3.5,10));
        assertNull(InteractionRay.pick(List.of(left,right),.5,1,-2,0,0,1,3.5,10));
    }
    @Test void overlappingVolumesUseFrontSurfaceAndExactAmbiguityNeverCharges() {
        var outer=new InteractionRay.Target("door",new InteractionRay.Box(-1,0,2,1,3,3));
        var behind=new InteractionRay.Target("box",new InteractionRay.Box(-.5,0,2.1,.5,2,3.5));
        assertEquals("door",InteractionRay.pick(List.of(behind,outer),0,1,0,0,0,1,4,10));
        var duplicate=new InteractionRay.Target("duplicate",outer.box());
        assertNull(InteractionRay.pick(List.of(outer,duplicate),0,1,0,0,0,1,4,10));
        assertEquals(2,outer.box().distance(0,1,0,0,0,1,4));
    }
}
