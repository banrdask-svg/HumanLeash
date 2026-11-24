package com.olis.humanleash;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.persistence.PersistentDataType;

public final class RopeRenderer implements Listener {
    private final HumanLeashPlugin plugin;
    private final LeashManager manager;
    private final NamespacedKey marker;
    private final Map<UUID, Bat> proxies = new HashMap<>();

    public RopeRenderer(HumanLeashPlugin plugin, LeashManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        marker = new NamespacedKey(plugin, "rope-proxy");
    }

    public void tick(boolean enabled) {
        if (!enabled) {
            clear();
            return;
        }
        Set<UUID> visible = new HashSet<>();
        for (LeashLink link : manager.links()) {
            Player target = plugin.getServer().getPlayer(link.targetId());
            Player holder = plugin.getServer().getPlayer(link.holderId());
            if (target == null || holder == null || target.isDead() || holder.isDead()
                    || !target.getWorld().equals(holder.getWorld())) {
                continue;
            }
            visible.add(link.targetId());
            Location location = target.getLocation().add(0, target.getEyeHeight(), 0);
            Bat proxy = proxies.get(link.targetId());
            if (proxy == null || !proxy.isValid() || !proxy.getWorld().equals(target.getWorld())) {
                remove(link.targetId());
                // Bat не толкает сущности и на клиенте; setCollidable(false) сам по себе защищает лишь сервер.
                proxy = target.getWorld().spawn(location, Bat.class, bat -> {
                    bat.setAwake(true);
                    bat.setInvisible(true);
                    bat.setAI(false);
                    bat.setGravity(false);
                    bat.setInvulnerable(true);
                    bat.setSilent(true);
                    bat.setCollidable(false);
                    bat.setCanPickupItems(false);
                    bat.setPersistent(false);
                    bat.setRemoveWhenFarAway(false);
                    bat.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
                });
                proxies.put(link.targetId(), proxy);
            }
            double yaw = Math.toRadians(location.getYaw());
            double forwardOffset = (target.getWidth() - proxy.getWidth()) * 0.4;
            location.add(-Math.sin(yaw) * forwardOffset, -proxy.getEyeHeight(), Math.cos(yaw) * forwardOffset);
            if (proxy.getLocation().distanceSquared(location) > 0.0001) {
                proxy.teleport(location);
            }
            proxy.setPortalCooldown(200);
            if (!proxy.isLeashed() || !proxy.getLeashHolder().getUniqueId().equals(holder.getUniqueId())) {
                if (!proxy.setLeashHolder(holder)) {
                    plugin.getLogger().warning("Не удалось показать поводок для " + target.getName());
                    remove(link.targetId());
                }
            }
        }
        for (UUID targetId : new HashSet<>(proxies.keySet())) {
            if (!visible.contains(targetId)) {
                remove(targetId);
            }
        }
    }

    public void remove(UUID targetId) {
        Bat proxy = proxies.remove(targetId);
        if (proxy != null) {
            proxy.setLeashHolder(null);
            proxy.remove();
        }
    }

    public void clear() {
        for (UUID targetId : new HashSet<>(proxies.keySet())) {
            remove(targetId);
        }
    }

    private boolean isProxy(Entity entity) {
        return entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDropItem(EntityDropItemEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        if (isProxy(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(EntityPortalEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        for (UUID playerId : manager.connectedPlayers(event.getPlayer().getUniqueId())) {
            remove(playerId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUnleash(EntityUnleashEvent event) {
        if (isProxy(event.getEntity())) {
            event.setDropLeash(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerUnleash(PlayerUnleashEntityEvent event) {
        onUnleash(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isProxy(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
