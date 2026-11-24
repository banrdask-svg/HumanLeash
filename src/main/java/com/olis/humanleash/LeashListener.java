package com.olis.humanleash;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class LeashListener implements Listener {
    private final HumanLeashPlugin plugin;
    private final LeashManager manager;
    private final Map<UUID, Long> notices = new HashMap<>();

    public LeashListener(HumanLeashPlugin plugin, LeashManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent || isDimensionPortal(event)) {
            return;
        }
        checkTeleport(event);
    }

    private boolean isDimensionPortal(PlayerTeleportEvent event) {
        return (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.END_GATEWAY)
                && event.getTo() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleported(PlayerTeleportEvent event) {
        manager.resetMotion(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.resetMotion(event.getPlayer().getUniqueId());
        if (!manager.isInternalTransfer(event.getPlayer().getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> manager.synchronizeWorld(event.getPlayer()));
        }
    }

    private void checkTeleport(PlayerTeleportEvent event) {
        if (!manager.canMove(event.getPlayer().getUniqueId(), event.getTo())) {
            event.setCancelled(true);
            notifyLimit(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!manager.canMove(playerId, event.getRespawnLocation())) {
            LeashPoint point = manager.anchor(playerId);
            Location location = point == null ? null : point.toLocation(plugin.getServer());
            if (location != null) {
                event.setRespawnLocation(location);
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> manager.enforce(playerId));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> manager.resume(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.checkpoint(event.getPlayer());
        notices.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        manager.checkpoint(event.getEntity());
    }

    private void notifyLimit(Player player) {
        long now = System.currentTimeMillis();
        if (now - notices.getOrDefault(player.getUniqueId(), 0L) >= 1_000) {
            player.sendActionBar(Component.text("Поводок не позволяет отойти дальше. Снять его можно только командой.",
                    NamedTextColor.RED));
            notices.put(player.getUniqueId(), now);
        }
    }
}
