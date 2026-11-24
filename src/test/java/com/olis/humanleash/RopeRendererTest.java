package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RopeRendererTest {
    private RopeRenderer renderer;
    private LeashManager manager;
    private World world;
    private Player target;
    private Player holder;
    private Bat proxy;
    private AtomicReference<Location> proxyPosition;

    @BeforeEach
    void setUp() {
        HumanLeashPlugin plugin = mock(HumanLeashPlugin.class);
        Server server = mock(Server.class);
        world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(plugin.namespace()).thenReturn("humanleash");
        when(plugin.getServer()).thenReturn(server);
        target = player("Target", world);
        holder = player("Holder", world);
        when(server.getPlayer(target.getUniqueId())).thenReturn(target);
        when(server.getPlayer(holder.getUniqueId())).thenReturn(holder);
        manager = mock(LeashManager.class);
        LeashLink link = new LeashLink(target.getUniqueId(), "Target", holder.getUniqueId(), "Holder", 15,
                new LeashPoint(worldId, 0, 64, 0, 0, 0));
        when(manager.links()).thenReturn(List.of(link));
        Set<UUID> connected = Set.of(target.getUniqueId(), holder.getUniqueId());
        when(manager.connectedPlayers(holder.getUniqueId())).thenReturn(connected);
        proxy = mock(Bat.class);
        proxyPosition = new AtomicReference<>(new Location(world, 0, 64, 0));
        when(proxy.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(proxy.getWorld()).thenReturn(world);
        when(proxy.isValid()).thenReturn(true);
        when(proxy.getEyeHeight()).thenReturn(0.45);
        when(proxy.getWidth()).thenReturn(0.5);
        when(proxy.getLocation()).thenAnswer(call -> proxyPosition.get().clone());
        when(proxy.setLeashHolder(holder)).thenReturn(true);
        when(proxy.isLeashed()).thenReturn(false, true);
        when(proxy.getLeashHolder()).thenReturn(holder);
        doAnswer(call -> {
            proxyPosition.set(((Location) call.getArgument(0)).clone());
            return true;
        }).when(proxy).teleport(any(Location.class));
        when(world.spawn(any(Location.class), eq(Bat.class), org.mockito.ArgumentMatchers.<Consumer<? super Bat>>any()))
                .thenAnswer(call -> {
                    proxyPosition.set(((Location) call.getArgument(0)).clone());
                    Consumer<? super Bat> initializer = call.getArgument(2);
                    initializer.accept(proxy);
                    return proxy;
                });
        renderer = new RopeRenderer(plugin, manager);
    }

    private Player player(String name, World playerWorld) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.getWorld()).thenReturn(playerWorld);
        when(player.getLocation()).thenAnswer(call -> new Location(playerWorld, 0, 64, 0));
        when(player.getEyeHeight()).thenReturn(1.62);
        when(player.getWidth()).thenReturn(0.6);
        return player;
    }

    @Test
    void reusesSingleNonPushingProxyInsteadOfRespawningEachTick() {
        renderer.tick(true);
        renderer.tick(true);
        verify(world).spawn(any(Location.class), eq(Bat.class), org.mockito.ArgumentMatchers.<Consumer<? super Bat>>any());
        verify(proxy).setLeashHolder(holder);
        verify(proxy, never()).remove();
    }

    @Test
    void crouchingKeepsRopeAtPlayerEyeHeight() {
        renderer.tick(true);
        when(target.getEyeHeight()).thenReturn(1.27);
        renderer.tick(true);
        assertEquals(65.27, proxyPosition.get().getY() + proxy.getEyeHeight(), 0.0000001);
        verify(world).spawn(any(Location.class), eq(Bat.class), org.mockito.ArgumentMatchers.<Consumer<? super Bat>>any());
    }

    @Test
    void worldChangeRemovesOldVisualWithoutRemovingBinding() throws Exception {
        renderer.tick(true);
        renderer.onWorldChange(new PlayerChangedWorldEvent(holder, world));
        verify(proxy).setLeashHolder(null);
        verify(proxy).remove();
        verify(manager, never()).unbind(any(UUID.class));
    }
}
