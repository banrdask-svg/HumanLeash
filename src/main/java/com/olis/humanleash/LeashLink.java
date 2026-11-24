package com.olis.humanleash;

import java.util.Objects;
import java.util.UUID;

public record LeashLink(UUID targetId, String targetName, UUID holderId, String holderName,
                        double distance, LeashPoint anchor) {
    public static final double MIN_DISTANCE = 1.0;
    public static final double MAX_DISTANCE = 128.0;

    public LeashLink {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(holderId, "holderId");
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(anchor, "anchor");
        if (targetId.equals(holderId)) {
            throw new IllegalArgumentException("Нельзя привязать игрока к самому себе.");
        }
        validateDistance(distance, MAX_DISTANCE);
    }

    public static void validateDistance(double distance, double maximum) {
        if (!Double.isFinite(distance) || distance < MIN_DISTANCE || distance > maximum) {
            throw new IllegalArgumentException("Дистанция должна быть от 1 до " + maximum + " блоков.");
        }
    }

    public LeashLink withDistance(double value) {
        return new LeashLink(targetId, targetName, holderId, holderName, value, anchor);
    }

    public LeashLink withAnchor(LeashPoint value, String name) {
        return new LeashLink(targetId, targetName, holderId, name, distance, value);
    }
}
