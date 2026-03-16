package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

public final class FluidSectionDataCache {
    static final byte LOADED = 1;
    static final byte AIR = 1 << 1;
    static final byte REPLACEABLE = 1 << 2;
    static final byte SOLID = 1 << 3;
    static final byte HAS_FLUID = 1 << 4;

    private static final SectionData UNLOADED = new SectionData(false, new byte[0], new short[0], new Fluid[0]);

    private final Level level;
    private final Long2ObjectOpenHashMap<SectionData> sections;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    public FluidSectionDataCache(Level level, int expectedSections) {
        this.level = level;
        this.sections = new Long2ObjectOpenHashMap<>(Math.max(16, expectedSections));
    }

    public int amount(BlockPos pos) {
        return amount(pos.getX(), pos.getY(), pos.getZ());
    }

    public int amount(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        return section.amounts()[sectionIndex(x, y, z)] & 0xFFFF;
    }

    public int amountIfFluid(BlockPos pos, Fluid fluid) {
        return amountIfFluid(pos.getX(), pos.getY(), pos.getZ(), fluid);
    }

    public int amountIfFluid(int x, int y, int z, Fluid fluid) {
        if (fluid == null) {
            return 0;
        }
        Fluid present = fluidType(x, y, z);
        return present != null && present.isSame(fluid) ? amount(x, y, z) : 0;
    }

    short rawAmount(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        return section.amounts()[sectionIndex(x, y, z)];
    }

    byte flags(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        return section.flags()[sectionIndex(x, y, z)];
    }

    public Fluid fluidType(BlockPos pos) {
        return fluidType(pos.getX(), pos.getY(), pos.getZ());
    }

    public Fluid fluidType(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return null;
        }
        return section.fluids()[sectionIndex(x, y, z)];
    }

    public boolean canAcceptFluid(BlockPos pos) {
        byte flags = flags(pos.getX(), pos.getY(), pos.getZ());
        return (flags & AIR) != 0 || (flags & REPLACEABLE) != 0 || (flags & HAS_FLUID) != 0;
    }

    public int supportScore(BlockPos pos, Fluid fallbackFluid, Direction[] horizontalDirections) {
        int score = 0;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        byte belowFlags = flags(x, y - 1, z);
        Fluid belowFluid = fluidType(x, y - 1, z);
        if (belowFluid != null && (fallbackFluid == null || belowFluid.isSame(fallbackFluid))) {
            score += 3;
        } else if ((belowFlags & LOADED) != 0 && (belowFlags & AIR) == 0 && (belowFlags & REPLACEABLE) == 0) {
            score += 2;
        }

        for (Direction direction : horizontalDirections) {
            Fluid neighborFluid = fluidType(
                x + direction.getStepX(),
                y + direction.getStepY(),
                z + direction.getStepZ()
            );
            if (neighborFluid != null && (fallbackFluid == null || neighborFluid.isSame(fallbackFluid))) {
                score++;
            }
        }

        return score;
    }

    public int columnHeight(BlockPos origin, Fluid sourceFluid, int maxScan) {
        if (sourceFluid == null || maxScan <= 0) {
            return 0;
        }
        int x = origin.getX();
        int z = origin.getZ();
        int height = 0;
        for (int step = 1; step <= maxScan; step++) {
            int y = origin.getY() + step;
            Fluid fluid = fluidType(x, y, z);
            if (fluid == null || !fluid.isSame(sourceFluid) || amount(x, y, z) <= 0) {
                break;
            }
            height++;
        }
        return height;
    }

    public void invalidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        int sectionX = Math.floorDiv(pos.getX(), 16);
        int sectionY = Math.floorDiv(pos.getY(), 16);
        int sectionZ = Math.floorDiv(pos.getZ(), 16);
        sections.remove(BlockPos.asLong(sectionX, sectionY, sectionZ));
    }

    private SectionData getSection(int x, int y, int z) {
        int sectionX = Math.floorDiv(x, 16);
        int sectionY = Math.floorDiv(y, 16);
        int sectionZ = Math.floorDiv(z, 16);
        long key = BlockPos.asLong(sectionX, sectionY, sectionZ);
        SectionData cached = sections.get(key);
        if (cached != null) {
            return cached;
        }

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int sectionMinY = sectionY << 4;
        if (sectionMinY > maxY || sectionMinY + 15 < minY) {
            sections.put(key, UNLOADED);
            return UNLOADED;
        }

        scratch.set(sectionX << 4, Math.max(minY, sectionMinY), sectionZ << 4);
        if (!level.isLoaded(scratch)) {
            sections.put(key, UNLOADED);
            return UNLOADED;
        }

        SectionData built = buildSection(sectionX, sectionY, sectionZ, minY, maxY);
        sections.put(key, built);
        return built;
    }

    private SectionData buildSection(int sectionX, int sectionY, int sectionZ, int minY, int maxY) {
        byte[] flags = new byte[4096];
        short[] amounts = new short[4096];
        Fluid[] fluids = new Fluid[4096];
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;

        for (int localY = 0; localY < 16; localY++) {
            int y = baseY + localY;
            if (y < minY || y > maxY) {
                continue;
            }
            for (int localZ = 0; localZ < 16; localZ++) {
                int z = baseZ + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    int x = baseX + localX;
                    scratch.set(x, y, z);
                    int index = sectionIndex(localX, localY, localZ);
                    BlockState state = level.getBlockState(scratch);
                    FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, scratch, state);
                    byte cellFlags = LOADED;
                    if (state.isAir()) {
                        cellFlags |= AIR;
                    }
                    if (state.canBeReplaced()) {
                        cellFlags |= REPLACEABLE;
                    }
                    if (state.isSolid()) {
                        cellFlags |= SOLID;
                    }
                    if (!fluidState.isEmpty()) {
                        cellFlags |= HAS_FLUID;
                        fluids[index] = fluidState.getType();
                    }

                    int amount = FluidSpatialGrid.getFluidAmount(level, scratch);
                    if (amount <= 0 && !fluidState.isEmpty()) {
                        amount = FluidAmountConverter.toInternal(fluidState.getAmount());
                    }

                    flags[index] = cellFlags;
                    amounts[index] = (short) Math.max(0, Math.min(Short.MAX_VALUE, amount));
                }
            }
        }
        return new SectionData(true, flags, amounts, fluids);
    }

    private static int sectionIndex(int x, int y, int z) {
        return (((y & 15) << 4) | (z & 15)) << 4 | (x & 15);
    }

    private record SectionData(boolean loaded, byte[] flags, short[] amounts, Fluid[] fluids) {
    }
}
