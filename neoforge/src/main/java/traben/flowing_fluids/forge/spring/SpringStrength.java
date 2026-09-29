package traben.flowing_fluids.forge.spring;

import net.minecraft.util.RandomSource;

public enum SpringStrength {
    SLIGHT("small", 1, 36, 52),
    NORMAL("normal", 2, 24, 36),
    LARGE("large", 3, 14, 24),
    HEAVY("heavy", 4, 8, 16);

    private final String suffix;
    private final int emissionAmount;
    private final int minDelay;
    private final int maxDelay;

    SpringStrength(String suffix, int emissionAmount, int minDelay, int maxDelay) {
        this.suffix = suffix;
        this.emissionAmount = emissionAmount;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
    }

    public String waterBlockName() {
        return "wall_spring_" + suffix;
    }

    public String lavaBlockName() {
        return "wall_lava_spring_" + suffix;
    }

    public String floorWaterBlockName() {
        return "floor_spring_" + suffix;
    }

    public String floorLavaBlockName() {
        return "floor_lava_spring_" + suffix;
    }

    public String ceilingWaterBlockName() {
        return "ceiling_spring_" + suffix;
    }

    public String ceilingLavaBlockName() {
        return "ceiling_lava_spring_" + suffix;
    }

    public int emissionAmount() {
        return emissionAmount;
    }

    public int minimumDelay() {
        return minDelay;
    }

    public int nextDelay(RandomSource random) {
        if (maxDelay <= minDelay) {
            return minDelay;
        }
        return minDelay + random.nextInt(maxDelay - minDelay + 1);
    }

    public int pulseMinHeight() {
        return switch (this) {
            case SLIGHT -> 1;
            case NORMAL -> 1;
            case LARGE -> 2;
            case HEAVY -> 3;
        };
    }

    public int pulseMaxHeight() {
        return switch (this) {
            case SLIGHT -> 3;
            case NORMAL -> 4;
            case LARGE -> 5;
            case HEAVY -> 5;
        };
    }

    public static SpringStrength pickGeneratedVariant(RandomSource random, int y, int seaLevel, boolean damp) {
        int depthScore = 0;
        int belowSeaLevel = seaLevel - y;
        if (belowSeaLevel > 10) {
            depthScore++;
        }
        if (belowSeaLevel > 24) {
            depthScore++;
        }
        if (belowSeaLevel > 40) {
            depthScore++;
        }
        if (damp) {
            depthScore++;
        }

        int roll = random.nextInt(100);
        return switch (Math.min(depthScore, 4)) {
            case 0 -> roll < 62 ? SLIGHT : roll < 90 ? NORMAL : roll < 98 ? LARGE : HEAVY;
            case 1 -> roll < 44 ? SLIGHT : roll < 80 ? NORMAL : roll < 95 ? LARGE : HEAVY;
            case 2 -> roll < 28 ? SLIGHT : roll < 67 ? NORMAL : roll < 90 ? LARGE : HEAVY;
            case 3 -> roll < 16 ? SLIGHT : roll < 52 ? NORMAL : roll < 82 ? LARGE : HEAVY;
            default -> roll < 10 ? SLIGHT : roll < 40 ? NORMAL : roll < 74 ? LARGE : HEAVY;
        };
    }

    public static SpringStrength pickGeneratedLavaVariant(RandomSource random, int y, boolean nearLava, int lavaRichness, int depthBonus) {
        return pickGeneratedLavaVariant(random, y, -64, false, nearLava, lavaRichness, depthBonus);
    }

    public static SpringStrength pickGeneratedLavaVariant(RandomSource random, int y, int minBuildHeight, boolean netherLike, boolean nearLava, int lavaRichness, int depthBonus) {
        int heatScore = 0;
        if (SpringDimensionContext.isBelowLegacyAbsoluteY(y, minBuildHeight, 64, netherLike)) {
            heatScore++;
        }
        if (SpringDimensionContext.isBelowLegacyAbsoluteY(y, minBuildHeight, 32, netherLike)) {
            heatScore++;
        }
        if (SpringDimensionContext.isBelowLegacyAbsoluteY(y, minBuildHeight, 0, netherLike)) {
            heatScore++;
        }
        if (nearLava) {
            heatScore++;
        }
        heatScore += Math.min(2, Math.max(0, lavaRichness - 1));
        heatScore += Math.min(2, Math.max(0, depthBonus));

        int roll = random.nextInt(100);
        return switch (Math.min(heatScore, 6)) {
            case 0 -> roll < 70 ? SLIGHT : roll < 92 ? NORMAL : roll < 98 ? LARGE : HEAVY;
            case 1 -> roll < 52 ? SLIGHT : roll < 84 ? NORMAL : roll < 96 ? LARGE : HEAVY;
            case 2 -> roll < 34 ? SLIGHT : roll < 68 ? NORMAL : roll < 90 ? LARGE : HEAVY;
            case 3 -> roll < 20 ? SLIGHT : roll < 54 ? NORMAL : roll < 82 ? LARGE : HEAVY;
            case 4 -> roll < 10 ? SLIGHT : roll < 38 ? NORMAL : roll < 70 ? LARGE : HEAVY;
            case 5 -> roll < 4 ? SLIGHT : roll < 26 ? NORMAL : roll < 60 ? LARGE : HEAVY;
            default -> roll < 2 ? SLIGHT : roll < 18 ? NORMAL : roll < 48 ? LARGE : HEAVY;
        };
    }

    public static SpringStrength pickGeneratedLavaVariant(RandomSource random, int y, boolean nearLava, int lavaRichness) {
        return pickGeneratedLavaVariant(random, y, -64, false, nearLava, lavaRichness, 0);
    }

    public static SpringStrength pickGeneratedLavaVariant(RandomSource random, int y, boolean nearLava) {
        return pickGeneratedLavaVariant(random, y, -64, false, nearLava, nearLava ? 1 : 0, 0);
    }
}
