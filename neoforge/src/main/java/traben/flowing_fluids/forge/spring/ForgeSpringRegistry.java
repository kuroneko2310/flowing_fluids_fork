package traben.flowing_fluids.forge.spring;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import traben.flowing_fluids.FlowingFluids;

public final class ForgeSpringRegistry {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, FlowingFluids.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, FlowingFluids.MOD_ID);
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, FlowingFluids.MOD_ID);

    public static final DeferredHolder<Block, WallSpringBlock> WALL_SPRING_SMALL = registerSpring(SpringStrength.SLIGHT);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_SPRING_NORMAL = registerSpring(SpringStrength.NORMAL);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_SPRING_LARGE = registerSpring(SpringStrength.LARGE);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_SPRING_HEAVY = registerSpring(SpringStrength.HEAVY);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_LAVA_SPRING_SMALL = registerLavaSpring(SpringStrength.SLIGHT);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_LAVA_SPRING_NORMAL = registerLavaSpring(SpringStrength.NORMAL);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_LAVA_SPRING_LARGE = registerLavaSpring(SpringStrength.LARGE);
    public static final DeferredHolder<Block, WallSpringBlock> WALL_LAVA_SPRING_HEAVY = registerLavaSpring(SpringStrength.HEAVY);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_SPRING_SMALL = registerFloorSpring(SpringStrength.SLIGHT);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_SPRING_NORMAL = registerFloorSpring(SpringStrength.NORMAL);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_SPRING_LARGE = registerFloorSpring(SpringStrength.LARGE);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_SPRING_HEAVY = registerFloorSpring(SpringStrength.HEAVY);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_SPRING_SMALL = registerCeilingSpring(SpringStrength.SLIGHT);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_SPRING_NORMAL = registerCeilingSpring(SpringStrength.NORMAL);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_SPRING_LARGE = registerCeilingSpring(SpringStrength.LARGE);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_SPRING_HEAVY = registerCeilingSpring(SpringStrength.HEAVY);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_LAVA_SPRING_SMALL = registerFloorLavaSpring(SpringStrength.SLIGHT);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_LAVA_SPRING_NORMAL = registerFloorLavaSpring(SpringStrength.NORMAL);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_LAVA_SPRING_LARGE = registerFloorLavaSpring(SpringStrength.LARGE);
    public static final DeferredHolder<Block, FloorSpringBlock> FLOOR_LAVA_SPRING_HEAVY = registerFloorLavaSpring(SpringStrength.HEAVY);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_LAVA_SPRING_SMALL = registerCeilingLavaSpring(SpringStrength.SLIGHT);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_LAVA_SPRING_NORMAL = registerCeilingLavaSpring(SpringStrength.NORMAL);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_LAVA_SPRING_LARGE = registerCeilingLavaSpring(SpringStrength.LARGE);
    public static final DeferredHolder<Block, CeilingSpringBlock> CEILING_LAVA_SPRING_HEAVY = registerCeilingLavaSpring(SpringStrength.HEAVY);

    public static final DeferredHolder<Feature<?>, Feature<?>> CAVE_WALL_SPRING =
            FEATURES.register("cave_wall_spring", () -> new CaveWallSpringFeature(NoneFeatureConfiguration.CODEC));
    public static final DeferredHolder<Feature<?>, Feature<?>> CAVE_FLOOR_SPRING =
            FEATURES.register("cave_floor_spring", () -> new CaveFloorSpringFeature(NoneFeatureConfiguration.CODEC));
    public static final DeferredHolder<Feature<?>, Feature<?>> CAVE_CEILING_SPRING =
            FEATURES.register("cave_ceiling_spring", () -> new CaveCeilingSpringFeature(NoneFeatureConfiguration.CODEC));
    public static final DeferredHolder<Feature<?>, Feature<?>> POND_FLOOR_SPRING =
            FEATURES.register("pond_floor_spring", () -> new PondFloorSpringFeature(NoneFeatureConfiguration.CODEC));
    public static final DeferredHolder<Feature<?>, Feature<?>> DEEP_UNDERGROUND_LAVA_SPRING =
            FEATURES.register("deep_underground_lava_spring", () -> new DeepUndergroundLavaSpringFeature(NoneFeatureConfiguration.CODEC));
    public static final DeferredHolder<Feature<?>, Feature<?>> DEEP_UNDERGROUND_CEILING_LAVA_SPRING =
            FEATURES.register("deep_underground_ceiling_lava_spring", () -> new DeepUndergroundCeilingLavaSpringFeature(NoneFeatureConfiguration.CODEC));
    public static final DeferredHolder<Feature<?>, Feature<?>> SURFACE_WATER_VENT_SPRING =
            FEATURES.register("surface_water_vent_spring", () -> new SurfaceVentSpringFeature(NoneFeatureConfiguration.CODEC, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER));
    public static final DeferredHolder<Feature<?>, Feature<?>> SURFACE_LAVA_VENT_SPRING =
            FEATURES.register("surface_lava_vent_spring", () -> new SurfaceVentSpringFeature(NoneFeatureConfiguration.CODEC, (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA));

    private ForgeSpringRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener(ForgeSpringRegistry::addToCreativeTabs);
    }

    public static WallSpringBlock pickGeneratedBlock(net.minecraft.util.RandomSource random, int y, int seaLevel, boolean damp) {
        SpringStrength strength = SpringStrength.pickGeneratedVariant(random, y, seaLevel, damp);
        return switch (strength) {
            case SLIGHT -> WALL_SPRING_SMALL.get();
            case NORMAL -> WALL_SPRING_NORMAL.get();
            case LARGE -> WALL_SPRING_LARGE.get();
            case HEAVY -> WALL_SPRING_HEAVY.get();
        };
    }

    public static WallSpringBlock pickGeneratedLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava, int lavaRichness) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, nearLava, lavaRichness, 0);
        return switch (strength) {
            case SLIGHT -> WALL_LAVA_SPRING_SMALL.get();
            case NORMAL -> WALL_LAVA_SPRING_NORMAL.get();
            case LARGE -> WALL_LAVA_SPRING_LARGE.get();
            case HEAVY -> WALL_LAVA_SPRING_HEAVY.get();
        };
    }

    public static WallSpringBlock pickGeneratedLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava, int lavaRichness, int depthBonus) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, nearLava, lavaRichness, depthBonus);
        return switch (strength) {
            case SLIGHT -> WALL_LAVA_SPRING_SMALL.get();
            case NORMAL -> WALL_LAVA_SPRING_NORMAL.get();
            case LARGE -> WALL_LAVA_SPRING_LARGE.get();
            case HEAVY -> WALL_LAVA_SPRING_HEAVY.get();
        };
    }

    public static WallSpringBlock pickGeneratedLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava) {
        return pickGeneratedLavaBlock(random, y, nearLava, nearLava ? 1 : 0);
    }

    public static FloorSpringBlock pickGeneratedFloorLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava, int lavaRichness) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, nearLava, lavaRichness, 0);
        return switch (strength) {
            case SLIGHT -> FLOOR_LAVA_SPRING_SMALL.get();
            case NORMAL -> FLOOR_LAVA_SPRING_NORMAL.get();
            case LARGE -> FLOOR_LAVA_SPRING_LARGE.get();
            case HEAVY -> FLOOR_LAVA_SPRING_HEAVY.get();
        };
    }

    public static FloorSpringBlock pickGeneratedFloorLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava, int lavaRichness, int depthBonus) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, nearLava, lavaRichness, depthBonus);
        return switch (strength) {
            case SLIGHT -> FLOOR_LAVA_SPRING_SMALL.get();
            case NORMAL -> FLOOR_LAVA_SPRING_NORMAL.get();
            case LARGE -> FLOOR_LAVA_SPRING_LARGE.get();
            case HEAVY -> FLOOR_LAVA_SPRING_HEAVY.get();
        };
    }

    public static FloorSpringBlock pickGeneratedFloorLavaBlock(net.minecraft.util.RandomSource random, int y, int minBuildHeight, boolean netherLike, boolean nearLava, int lavaRichness, int depthBonus) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, minBuildHeight, netherLike, nearLava, lavaRichness, depthBonus);
        return switch (strength) {
            case SLIGHT -> FLOOR_LAVA_SPRING_SMALL.get();
            case NORMAL -> FLOOR_LAVA_SPRING_NORMAL.get();
            case LARGE -> FLOOR_LAVA_SPRING_LARGE.get();
            case HEAVY -> FLOOR_LAVA_SPRING_HEAVY.get();
        };
    }

    public static FloorSpringBlock pickGeneratedFloorLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava) {
        return pickGeneratedFloorLavaBlock(random, y, nearLava, nearLava ? 1 : 0);
    }

    public static FloorSpringBlock pickGeneratedFloorBlock(net.minecraft.util.RandomSource random, int y, int seaLevel, boolean damp) {
        SpringStrength strength = SpringStrength.pickGeneratedVariant(random, y, seaLevel, damp);
        return switch (strength) {
            case SLIGHT -> FLOOR_SPRING_SMALL.get();
            case NORMAL -> FLOOR_SPRING_NORMAL.get();
            case LARGE -> FLOOR_SPRING_LARGE.get();
            case HEAVY -> FLOOR_SPRING_HEAVY.get();
        };
    }

    public static CeilingSpringBlock pickGeneratedCeilingBlock(net.minecraft.util.RandomSource random, int y, int seaLevel, boolean damp) {
        SpringStrength strength = SpringStrength.pickGeneratedVariant(random, y, seaLevel, damp);
        return switch (strength) {
            case SLIGHT -> CEILING_SPRING_SMALL.get();
            case NORMAL -> CEILING_SPRING_NORMAL.get();
            case LARGE -> CEILING_SPRING_LARGE.get();
            case HEAVY -> CEILING_SPRING_HEAVY.get();
        };
    }

    public static CeilingSpringBlock pickGeneratedCeilingLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava, int lavaRichness) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, nearLava, lavaRichness, 0);
        return switch (strength) {
            case SLIGHT -> CEILING_LAVA_SPRING_SMALL.get();
            case NORMAL -> CEILING_LAVA_SPRING_NORMAL.get();
            case LARGE -> CEILING_LAVA_SPRING_LARGE.get();
            case HEAVY -> CEILING_LAVA_SPRING_HEAVY.get();
        };
    }

    public static CeilingSpringBlock pickGeneratedCeilingLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava, int lavaRichness, int depthBonus) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, nearLava, lavaRichness, depthBonus);
        return switch (strength) {
            case SLIGHT -> CEILING_LAVA_SPRING_SMALL.get();
            case NORMAL -> CEILING_LAVA_SPRING_NORMAL.get();
            case LARGE -> CEILING_LAVA_SPRING_LARGE.get();
            case HEAVY -> CEILING_LAVA_SPRING_HEAVY.get();
        };
    }

    public static CeilingSpringBlock pickGeneratedCeilingLavaBlock(net.minecraft.util.RandomSource random, int y, int minBuildHeight, boolean netherLike, boolean nearLava, int lavaRichness, int depthBonus) {
        SpringStrength strength = SpringStrength.pickGeneratedLavaVariant(random, y, minBuildHeight, netherLike, nearLava, lavaRichness, depthBonus);
        return switch (strength) {
            case SLIGHT -> CEILING_LAVA_SPRING_SMALL.get();
            case NORMAL -> CEILING_LAVA_SPRING_NORMAL.get();
            case LARGE -> CEILING_LAVA_SPRING_LARGE.get();
            case HEAVY -> CEILING_LAVA_SPRING_HEAVY.get();
        };
    }

    public static CeilingSpringBlock pickGeneratedCeilingLavaBlock(net.minecraft.util.RandomSource random, int y, boolean nearLava) {
        return pickGeneratedCeilingLavaBlock(random, y, nearLava, nearLava ? 1 : 0);
    }

    private static DeferredHolder<Block, WallSpringBlock> registerSpring(SpringStrength strength) {
        DeferredHolder<Block, WallSpringBlock> block = BLOCKS.register(
                strength.waterBlockName(),
                () -> new WallSpringBlock(strength, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, ParticleTypes.DRIPPING_WATER)
        );
        ITEMS.register(strength.waterBlockName(), () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static DeferredHolder<Block, WallSpringBlock> registerLavaSpring(SpringStrength strength) {
        DeferredHolder<Block, WallSpringBlock> block = BLOCKS.register(
                strength.lavaBlockName(),
                () -> new WallSpringBlock(strength, (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, ParticleTypes.DRIPPING_LAVA)
        );
        ITEMS.register(strength.lavaBlockName(), () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static DeferredHolder<Block, FloorSpringBlock> registerFloorSpring(SpringStrength strength) {
        DeferredHolder<Block, FloorSpringBlock> block = BLOCKS.register(
                strength.floorWaterBlockName(),
                () -> new FloorSpringBlock(strength, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, ParticleTypes.DRIPPING_WATER)
        );
        ITEMS.register(strength.floorWaterBlockName(), () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static DeferredHolder<Block, FloorSpringBlock> registerFloorLavaSpring(SpringStrength strength) {
        DeferredHolder<Block, FloorSpringBlock> block = BLOCKS.register(
                strength.floorLavaBlockName(),
                () -> new FloorSpringBlock(strength, (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, ParticleTypes.DRIPPING_LAVA)
        );
        ITEMS.register(strength.floorLavaBlockName(), () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static DeferredHolder<Block, CeilingSpringBlock> registerCeilingSpring(SpringStrength strength) {
        DeferredHolder<Block, CeilingSpringBlock> block = BLOCKS.register(
                strength.ceilingWaterBlockName(),
                () -> new CeilingSpringBlock(strength, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, ParticleTypes.DRIPPING_WATER)
        );
        ITEMS.register(strength.ceilingWaterBlockName(), () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static DeferredHolder<Block, CeilingSpringBlock> registerCeilingLavaSpring(SpringStrength strength) {
        DeferredHolder<Block, CeilingSpringBlock> block = BLOCKS.register(
                strength.ceilingLavaBlockName(),
                () -> new CeilingSpringBlock(strength, (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, ParticleTypes.DRIPPING_LAVA)
        );
        ITEMS.register(strength.ceilingLavaBlockName(), () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(WALL_SPRING_SMALL.get());
            event.accept(WALL_SPRING_NORMAL.get());
            event.accept(WALL_SPRING_LARGE.get());
            event.accept(WALL_SPRING_HEAVY.get());
            event.accept(FLOOR_SPRING_SMALL.get());
            event.accept(FLOOR_SPRING_NORMAL.get());
            event.accept(FLOOR_SPRING_LARGE.get());
            event.accept(FLOOR_SPRING_HEAVY.get());
            event.accept(CEILING_SPRING_SMALL.get());
            event.accept(CEILING_SPRING_NORMAL.get());
            event.accept(CEILING_SPRING_LARGE.get());
            event.accept(CEILING_SPRING_HEAVY.get());
            event.accept(WALL_LAVA_SPRING_SMALL.get());
            event.accept(WALL_LAVA_SPRING_NORMAL.get());
            event.accept(WALL_LAVA_SPRING_LARGE.get());
            event.accept(WALL_LAVA_SPRING_HEAVY.get());
            event.accept(FLOOR_LAVA_SPRING_SMALL.get());
            event.accept(FLOOR_LAVA_SPRING_NORMAL.get());
            event.accept(FLOOR_LAVA_SPRING_LARGE.get());
            event.accept(FLOOR_LAVA_SPRING_HEAVY.get());
            event.accept(CEILING_LAVA_SPRING_SMALL.get());
            event.accept(CEILING_LAVA_SPRING_NORMAL.get());
            event.accept(CEILING_LAVA_SPRING_LARGE.get());
            event.accept(CEILING_LAVA_SPRING_HEAVY.get());
        }
    }
}
