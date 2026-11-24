package com.olis.humanleash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LeashRulesTest {
    @TempDir
    Path directory;

    private final UUID world = UUID.randomUUID();
    private final LeashPoint anchor = new LeashPoint(world, 0, 64, 0, 0, 0);

    @Test
    void radiusIncludesBoundaryAndCountsHeight() {
        assertTrue(anchor.contains(new LeashPoint(world, 9, 76, 0, 0, 0), 15));
        assertFalse(anchor.contains(new LeashPoint(world, 9, 76.01, 0, 0, 0), 15));
        assertFalse(anchor.contains(new LeashPoint(world, 0, 79.01, 0, 0, 0), 15));
        assertTrue(anchor.contains(new LeashPoint(world, 0, 64, 15, 0, 0), 15));
    }

    @Test
    void differentWorldIsOutsideLeash() {
        assertFalse(anchor.contains(new LeashPoint(UUID.randomUUID(), 0, 64, 0, 0, 0), 15));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0, 0.99, 128.01})
    void rejectsInvalidDistance(double distance) {
        assertThrows(IllegalArgumentException.class, () -> LeashLink.validateDistance(distance, 128));
    }

    @Test
    void supportsFractionalDistanceAndConfiguredMaximum() {
        LeashLink.validateDistance(7.5, 10);
        assertThrows(IllegalArgumentException.class, () -> LeashLink.validateDistance(10.1, 10));
    }

    @Test
    void rejectsSelfLeashAndCyclicChains() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new LeashLink(a, "A", a, "A", 15, anchor));
        Map<UUID, LeashLink> links = Map.of(a, new LeashLink(a, "A", b, "B", 15, anchor),
                b, new LeashLink(b, "B", c, "C", 15, anchor));
        assertTrue(LeashManager.wouldCreateCycle(links, c, a));
        assertTrue(LeashManager.wouldCreateCycle(links, b, a));
        assertFalse(LeashManager.wouldCreateCycle(links, UUID.randomUUID(), a));
    }

    @Test
    void savesAndRestoresHolderLengthAndAnchor() throws Exception {
        LeashStore store = new LeashStore(directory.resolve("leashes.yml"));
        LeashLink link = new LeashLink(UUID.randomUUID(), "Target", UUID.randomUUID(), "Holder", 7.5,
                new LeashPoint(world, -100.5, 64.25, 5.75, 90, -30));
        store.save(List.of(link));
        assertEquals(Map.of(link.targetId(), link), store.load());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void removedLeashDoesNotReturnAfterRestart() throws Exception {
        LeashStore store = new LeashStore(directory.resolve("leashes.yml"));
        store.save(List.of(new LeashLink(UUID.randomUUID(), "Target", UUID.randomUUID(), "Holder", 15, anchor)));
        store.save(List.of());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void malformedSaveFailsWithoutOverwritingFile() throws Exception {
        Path file = directory.resolve("leashes.yml");
        String damaged = "leashes:\n  " + UUID.randomUUID() + ":\n    distance: 15\n";
        Files.writeString(file, damaged);
        assertThrows(InvalidConfigurationException.class, () -> new LeashStore(file).load());
        assertEquals(damaged, Files.readString(file));
    }
}
