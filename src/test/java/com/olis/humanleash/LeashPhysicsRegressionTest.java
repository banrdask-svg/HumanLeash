package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class LeashPhysicsRegressionTest {
    @Test
    void approachingHolderCannotPushStationaryTargetAway() {
        Vector pulled = LeashPhysics.pull(new Vector(7.51, 0, 0), new Vector(), new Vector(),
                new Vector(-0.4, 0, 0), 15);
        assertNotNull(pulled);
        assertTrue(pulled.getX() >= 0, "Поводок не должен отталкивать игрока от держателя.");
    }

    @Test
    void holderSidewaysMovementCannotCreateTargetSidewaysSlide() {
        Vector pulled = LeashPhysics.pull(new Vector(8, 0, 0), new Vector(), new Vector(),
                new Vector(0, 0.4, -0.4), 15);
        assertNotNull(pulled);
        assertEquals(0, pulled.getY(), 0.0000001);
        assertEquals(0, pulled.getZ(), 0.0000001);
    }

    @Test
    void repeatedTensionAgainstObstacleCannotAccumulateVelocity() {
        Vector velocity = new Vector();
        for (int tick = 0; tick < 200; tick++) {
            velocity = LeashPhysics.pull(new Vector(16, 0, 0), velocity, new Vector(), new Vector(), 15);
            assertNotNull(velocity);
            assertTrue(velocity.length() <= 0.55, "Натяжение не должно разгонять игрока каждый тик.");
        }
    }
}
