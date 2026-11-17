package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-556: repository/display-name normalization must never touch the
 * shipped runtime mod ID. These two facts are asserted independently so a
 * future edit to either file trips a test instead of silently drifting.
 */
class MinecraftFiveFiveSixNamingTest {
    @Test
    void shippedModIdIsFrozen() {
        assertEquals("dynamicvehicles", DynamicVehiclesMod.MODID);
    }

    @Test
    void gradlePropertiesDisplayNameIsTheNormalizedName() throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            props.load(in);
        }
        assertEquals("dynamicvehicles", props.getProperty("mod_id"));
        assertEquals("Legacy Dynamic Vehicles", props.getProperty("mod_name"));
    }

    @Test
    void settingsGradleRootProjectNameIsTheNormalizedName() throws IOException {
        String settings = Files.readString(Path.of("settings.gradle"));
        assertTrue(settings.contains("rootProject.name = \"legacy-dynamic-vehicles\""),
                "settings.gradle should set rootProject.name to legacy-dynamic-vehicles");
    }
}
