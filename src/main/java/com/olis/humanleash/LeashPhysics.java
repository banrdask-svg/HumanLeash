package com.olis.humanleash;

import org.bukkit.util.Vector;

final class LeashPhysics {
    private static final double ELASTIC_DISTANCE_RATIO = 0.5;
    private static final double SPRING_STRENGTH = 0.18;
    private static final double MOVEMENT_DAMPING = 0.35;
    private static final double MAX_PULL_SPEED = 0.45;
    private static final double MAX_SPEED_CHANGE = 0.08;
    private static final double MIN_STRETCH = 0.005;

    private LeashPhysics() {
    }

    static Vector pull(Vector offset, Vector velocity, Vector targetMovement, Vector holderMovement, double length) {
        return pull(offset, velocity, targetMovement, holderMovement, length, 0);
    }

    static Vector pull(Vector offset, Vector velocity, Vector targetMovement, Vector holderMovement,
                       double length, double previousPullSpeed) {
        double distance = offset.length();
        double slack = length * ELASTIC_DISTANCE_RATIO;
        double stretch = distance - slack;
        if (stretch <= MIN_STRETCH) {
            return null;
        }
        Vector direction = offset.clone().multiply(1.0 / distance);
        double holderSpeed = clamp(holderMovement.dot(direction), 0, MAX_PULL_SPEED);
        double measuredSpeed = clamp(targetMovement.dot(direction), 0, MAX_PULL_SPEED);
        double desiredSpeed = clamp(stretch * SPRING_STRENGTH + holderSpeed
                - measuredSpeed * MOVEMENT_DAMPING, 0, MAX_PULL_SPEED);
        double previousSpeed = Math.max(measuredSpeed, clamp(previousPullSpeed, 0, MAX_PULL_SPEED));
        double nextSpeed = previousSpeed + clamp(desiredSpeed - previousSpeed, -MAX_SPEED_CHANGE, MAX_SPEED_CHANGE);
        nextSpeed = Math.min(nextSpeed, stretch * 0.5);
        // Скорость игрока на сервере может оставаться от прошлого пакета. Задаём радиальную скорость,
        // вместо накопления импульсов; поперечное движение и прыжки остаются под управлением игрока.
        Vector perpendicular = velocity.clone().subtract(direction.clone().multiply(velocity.dot(direction)));
        return perpendicular.add(direction.multiply(nextSpeed));
    }

    static Vector removePull(Vector velocity, Vector appliedPull) {
        if (appliedPull == null || appliedPull.lengthSquared() < 0.000000000001) {
            return velocity.clone();
        }
        double speed = appliedPull.length();
        Vector direction = appliedPull.clone().multiply(1.0 / speed);
        double remaining = clamp(velocity.dot(direction), 0, speed);
        return velocity.clone().subtract(direction.multiply(remaining));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
