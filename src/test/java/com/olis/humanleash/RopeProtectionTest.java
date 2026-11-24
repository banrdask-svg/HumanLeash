package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RopeProtectionTest {
    private RopeRenderer ropes;
    private Bat proxy;
    private Player player;

    @BeforeEach
    void setUp() {
        HumanLeashPlugin plugin = mock(HumanLeashPlugin.class);
        when(plugin.namespace()).thenReturn("humanleash");
        ropes = new RopeRenderer(plugin, mock(LeashManager.class));
        proxy = mock(Bat.class);
        player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(proxy.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
    }

    @Test
    void distanceCannotBreakRopeOrDropLead() {
        EntityUnleashEvent event = new EntityUnleashEvent(proxy, EntityUnleashEvent.UnleashReason.DISTANCE, true);
        ropes.onUnleash(event);
        assertTrue(event.isCancelled());
        assertFalse(event.isDropLeash());
    }

    @Test
    void playerCannotUnleashProxy() {
        PlayerUnleashEntityEvent event = new PlayerUnleashEntityEvent(proxy, player, EquipmentSlot.HAND, true);
        ropes.onPlayerUnleash(event);
        assertTrue(event.isCancelled());
        assertFalse(event.isDropLeash());
    }

    @Test
    void playerCannotRetieOrInteractWithProxy() {
        PlayerLeashEntityEvent leash = new PlayerLeashEntityEvent(proxy, player, player, EquipmentSlot.HAND);
        ropes.onLeash(leash);
        assertTrue(leash.isCancelled());
        PlayerInteractEntityEvent click = new PlayerInteractEntityEvent(player, proxy, EquipmentSlot.HAND);
        ropes.onInteract(click);
        assertTrue(click.isCancelled());
    }

    @Test
    void ordinaryAnimalsKeepVanillaUnleashing() {
        Chicken ordinary = mock(Chicken.class);
        when(ordinary.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        EntityUnleashEvent event = new EntityUnleashEvent(ordinary, EntityUnleashEvent.UnleashReason.DISTANCE, true);
        ropes.onUnleash(event);
        assertFalse(event.isCancelled());
        assertTrue(event.isDropLeash());
    }

    @Test
    void standingInPortalNeverLetsProxyEnterOrCreateLeadItems() {
        Location location = new Location(mock(World.class), 0, 64, 0);
        for (int tick = 0; tick < 100; tick++) {
            EntityPortalEnterEvent enter = new EntityPortalEnterEvent(proxy, location, PortalType.NETHER);
            ropes.onPortalEnter(enter);
            assertTrue(enter.isCancelled());
            EntityDropItemEvent drop = new EntityDropItemEvent(proxy, mock(Item.class));
            ropes.onDropItem(drop);
            assertTrue(drop.isCancelled());
        }
    }

    @Test
    void proxyPortalTransferIsCancelledAndCannotDropItems() {
        Location from = new Location(mock(World.class), 0, 64, 0);
        Location to = new Location(mock(World.class), 10, 70, 10);
        EntityPortalEvent portal = new EntityPortalEvent(proxy, from, to);
        ropes.onPortal(portal);
        assertTrue(portal.isCancelled());
        EntityDropItemEvent drop = new EntityDropItemEvent(proxy, mock(Item.class));
        ropes.onDropItem(drop);
        assertTrue(drop.isCancelled());
    }

    @Test
    void ordinaryAnimalsMayUsePortalsAndDropItems() {
        Chicken ordinary = mock(Chicken.class);
        when(ordinary.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        Location location = new Location(mock(World.class), 0, 64, 0);
        EntityPortalEnterEvent enter = new EntityPortalEnterEvent(ordinary, location, PortalType.NETHER);
        ropes.onPortalEnter(enter);
        assertFalse(enter.isCancelled());
        EntityDropItemEvent drop = new EntityDropItemEvent(ordinary, mock(Item.class));
        ropes.onDropItem(drop);
        assertFalse(drop.isCancelled());
    }

    @Test
    void killedProxyDoesNotDropLootOrExperience() {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(mock(ItemStack.class));
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(proxy);
        when(death.getDrops()).thenReturn(drops);
        ropes.onDeath(death);
        assertTrue(drops.isEmpty());
        verify(death).setDroppedExp(0);
    }

    @Test
    void ordinaryAnimalsKeepDeathLootAndExperience() {
        Chicken ordinary = mock(Chicken.class);
        when(ordinary.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(ordinary);
        ropes.onDeath(death);
        verify(death, never()).getDrops();
        verify(death, never()).setDroppedExp(0);
    }
}
