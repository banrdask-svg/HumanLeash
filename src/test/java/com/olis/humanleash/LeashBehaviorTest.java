package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeashBehaviorTest {
    @TempDir
    Path directory;

    private Server server;
    private World world;
    private LeashManager manager;
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<UUID, AtomicReference<Location>> positions = new HashMap<>();
    private final Map<UUID, AtomicReference<Vector>> velocities = new HashMap<>();
    private Player holder;
    private Player target;

    @BeforeEach
    void setUp() throws Exception {
        server = mock(Server.class);
        world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getPlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
        when(server.getPlayerExact(anyString())).thenAnswer(call -> online.values().stream()
                .filter(player -> player.getName().equals(call.getArgument(0))).findFirst().orElse(null));
        holder = player("Holder", 0);
        target = player("Target", 10);
        manager = new LeashManager(server, Logger.getLogger("HumanLeashTest"),
                new LeashStore(directory.resolve("leashes.yml")));
        manager.bind(target, holder, 15);
    }

    private Player player(String name, double x) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        AtomicReference<Location> location = new AtomicReference<>(new Location(world, x, 64, 0));
        AtomicReference<Vector> velocity = new AtomicReference<>(new Vector());
        positions.put(id, location);
        velocities.put(id, velocity);
        online.put(id, player);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getWorld()).thenAnswer(call -> location.get().getWorld());
        when(player.getLocation()).thenAnswer(call -> location.get().clone());
        when(player.getVelocity()).thenAnswer(call -> velocity.get().clone());
        when(player.getWidth()).thenReturn(0.6);
        when(player.getHeight()).thenReturn(1.8);
        doAnswer(call -> {
            velocity.set(((Vector) call.getArgument(0)).clone());
            return null;
        }).when(player).setVelocity(any(Vector.class));
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN)))
                .thenAnswer(call -> {
                    location.set(((Location) call.getArgument(0)).clone());
                    return true;
                });
        return player;
    }

    @Test
    void tautLeashPullsWithoutTeleportingOrChangingPosition() {
        Location outside = new Location(world, 15.1, 64, 0, 90, 30);
        positions.get(target.getUniqueId()).set(outside);
        velocities.get(target.getUniqueId()).set(new Vector(0.4, 0.42, 0.2));
        manager.enforce(target.getUniqueId());
        assertEquals(outside, target.getLocation());
        assertTrue(target.getVelocity().getX() < 0);
        assertEquals(0.42, target.getVelocity().getY());
        assertEquals(0.2, target.getVelocity().getZ());
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(target, never()).teleport(any(Location.class));
    }

    @Test
    void blocksPearlAndCommandTeleportsOutsideRadius() {
        LeashListener listener = new LeashListener(null, manager);
        for (PlayerTeleportEvent.TeleportCause cause : new PlayerTeleportEvent.TeleportCause[] {
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL, PlayerTeleportEvent.TeleportCause.COMMAND,
                PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT}) {
            PlayerTeleportEvent event = new PlayerTeleportEvent(target, target.getLocation(),
                    new Location(world, 100, 64, 0), cause);
            listener.onTeleport(event);
            assertTrue(event.isCancelled());
        }
        PlayerTeleportEvent inside = new PlayerTeleportEvent(target, target.getLocation(),
                new Location(world, 5, 64, 0), PlayerTeleportEvent.TeleportCause.COMMAND);
        listener.onTeleport(inside);
        assertFalse(inside.isCancelled());
    }

    @Test
    void movingHolderPullsTargetAndKeepsBinding() {
        positions.get(holder.getUniqueId()).set(new Location(world, 50, 64, 0));
        manager.tick();
        assertEquals(10, target.getLocation().getX());
        assertTrue(target.getVelocity().getX() > 0);
        assertNotNull(manager.get(target.getUniqueId()));
        verify(target).leaveVehicle();
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void holderLogoutLeavesLastAnchorAndRestartRestoresIt() throws Exception {
        positions.get(holder.getUniqueId()).set(new Location(world, 5, 64, 0));
        manager.checkpoint(holder);
        online.remove(holder.getUniqueId());
        assertTrue(manager.canMove(target.getUniqueId(), new Location(world, 20, 64, 0)));
        assertFalse(manager.canMove(target.getUniqueId(), new Location(world, 20.1, 64, 0)));
        LeashManager restarted = new LeashManager(server, Logger.getLogger("HumanLeashTest"),
                new LeashStore(directory.resolve("leashes.yml")));
        restarted.load();
        assertNotNull(restarted.get(target.getUniqueId()));
        assertFalse(restarted.canMove(target.getUniqueId(), new Location(world, 20.1, 64, 0)));
    }

    @Test
    void offlineTargetCanBeUnboundByName() throws Exception {
        online.remove(target.getUniqueId());
        assertEquals(target.getUniqueId(), manager.find("target").targetId());
        manager.unbind(manager.find("target").targetId());
        assertNull(manager.find("target"));
        assertTrue(new LeashStore(directory.resolve("leashes.yml")).load().isEmpty());
    }

    @Test
    void shorterLengthPullsPlayerImmediately() throws Exception {
        manager.setDistance(target.getUniqueId(), 3);
        assertEquals(3, manager.get(target.getUniqueId()).distance());
        assertEquals(10, target.getLocation().getX());
        assertTrue(target.getVelocity().getX() < 0);
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void slackLeashDoesNotChangeVelocity() {
        positions.get(target.getUniqueId()).set(new Location(world, 5, 64, 0));
        manager.tick();
        verify(target, never()).setVelocity(any(Vector.class));
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void holderWalkingAffectsPullEvenWhenBukkitVelocityIsZero() {
        positions.get(target.getUniqueId()).set(new Location(world, 7.6, 64, 0));
        manager.resetMotion(target.getUniqueId());
        positions.get(holder.getUniqueId()).set(new Location(world, -0.04, 64, 0));
        assertEquals(new Vector(), holder.getVelocity());
        manager.tick();
        double walkingSpeed = -target.getVelocity().getX();
        manager.resetMotion(holder.getUniqueId());
        manager.resetMotion(target.getUniqueId());
        velocities.get(target.getUniqueId()).set(new Vector());
        manager.tick();
        assertTrue(walkingSpeed > -target.getVelocity().getX());
        assertTrue(walkingSpeed <= 0.45);
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void teleportIsNotMistakenForHolderWalking() {
        positions.get(target.getUniqueId()).set(new Location(world, 15.2, 64, 0));
        manager.resetMotion(target.getUniqueId());
        Location from = holder.getLocation();
        Location to = new Location(world, -100, 64, 0);
        positions.get(holder.getUniqueId()).set(to);
        new LeashListener(null, manager).onTeleported(new PlayerTeleportEvent(holder, from, to,
                PlayerTeleportEvent.TeleportCause.COMMAND));
        manager.tick();
        assertTrue(target.getVelocity().getX() >= -0.45);
        assertTrue(target.getVelocity().getX() < 0);
    }

    @Test
    void suspendedPlayerGetsVanillaFallDistanceLimit() {
        positions.get(target.getUniqueId()).set(new Location(world, 0, 50, 0));
        when(target.getFallDistance()).thenReturn(20F);
        manager.enforce(target.getUniqueId());
        assertTrue(target.getVelocity().getY() > 0);
        verify(target).setFallDistance(1);
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void slackLeashDoesNotChangeFallDamage() {
        positions.get(target.getUniqueId()).set(new Location(world, 5, 64, 0));
        when(target.getFallDistance()).thenReturn(20F);
        manager.enforce(target.getUniqueId());
        verify(target, never()).setFallDistance(anyFloat());
    }

    @Test
    void slackLeashRemovesResidualPullWithoutCancellingJumpOrStrafe() {
        manager.enforce(target.getUniqueId());
        double pull = target.getVelocity().getX();
        velocities.get(target.getUniqueId()).set(new Vector(pull, 0.42, 0.3));
        positions.get(target.getUniqueId()).set(new Location(world, 5, 64, 0));
        manager.enforce(target.getUniqueId());
        assertEquals(new Vector(0, 0.42, 0.3), target.getVelocity());
    }

    @Test
    void unbindingStopsResidualLeashMotion() throws Exception {
        manager.enforce(target.getUniqueId());
        assertTrue(target.getVelocity().getX() < 0);
        manager.unbind(target.getUniqueId());
        assertEquals(new Vector(), target.getVelocity());
        assertNull(manager.get(target.getUniqueId()));
    }

    @Test
    void longerLeashReleasesPreviousPullImmediately() throws Exception {
        manager.enforce(target.getUniqueId());
        manager.setDistance(target.getUniqueId(), 40);
        assertEquals(new Vector(), target.getVelocity());
        assertEquals(10, target.getLocation().getX());
    }

    @Test
    void shutdownReleasesMotionWithoutRemovingBinding() {
        manager.enforce(target.getUniqueId());
        manager.releaseAllPulls();
        assertEquals(new Vector(), target.getVelocity());
        assertNotNull(manager.get(target.getUniqueId()));
    }

    @Test
    void changingRopeDirectionDoesNotLeaveOldSidewaysImpulse() {
        manager.enforce(target.getUniqueId());
        assertTrue(target.getVelocity().getX() < 0);
        positions.get(holder.getUniqueId()).set(new Location(world, 10, 64, 16));
        manager.enforce(target.getUniqueId());
        assertEquals(0, target.getVelocity().getX(), 0.0000001);
        assertTrue(target.getVelocity().getZ() > 0);
    }

    @Test
    void turningHeadCannotChangeDirectionOfPull() {
        manager.enforce(target.getUniqueId());
        Vector facingForward = target.getVelocity();
        manager.resetMotion(target.getUniqueId());
        velocities.get(target.getUniqueId()).set(new Vector());
        positions.get(target.getUniqueId()).set(new Location(world, 10, 64, 0, 90, 0));
        manager.enforce(target.getUniqueId());
        assertEquals(facingForward, target.getVelocity());
        assertEquals(0, target.getVelocity().getZ());
    }

    @Test
    void repeatedTensionAgainstWallKeepsPlayerVelocityBounded() {
        positions.get(target.getUniqueId()).set(new Location(world, 30, 64, 0));
        manager.resetMotion(target.getUniqueId());
        for (int tick = 0; tick < 200; tick++) {
            manager.tick();
            assertTrue(target.getVelocity().length() <= 0.45 + 0.0000001);
            assertTrue(target.getVelocity().getX() <= 0);
        }
    }

    @Test
    void resistingPlayerBuildsBoundedPullInsteadOfDefeatingTension() {
        for (int tick = 0; tick < 20; tick++) {
            positions.get(target.getUniqueId()).set(new Location(world, 10 + tick * 0.15, 64, 0));
            manager.tick();
            assertTrue(target.getVelocity().getX() >= -0.45 - 0.0000001);
            assertTrue(target.getVelocity().getX() <= 0);
        }
        assertTrue(target.getVelocity().getX() <= -0.4);
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void staleServerVelocityDoesNotStopSendingPullToClient() {
        AtomicReference<Vector> packet = new AtomicReference<>();
        doAnswer(call -> {
            Vector value = ((Vector) call.getArgument(0)).clone();
            velocities.get(target.getUniqueId()).set(value);
            packet.set(value);
            return null;
        }).when(target).setVelocity(any(Vector.class));
        for (int tick = 0; tick < 20; tick++) {
            manager.enforce(target.getUniqueId());
        }
        packet.set(null);
        manager.enforce(target.getUniqueId());
        assertNotNull(packet.get());
        assertTrue(packet.get().getX() < 0);
        assertTrue(packet.get().length() <= 0.45);
    }

    @Test
    void failedUnbindDoesNotReleaseOrForgetActiveTension() throws Exception {
        manager.enforce(target.getUniqueId());
        Vector before = target.getVelocity();
        Path file = directory.resolve("leashes.yml");
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> manager.unbind(target.getUniqueId()));
        assertNotNull(manager.get(target.getUniqueId()));
        assertEquals(before, target.getVelocity());
        manager.enforce(target.getUniqueId());
        assertTrue(target.getVelocity().getX() < 0);
    }

    @Test
    void holderPortalTransfersTargetOnceAndKeepsLeash() {
        World nether = otherWorld();
        positions.get(holder.getUniqueId()).set(new Location(nether, 80, 70, -20));
        manager.synchronizeWorld(holder);
        assertEquals(nether, target.getWorld());
        assertTrue(manager.canMove(target.getUniqueId(), target.getLocation()));
        assertNotNull(manager.get(target.getUniqueId()));
        assertFalse(manager.isInternalTransfer(target.getUniqueId()));
        manager.synchronizeWorld(holder);
        manager.tick();
        verify(target).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(holder, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(target).setPortalCooldown(200);
    }

    @Test
    void targetMayEnterPortalFirstAndHolderFollows() {
        World nether = otherWorld();
        Location exit = new Location(nether, 10, 70, -5);
        PlayerTeleportEvent event = new PlayerTeleportEvent(target, target.getLocation(), exit,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        new LeashListener(null, manager).onTeleport(event);
        assertFalse(event.isCancelled());
        positions.get(target.getUniqueId()).set(exit);
        manager.synchronizeWorld(target);
        assertEquals(nether, holder.getWorld());
        assertNotNull(manager.get(target.getUniqueId()));
        verify(holder).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void portalTransfersEntireChainButLeavesUnrelatedPlayers() throws Exception {
        Player child = player("Child", 11);
        Player unrelated = player("Unrelated", 5);
        manager.bind(child, target, 15);
        World nether = otherWorld();
        positions.get(target.getUniqueId()).set(new Location(nether, 10, 70, -5));
        manager.synchronizeWorld(target);
        assertEquals(nether, holder.getWorld());
        assertEquals(nether, child.getWorld());
        assertEquals(world, unrelated.getWorld());
        assertNotNull(manager.get(target.getUniqueId()));
        assertNotNull(manager.get(child.getUniqueId()));
    }

    private World otherWorld() {
        World other = mock(World.class);
        UUID id = UUID.randomUUID();
        when(other.getUID()).thenReturn(id);
        when(server.getWorld(id)).thenReturn(other);
        return other;
    }

    @Test
    void offlineTargetCatchesUpToHolderPortalWhenJoining() {
        online.remove(target.getUniqueId());
        World nether = otherWorld();
        positions.get(holder.getUniqueId()).set(new Location(nether, 10, 70, -5));
        manager.synchronizeWorld(holder);
        assertEquals(world, target.getWorld());
        online.put(target.getUniqueId(), target);
        manager.resume(target.getUniqueId());
        assertEquals(nether, target.getWorld());
        assertTrue(manager.canMove(target.getUniqueId(), target.getLocation()));
        assertNotNull(manager.get(target.getUniqueId()));
    }

    @Test
    void rejoiningInSameWorldUsesTensionWithoutTeleporting() {
        positions.get(target.getUniqueId()).set(new Location(world, 50, 64, 0));
        manager.resume(target.getUniqueId());
        assertEquals(50, target.getLocation().getX());
        assertTrue(target.getVelocity().getX() < 0);
        verify(target, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void offlineHolderStillKeepsLastWorldAfterPortal() {
        World nether = otherWorld();
        positions.get(holder.getUniqueId()).set(new Location(nether, 10, 70, -5));
        manager.checkpoint(holder);
        online.remove(holder.getUniqueId());
        manager.resume(target.getUniqueId());
        assertEquals(nether, target.getWorld());
        assertNotNull(manager.get(target.getUniqueId()));
        assertFalse(manager.isInternalTransfer(target.getUniqueId()));
    }

    @Test
    void persistenceFailureRollsBackBind() throws Exception {
        Path unwritable = directory.resolve("directory-instead-of-file.yml");
        Files.createDirectory(unwritable);
        LeashManager broken = new LeashManager(server, Logger.getLogger("HumanLeashTest"), new LeashStore(unwritable));
        assertThrows(IOException.class, () -> broken.bind(target, holder, 15));
        assertNull(broken.get(target.getUniqueId()));
    }

    @Test
    void cannotReplaceOrAttachOutsideRadius() {
        assertThrows(IllegalArgumentException.class, () -> manager.bind(target, holder, 15));
        Player distant = player("Distant", 100);
        assertThrows(IllegalArgumentException.class, () -> manager.bind(distant, holder, 15));
        assertNull(manager.get(distant.getUniqueId()));
    }
}
