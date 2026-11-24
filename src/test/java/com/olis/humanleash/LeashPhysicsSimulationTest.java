package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LeashPhysicsSimulationTest {
    @ParameterizedTest
    @ValueSource(doubles = {1, 2.5, 12, 15, 128})
    void stationaryHolderDoesNotCauseOvershootOrEndlessSliding(double length) {
        double position = length + 5;
        Vector velocity = new Vector();
        Vector movement = new Vector();
        Vector applied = null;
        for (int tick = 0; tick < 2000; tick++) {
            Vector base = LeashPhysics.removePull(velocity, applied);
            Vector pulled = LeashPhysics.pull(new Vector(-position, 0, 0), base, movement, new Vector(), length);
            if (pulled == null) {
                velocity = base;
                applied = null;
            } else {
                velocity = pulled;
                applied = new Vector(velocity.getX(), 0, 0);
            }
            position += velocity.getX();
            movement = velocity.clone();
            assertTrue(position >= length * 0.5 - 0.0000001, "Игрок не должен пролетать мимо держателя.");
            assertTrue(velocity.length() <= 0.45 + 0.0000001);
        }
        assertTrue(position <= length * 0.5 + 0.006);
        assertEquals(0, velocity.length(), 0.0000001);
    }
}
