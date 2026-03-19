package traben.flowing_fluids.mixin;

final class MixinFluidRegressionLogic {

    private MixinFluidRegressionLogic() {
    }

    static boolean isSurfaceEvaporationCandidate(boolean hasSameFluidAbove) {
        return !hasSameFluidAbove;
    }

    static int computeVanillaWaterlogTransferAmount(boolean fromIsWaterloggableVanilla,
                                                    boolean toIsWaterloggableVanilla,
                                                    int amount,
                                                    int destFluidAmount) {
        if (amount <= 0) {
            return 0;
        }
        if (destFluidAmount + amount < 8) {
            return 0;
        }
        int requiredToFill = Math.max(0, 8 - destFluidAmount);
        if (toIsWaterloggableVanilla) {
            return requiredToFill;
        }
        if (fromIsWaterloggableVanilla) {
            return Math.min(amount, requiredToFill);
        }
        return 0;
    }
}
