package traben.flowing_fluids;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import traben.flowing_fluids.util.DimensionKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class DimensionKeyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void resourceDimensionsAreInterned() {
        ResourceKey<Level> dimension = dimension("test:one");
        DimensionKey first = DimensionKey.of(dimension);
        DimensionKey second = DimensionKey.of(dimension);

        assertSame(first, second);
        assertEquals(first, second);
    }

    @Test
    void differentResourceDimensionsStayDistinct() {
        DimensionKey overworld = DimensionKey.of(dimension("test:one"));
        DimensionKey nether = DimensionKey.of(dimension("test:two"));

        assertNotSame(overworld, nether);
        assertNotEquals(overworld, nether);
    }

    @Test
    void identityKeysRemainIsolated() {
        Object token = new Object();
        DimensionKey first = DimensionKey.ofIdentity(token);
        DimensionKey second = DimensionKey.ofIdentity(token);

        assertNotSame(first, second);
        assertEquals(first, second);
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse(id));
    }
}
