package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class LeashPhysicsTest {
    private static final double EPSILON = 0.0000001;

    @Test
    void ropeHasFreeMovementWithinSlack() {
        assertNull(LeashPhysics.pull(new Vector(), new Vector(), new Vector(), new Vector(), 12));
        assertNull(LeashPhysics.pull(new Vector(5.99, 0, 0), new Vector(0.2, 0.4, 0),
                new Vector(0.2, 0.4, 0), new Vector(), 12));
    }

    @Test
    void stationaryTargetIsPulledGentlyTowardHolder() {
        Vector pulled = LeashPhysics.pull(new Vector(7, 0, 0), new Vector(), new Vector(), new Vector(), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() > 0 && pulled.getX() <= 0.08 + EPSILON);
        assertEquals(0, pulled.getY());
        assertEquals(0, pulled.getZ());
    }

    @Test
    void outwardMovementIsStoppedWithoutLaunchingTarget() {
        Vector movement = new Vector(-0.2, 0, 0);
        Vector pulled = LeashPhysics.pull(new Vector(7, 0, 0), movement, movement, new Vector(), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() > 0 && pulled.getX() <= 0.45);
    }

    @Test
    void approachingPlayerIsNotAcceleratedPastHolder() {
        Vector movement = new Vector(0.2, 0, 0);
        Vector pulled = LeashPhysics.pull(new Vector(6.02, 0, 0), movement, movement, new Vector(), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() >= 0 && pulled.getX() <= 0.01 + EPSILON);
    }

    @Test
    void verticalRopeDoesNotInjectHorizontalMotion() {
        Vector pulled = LeashPhysics.pull(new Vector(0, 7, 0), new Vector(), new Vector(), new Vector(), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getY() > 0 && pulled.getY() <= 0.45);
        assertEquals(0, pulled.getX());
        assertEquals(0, pulled.getZ());
    }

    @Test
    void holderWalkingAwayIncreasesTension() {
        Vector standing = LeashPhysics.pull(new Vector(6.1, 0, 0), new Vector(), new Vector(), new Vector(), 12);
        Vector walking = LeashPhysics.pull(new Vector(6.1, 0, 0), new Vector(), new Vector(),
                new Vector(0.2, 0.1, -0.3), 12);
        assertNotNull(standing);
        assertNotNull(walking);
        assertTrue(walking.getX() > standing.getX());
        assertEquals(0, walking.getY());
        assertEquals(0, walking.getZ());
    }

    @Test
    void movingTogetherHasBoundedInwardSpeed() {
        Vector movement = new Vector(0.2, 0, 0);
        Vector pulled = LeashPhysics.pull(new Vector(7, 0, 0), movement, movement, movement, 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() >= 0.2 && pulled.getX() <= 0.45);
    }

    @Test
    void horizontalRopePreservesJumpAndSidewaysMotion() {
        Vector movement = new Vector(0, 0.42, 0.3);
        Vector pulled = LeashPhysics.pull(new Vector(7, 0, 0), movement, movement, new Vector(), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() > 0);
        assertEquals(0.42, pulled.getY(), EPSILON);
        assertEquals(0.3, pulled.getZ(), EPSILON);
    }

    @Test
    void configuredLengthScalesSlack() {
        assertNull(LeashPhysics.pull(new Vector(9, 0, 0), new Vector(), new Vector(), new Vector(), 20));
        assertNotNull(LeashPhysics.pull(new Vector(9, 0, 0), new Vector(), new Vector(), new Vector(), 10));
    }

    @Test
    void extremeDistanceAndMovementCannotLaunchPlayer() {
        Vector pulled = LeashPhysics.pull(new Vector(1200, 0, 0), new Vector(100, 0.42, 0.3),
                new Vector(100, 0, 0), new Vector(1000, 0, 0), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() >= 0 && pulled.getX() <= 0.45);
        assertEquals(0.42, pulled.getY(), EPSILON);
        assertEquals(0.3, pulled.getZ(), EPSILON);
    }

    @Test
    void staleServerVelocityIsReplacedInsteadOfAccumulated() {
        Vector pulled = LeashPhysics.pull(new Vector(7, 0, 0), new Vector(2, 0, 0),
                new Vector(), new Vector(), 12);
        assertNotNull(pulled);
        assertTrue(pulled.getX() > 0 && pulled.getX() <= 0.45);
    }

    @Test
    void releasingPullPreservesPerpendicularMotionAndExternalKnockback() {
        Vector applied = new Vector(0.08, 0, 0);
        assertEquals(new Vector(0, 0.42, 0.3),
                LeashPhysics.removePull(new Vector(0.08, 0.42, 0.3), applied));
        assertEquals(new Vector(-0.5, 0.42, 0.3),
                LeashPhysics.removePull(new Vector(-0.5, 0.42, 0.3), applied));
        Vector released = LeashPhysics.removePull(new Vector(1, 0, 0), applied);
        assertEquals(0.92, released.getX(), EPSILON);
    }

    @Test
    void inputsAreNotMutatedBySpringCalculation() {
        Vector offset = new Vector(7, 0, 0);
        Vector velocity = new Vector(0.2, 0.42, 0.1);
        Vector targetMovement = new Vector(0.1, 0.2, 0.3);
        Vector holderMovement = new Vector(0.3, 0.2, 0.1);
        LeashPhysics.pull(offset, velocity, targetMovement, holderMovement, 12);
        assertEquals(new Vector(7, 0, 0), offset);
        assertEquals(new Vector(0.2, 0.42, 0.1), velocity);
        assertEquals(new Vector(0.1, 0.2, 0.3), targetMovement);
        assertEquals(new Vector(0.3, 0.2, 0.1), holderMovement);
    }
}
