package com.olis.humanleash;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

public record LeashPoint(UUID worldId, double x, double y, double z, float yaw, float pitch) {
    public LeashPoint {
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Координаты должны быть конечными числами.");
        }
    }

    public static LeashPoint from(Location location) {
        return new LeashPoint(Objects.requireNonNull(location.getWorld()).getUID(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location toLocation(Server server) {
        World world = server.getWorld(worldId);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    public boolean contains(LeashPoint other, double distance) {
        if (!worldId.equals(other.worldId)) {
            return false;
        }
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return dx * dx + dy * dy + dz * dz <= distance * distance;
    }
}
