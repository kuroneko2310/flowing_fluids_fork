package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.BitSet;

public final class FluidSectionDataCache {
    static final byte LOADED = 1;
    static final byte AIR = 1 << 1;
    static final byte REPLACEABLE = 1 << 2;
    static final byte SOLID = 1 << 3;
    static final byte HAS_FLUID = 1 << 4;
    static final byte PASS_THROUGH = 1 << 6;
    static final byte BINARY_FLUID_STORAGE = (byte) (1 << 7);

    private static final SectionData UNLOADED = new SectionData(false, new byte[0], new short[0], new Fluid[0], new BitSet());

    private final Level level;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final Long2ObjectOpenHashMap<SectionData> sections;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private long lastSectionKey = Long.MIN_VALUE;
    private SectionData lastSection;

    public FluidSectionDataCache(Level level, int expectedSections) {
        this.level = level;
        this.minBuildHeight = level.getMinBuildHeight();
        this.maxBuildHeight = level.getMaxBuildHeight();
        this.sections = new Long2ObjectOpenHashMap<>(Math.max(16, expectedSections));
    }

    public int amount(BlockPos pos) {
        return amount(pos.getX(), pos.getY(), pos.getZ());
    }

    public int internalAmount(BlockPos pos) {
        return internalAmount(pos.getX(), pos.getY(), pos.getZ());
    }

    public int amount(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        ensureCell(section, x, y, z);
        return FluidAmountConverter.toBlockState(section.amounts()[sectionIndex(x, y, z)] & 0xFFFF);
    }

    public int amountIfFluid(BlockPos pos, Fluid fluid) {
        return amountIfFluid(pos.getX(), pos.getY(), pos.getZ(), fluid);
    }

    public int amountIfFluid(int x, int y, int z, Fluid fluid) {
        if (fluid == null) {
            return 0;
        }
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        ensureCell(section, x, y, z);
        int index = sectionIndex(x, y, z);
        Fluid present = section.fluids()[index];
        return present != null && present.isSame(fluid)
                ? FluidAmountConverter.toBlockState(section.amounts()[index] & 0xFFFF)
                : 0;
    }

    short rawAmount(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        ensureCell(section, x, y, z);
        return section.amounts()[sectionIndex(x, y, z)];
    }

    public int internalAmount(int x, int y, int z) {
        return rawAmount(x, y, z) & 0xFFFF;
    }

    byte flags(int x, int y, int z) {
        SectionData section = getSection(x, y, z);
        if (!section.loaded()) {
            return 0;
        }
        ensureCell(section, x, y, z);
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
        ensureCell(section, x, y, z);
        return section.fluids()[sectionIndex(x, y, z)];
    }

    public boolean canAcceptFluid(BlockPos pos) {
        byte flags = flags(pos.getX(), pos.getY(), pos.getZ());
        return (flags & AIR) != 0 || (flags & REPLACEABLE) != 0 || (flags & HAS_FLUID) != 0;
    }

    public boolean isAir(int x, int y, int z) {
        return (flags(x, y, z) & AIR) != 0;
    }

    public boolean isReplaceable(int x, int y, int z) {
        return (flags(x, y, z) & REPLACEABLE) != 0;
    }

    public boolean isPassThrough(int x, int y, int z) {
        return (flags(x, y, z) & PASS_THROUGH) != 0;
    }

    public boolean isBinaryFluidStorage(BlockPos pos) {
        return (flags(pos.getX(), pos.getY(), pos.getZ()) & BINARY_FLUID_STORAGE) != 0;
    }

    public int supportScore(BlockPos pos, Fluid fallbackFluid, Direction[] horizontalDirections) {
        int score = 0;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        SectionData belowSection = getSection(x, y - 1, z);
        int belowIndex = sectionIndex(x, y - 1, z);
        if (belowSection.loaded()) {
            ensureCell(belowSection, x, y - 1, z);
        }
        byte belowFlags = belowSection.loaded() ? belowSection.flags()[belowIndex] : 0;
        Fluid belowFluid = belowSection.loaded() ? belowSection.fluids()[belowIndex] : null;
        if (belowFluid != null && (fallbackFluid == null || belowFluid.isSame(fallbackFluid))) {
            score += 3;
        } else if (isSolidSupportFlags(belowFlags)) {
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

    static boolean isSolidSupportFlags(byte flags) {
        return (flags & LOADED) != 0
            && (flags & AIR) == 0
            && (flags & REPLACEABLE) == 0
            && (flags & PASS_THROUGH) == 0;
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
            SectionData section = getSection(x, y, z);
            if (!section.loaded()) {
                break;
            }
            ensureCell(section, x, y, z);
            int index = sectionIndex(x, y, z);
            Fluid fluid = section.fluids()[index];
            if (fluid == null || !fluid.isSame(sourceFluid) || (section.amounts()[index] & 0xFFFF) <= 0) {
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
        long key = BlockPos.asLong(sectionX, sectionY, sectionZ);
        sections.remove(key);
        if (lastSectionKey == key) {
            lastSectionKey = Long.MIN_VALUE;
            lastSection = null;
        }
    }

    private SectionData getSection(int x, int y, int z) {
        int sectionX = Math.floorDiv(x, 16);
        int sectionY = Math.floorDiv(y, 16);
        int sectionZ = Math.floorDiv(z, 16);
        long key = BlockPos.asLong(sectionX, sectionY, sectionZ);
        if (lastSectionKey == key && lastSection != null) {
            return lastSection;
        }
        SectionData cached = sections.get(key);
        if (cached != null) {
            lastSectionKey = key;
            lastSection = cached;
            return cached;
        }

        int sectionMinY = sectionY << 4;
        if (sectionMinY >= maxBuildHeight || sectionMinY + 15 < minBuildHeight) {
            sections.put(key, UNLOADED);
            lastSectionKey = key;
            lastSection = UNLOADED;
            return UNLOADED;
        }

        scratch.set(sectionX << 4, Math.max(minBuildHeight, sectionMinY), sectionZ << 4);
        if (!level.isLoaded(scratch)) {
            sections.put(key, UNLOADED);
            lastSectionKey = key;
            lastSection = UNLOADED;
            return UNLOADED;
        }

        SectionData built = new SectionData(true, new byte[4096], new short[4096], new Fluid[4096], new BitSet(4096));
        sections.put(key, built);
        lastSectionKey = key;
        lastSection = built;
        return built;
    }

    private void ensureCell(SectionData section, int x, int y, int z) {
        int index = sectionIndex(x, y, z);
        if (section.initialized().get(index)) {
            return;
        }
        if (y < minBuildHeight || y >= maxBuildHeight) {
            section.initialized().set(index);
            return;
        }
        scratch.set(x, y, z);
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
        if (FFFluidUtils.isPassThroughFluidBlock(level, state, null)) {
            cellFlags |= PASS_THROUGH;
        }
        if (FFFluidUtils.isVanillaWaterloggable(state)) {
            cellFlags |= BINARY_FLUID_STORAGE;
        }
        if (!fluidState.isEmpty()) {
            cellFlags |= HAS_FLUID;
            section.fluids()[index] = fluidState.getType();
        }

        int amount = 0;
        if (!fluidState.isEmpty()) {
            int worldAmount = fluidState.getAmount();
            int cachedAmount = FluidSpatialGrid.getFluidAmount(level, scratch);
            amount = cachedAmount > 0
                && FluidAmountConverter.toBlockState(cachedAmount) == worldAmount
                ? cachedAmount
                : FluidAmountConverter.toInternal(worldAmount);
        }

        section.flags()[index] = cellFlags;
        section.amounts()[index] = (short) Math.max(0, Math.min(Short.MAX_VALUE, amount));
        section.initialized().set(index);
    }

    private static int sectionIndex(int x, int y, int z) {
        return (((y & 15) << 4) | (z & 15)) << 4 | (x & 15);
    }

    private record SectionData(boolean loaded, byte[] flags, short[] amounts, Fluid[] fluids, BitSet initialized) {
    }
}
