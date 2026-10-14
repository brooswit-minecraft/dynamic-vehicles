package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial;

class AtmosphereCompatTest {
    @Test
    void creeperBurstUsesTheExistingExhaustMaterialSingleton() {
        assertSame(AtmosphereMaterial.EXHAUST, AtmosphereCompat.exhaustMaterial());
    }
}
