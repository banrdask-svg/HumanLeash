package com.olis.humanleash;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class LeashStore {
    private final Path file;

    public LeashStore(Path file) {
        this.file = file;
    }

    public Map<UUID, LeashLink> load() throws IOException, InvalidConfigurationException {
        Map<UUID, LeashLink> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("leashes");
        if (root == null) {
            if (yaml.contains("leashes")) {
                throw new InvalidConfigurationException("Поле leashes должно быть секцией YAML.");
            }
            return result;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(key);
            try {
                if (entry == null) {
                    throw new IllegalArgumentException("Отсутствует секция привязки.");
                }
                UUID target = UUID.fromString(key);
                LeashPoint anchor = new LeashPoint(UUID.fromString(requiredString(entry, "anchor.world")),
                        requiredNumber(entry, "anchor.x"), requiredNumber(entry, "anchor.y"),
                        requiredNumber(entry, "anchor.z"), (float) requiredNumber(entry, "anchor.yaw"),
                        (float) requiredNumber(entry, "anchor.pitch"));
                result.put(target, new LeashLink(target, requiredString(entry, "target-name"),
                        UUID.fromString(requiredString(entry, "holder")), requiredString(entry, "holder-name"),
                        requiredNumber(entry, "distance"), anchor));
            } catch (IllegalArgumentException exception) {
                throw new InvalidConfigurationException("Некорректная привязка " + key + ": "
                        + exception.getMessage(), exception);
            }
        }
        return result;
    }

    public void save(Collection<LeashLink> links) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("leashes");
        for (LeashLink link : links) {
            String key = "leashes." + link.targetId() + ".";
            yaml.set(key + "target-name", link.targetName());
            yaml.set(key + "holder", link.holderId().toString());
            yaml.set(key + "holder-name", link.holderName());
            yaml.set(key + "distance", link.distance());
            yaml.set(key + "anchor.world", link.anchor().worldId().toString());
            yaml.set(key + "anchor.x", link.anchor().x());
            yaml.set(key + "anchor.y", link.anchor().y());
            yaml.set(key + "anchor.z", link.anchor().z());
            yaml.set(key + "anchor.yaw", link.anchor().yaw());
            yaml.set(key + "anchor.pitch", link.anchor().pitch());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "leashes-", ".tmp");
        try {
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String requiredString(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Отсутствует поле " + key);
        }
        return value;
    }

    private static double requiredNumber(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Отсутствует числовое поле " + key);
        }
        return number.doubleValue();
    }
}
