package com.olis.humanleash;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;

public final class LeashManager {
    private final Server server;
    private final Logger logger;
    private final LeashStore store;
    private final Map<UUID, LeashLink> links = new LinkedHashMap<>();
    private final Map<UUID, Long> warnings = new HashMap<>();
    private final Map<UUID, Location> previousPositions = new HashMap<>();
    private final Map<UUID, Vector> movements = new HashMap<>();
    private final Map<UUID, Vector> appliedPulls = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();

    public LeashManager(Server server, Logger logger, LeashStore store) {
        this.server = server;
        this.logger = logger;
        this.store = store;
    }

    public void load() throws IOException, InvalidConfigurationException {
        Map<UUID, LeashLink> loaded = store.load();
        for (LeashLink link : loaded.values()) {
            if (wouldCreateCycle(loaded, link.targetId(), link.holderId())) {
                throw new InvalidConfigurationException("Циклическая привязка: " + link.targetName());
            }
        }
        links.putAll(loaded);
    }

    public Collection<LeashLink> links() {
        return new ArrayList<>(links.values());
    }

    public LeashLink get(UUID targetId) {
        return links.get(targetId);
    }

    public LeashLink find(String name) {
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return links.get(online.getUniqueId());
        }
        return links.values().stream().filter(link -> link.targetName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public void bind(Player target, Player holder, double distance) throws IOException {
        UUID targetId = target.getUniqueId();
        if (target.isDead() || holder.isDead()) {
            throw new IllegalArgumentException("Дождитесь возрождения обоих игроков.");
        }
        if (links.containsKey(targetId)) {
            throw new IllegalArgumentException("Игрок уже привязан. Сначала используйте /leash unbind.");
        }
        if (wouldCreateCycle(links, targetId, holder.getUniqueId())) {
            throw new IllegalArgumentException("Нельзя создавать замкнутую цепочку поводков.");
        }
        LeashLink link = new LeashLink(targetId, target.getName(), holder.getUniqueId(), holder.getName(),
                distance, LeashPoint.from(holder.getLocation()));
        if (!link.anchor().contains(LeashPoint.from(target.getLocation()), distance)) {
            throw new IllegalArgumentException("Для привязки игроки должны находиться в одном мире и не дальше "
                    + distance + " блоков друг от друга.");
        }
        links.put(targetId, link);
        try {
            save();
        } catch (IOException exception) {
            links.remove(targetId);
            throw exception;
        }
        previousPositions.putIfAbsent(targetId, target.getLocation());
        previousPositions.putIfAbsent(holder.getUniqueId(), holder.getLocation());
    }

    public void unbind(UUID targetId) throws IOException {
        LeashLink previous = links.remove(targetId);
        if (previous == null) {
            throw new IllegalArgumentException("Игрок не привязан.");
        }
        try {
            save();
        } catch (IOException exception) {
            links.put(targetId, previous);
            throw exception;
        }
        releasePull(targetId);
        resetMotion(targetId);
        warnings.remove(targetId);
    }

    public void setDistance(UUID targetId, double distance) throws IOException {
        LeashLink previous = links.get(targetId);
        if (previous == null) {
            throw new IllegalArgumentException("Игрок не привязан.");
        }
        links.put(targetId, previous.withDistance(distance));
        try {
            save();
        } catch (IOException exception) {
            links.put(targetId, previous);
            throw exception;
        }
        enforce(targetId);
    }

    public LeashPoint anchor(UUID targetId) {
        LeashLink link = links.get(targetId);
        if (link == null) {
            return null;
        }
        Player holder = server.getPlayer(link.holderId());
        if (holder != null && !holder.isDead()) {
            LeashPoint point = LeashPoint.from(holder.getLocation());
            links.put(targetId, link.withAnchor(point, holder.getName()));
            return point;
        }
        return link.anchor();
    }

    public boolean canMove(UUID playerId, Location destination) {
        LeashLink link = links.get(playerId);
        return link == null || internalTeleports.contains(playerId)
                || (destination != null && anchor(playerId).contains(LeashPoint.from(destination), link.distance()));
    }

    public void tick() {
        captureMovements();
        Set<UUID> visited = new HashSet<>();
        for (UUID targetId : new ArrayList<>(links.keySet())) {
            enforceChain(targetId, visited);
        }
    }

    private void captureMovements() {
        Set<UUID> participants = new HashSet<>();
        for (LeashLink link : links.values()) {
            participants.add(link.targetId());
            participants.add(link.holderId());
        }
        previousPositions.keySet().retainAll(participants);
        movements.keySet().retainAll(participants);
        for (UUID playerId : participants) {
            Player player = server.getPlayer(playerId);
            if (player == null || player.isDead()) {
                previousPositions.remove(playerId);
                movements.remove(playerId);
                appliedPulls.remove(playerId);
                continue;
            }
            Location current = player.getLocation();
            Location previous = previousPositions.put(playerId, current);
            Vector movement = previous != null && previous.getWorld().equals(current.getWorld())
                    ? current.toVector().subtract(previous.toVector()) : new Vector();
            if (movement.lengthSquared() > 16) {
                movement.normalize().multiply(4);
            }
            // Сглаживаем пакетное движение клиента, чтобы задержка пакетов не создавала рывки.
            Vector smoothed = movements.getOrDefault(playerId, new Vector()).clone().multiply(0.5)
                    .add(movement.multiply(0.5));
            movements.put(playerId, smoothed);
        }
    }

    public void resetMotion(UUID playerId) {
        previousPositions.remove(playerId);
        movements.remove(playerId);
        appliedPulls.remove(playerId);
    }

    private void enforceChain(UUID targetId, Set<UUID> visited) {
        if (!visited.add(targetId)) {
            return;
        }
        LeashLink link = links.get(targetId);
        if (link != null) {
            enforceChain(link.holderId(), visited);
            enforce(targetId);
        }
    }

    public void enforce(UUID targetId) {
        LeashLink link = links.get(targetId);
        if (link == null) {
            return;
        }
        LeashPoint point = anchor(targetId);
        Player target = server.getPlayer(targetId);
        if (target == null || target.isDead()) {
            return;
        }
        Location origin = point.toLocation(server);
        if (origin == null) {
            releasePull(targetId);
            warn(targetId, "Мир держателя поводка недоступен: " + link.holderName());
            return;
        }
        Location current = target.getLocation();
        if (!origin.getWorld().equals(current.getWorld())) {
            releasePull(targetId);
            return;
        }
        Player holder = server.getPlayer(link.holderId());
        double holderHeight = holder != null && !holder.isDead() ? holder.getHeight() : 1.8;
        Vector holderAttachment = origin.toVector().add(new Vector(0, holderHeight * 0.5, 0));
        Vector targetAttachment = current.toVector().add(new Vector(0, target.getHeight() * 0.5, 0));
        Vector offset = holderAttachment.subtract(targetAttachment);
        Vector currentVelocity = target.getVelocity();
        Vector previousPull = appliedPulls.get(targetId);
        Vector baseVelocity = LeashPhysics.removePull(currentVelocity, previousPull);
        double previousPullSpeed = previousPull == null || offset.lengthSquared() < 0.000000000001
                ? 0 : previousPull.dot(offset) / offset.length();
        Vector velocity = LeashPhysics.pull(offset, baseVelocity,
                movements.getOrDefault(targetId, new Vector()),
                movements.getOrDefault(link.holderId(), new Vector()), link.distance(), previousPullSpeed);
        if (velocity == null) {
            releasePull(targetId);
            return;
        }
        target.leaveVehicle();
        // Даже одинаковая серверная скорость требует нового пакета: клиент мог затормозить об стену.
        target.setVelocity(velocity);
        Vector direction = offset.normalize();
        appliedPulls.put(targetId, direction.multiply(Math.max(0, velocity.dot(direction))));
        if (velocity.getY() > -0.5 && target.getFallDistance() > 1) {
            target.setFallDistance(1);
        }
    }

    private void releasePull(UUID playerId) {
        Vector applied = appliedPulls.remove(playerId);
        Player player = server.getPlayer(playerId);
        if (applied == null || player == null || player.isDead()) {
            return;
        }
        Vector current = player.getVelocity();
        Vector released = LeashPhysics.removePull(current, applied);
        if (released.distanceSquared(current) > 0.000000000001) {
            player.setVelocity(released);
        }
    }

    public void releaseAllPulls() {
        for (UUID playerId : new HashSet<>(appliedPulls.keySet())) {
            releasePull(playerId);
        }
    }

    public Set<UUID> connectedPlayers(UUID playerId) {
        Set<UUID> connected = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(playerId);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!connected.add(current)) {
                continue;
            }
            LeashLink parent = links.get(current);
            if (parent != null) {
                queue.addLast(parent.holderId());
            }
            for (LeashLink link : links.values()) {
                if (link.holderId().equals(current)) {
                    queue.addLast(link.targetId());
                }
            }
        }
        return connected;
    }

    public boolean isInternalTransfer(UUID playerId) {
        return internalTeleports.contains(playerId);
    }

    public void synchronizeWorld(Player traveler) {
        Set<UUID> connected = connectedPlayers(traveler.getUniqueId());
        if (connected.size() < 2 || server.getPlayer(traveler.getUniqueId()) == null || traveler.isDead()) {
            return;
        }
        Location exit = traveler.getLocation();
        traveler.setPortalCooldown(200);
        synchronizeWorld(connected, exit);
    }

    public void resume(UUID playerId) {
        resetMotion(playerId);
        Player player = server.getPlayer(playerId);
        if (player == null || player.isDead()) {
            return;
        }
        if (links.containsKey(playerId)) {
            Location origin = anchor(playerId).toLocation(server);
            if (origin != null && !origin.getWorld().equals(player.getWorld())) {
                synchronizeWorld(connectedPlayers(playerId), origin);
            }
        } else {
            synchronizeWorld(player);
        }
        enforce(playerId);
    }

    private void synchronizeWorld(Set<UUID> connected, Location exit) {
        internalTeleports.addAll(connected);
        try {
            for (UUID playerId : connected) {
                Player partner = server.getPlayer(playerId);
                if (partner == null || partner.isDead() || partner.getWorld().equals(exit.getWorld())) {
                    continue;
                }
                Location destination = exit.clone();
                destination.setYaw(partner.getLocation().getYaw());
                destination.setPitch(partner.getLocation().getPitch());
                partner.leaveVehicle();
                // Смена измерения требует переноса; при обычном движении используется только скорость.
                if (partner.teleport(destination, TeleportCause.PLUGIN)) {
                    partner.setPortalCooldown(200);
                    partner.setVelocity(new Vector());
                    partner.setFallDistance(0);
                } else {
                    warn(playerId, "Не удалось провести " + partner.getName() + " через портал вместе с поводком.");
                }
            }
        } finally {
            internalTeleports.removeAll(connected);
        }
        for (UUID targetId : connected) {
            resetMotion(targetId);
            anchor(targetId);
        }
        saveLogged();
    }

    public void checkpoint(Player holder) {
        resetMotion(holder.getUniqueId());
        for (LeashLink link : links()) {
            if (link.holderId().equals(holder.getUniqueId())) {
                links.put(link.targetId(), link.withAnchor(LeashPoint.from(holder.getLocation()), holder.getName()));
            }
        }
        saveLogged();
    }

    public void save() throws IOException {
        store.save(links.values());
    }

    public void saveLogged() {
        try {
            save();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Не удалось сохранить привязки в leashes.yml.", exception);
        }
    }

    private void warn(UUID playerId, String message) {
        long now = System.currentTimeMillis();
        if (now - warnings.getOrDefault(playerId, 0L) >= 10_000) {
            logger.warning(message);
            warnings.put(playerId, now);
        }
    }

    static boolean wouldCreateCycle(Map<UUID, LeashLink> links, UUID target, UUID holder) {
        Set<UUID> visited = new HashSet<>();
        UUID current = holder;
        while (current != null) {
            if (current.equals(target) || !visited.add(current)) {
                return true;
            }
            LeashLink link = links.get(current);
            current = link == null ? null : link.holderId();
        }
        return false;
    }
}
