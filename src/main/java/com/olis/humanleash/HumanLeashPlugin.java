package com.olis.humanleash;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class HumanLeashPlugin extends JavaPlugin {
    private LeashManager manager;
    private RopeRenderer ropes;
    private double defaultDistance;
    private double maxDistance;
    private boolean showRope;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            readSettings();
            manager = new LeashManager(getServer(), getLogger(),
                    new LeashStore(getDataFolder().toPath().resolve("leashes.yml")));
            manager.load();
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Не удалось загрузить HumanLeash. Проверьте config.yml и leashes.yml.", exception);
            manager = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ropes = new RopeRenderer(this, manager);
        LeashCommand executor = new LeashCommand(this, manager, ropes);
        PluginCommand command = Objects.requireNonNull(getCommand("leash"));
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new LeashListener(this, manager), this);
        getServer().getPluginManager().registerEvents(ropes, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            manager.tick();
            ropes.tick(showRope);
        }, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, manager::saveLogged, 600L, 600L);
        getLogger().info("HumanLeash включён. Дистанция по умолчанию: " + defaultDistance + " блоков.");
    }

    @Override
    public void onDisable() {
        if (ropes != null) {
            ropes.clear();
        }
        if (manager != null) {
            manager.releaseAllPulls();
            manager.saveLogged();
        }
    }

    public void reloadSettings() throws IOException, InvalidConfigurationException {
        readSettings();
    }

    private void readSettings() throws IOException, InvalidConfigurationException {
        YamlConfiguration settings = new YamlConfiguration();
        settings.load(getDataFolder().toPath().resolve("config.yml").toFile());
        double maximum = number(settings, "max-distance", LeashLink.MAX_DISTANCE);
        LeashLink.validateDistance(maximum, LeashLink.MAX_DISTANCE);
        double distance = number(settings, "default-distance", 15.0);
        LeashLink.validateDistance(distance, maximum);
        Object rope = settings.get("show-rope", true);
        if (!(rope instanceof Boolean enabled)) {
            throw new IllegalArgumentException("show-rope должен иметь значение true или false.");
        }
        maxDistance = maximum;
        defaultDistance = distance;
        showRope = enabled;
    }

    private static double number(YamlConfiguration settings, String key, double fallback) {
        Object value = settings.get(key, fallback);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " должен быть числом.");
        }
        return number.doubleValue();
    }

    public double defaultDistance() {
        return defaultDistance;
    }

    public double maxDistance() {
        return maxDistance;
    }
}
