package traben.flowing_fluids.forge.hydraulic;

import traben.flowing_fluids.FluidRegressionLogic;

enum FlowAnchorTier {
    DROPLET("flow_anchor_droplet", 8, 4, 96, 220, 255),
    BROOK("flow_anchor_brook", 16, 6, 84, 200, 255),
    CHANNEL("flow_anchor_channel", 24, 8, 72, 176, 255),
    WELLSPRING("flow_anchor_wellspring", 32, 10, 124, 232, 255),
    LAKEHEART("flow_anchor_lakeheart", 48, 12, 180, 244, 255);

    private final String blockName;
    private final int processingRadius;
    private final int visualRadius;
    private final int lightLevel;
    private final int red;
    private final int green;
    private final int blue;

    FlowAnchorTier(String blockName, int processingRadius, int lightLevel, int red, int green, int blue) {
        this.blockName = blockName;
        this.processingRadius = processingRadius;
        this.visualRadius = FluidRegressionLogic.getPlayerVisualMaintenanceDistance(processingRadius);
        this.lightLevel = lightLevel;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    String blockName() {
        return blockName;
    }

    int processingRadius() {
        return processingRadius;
    }

    int visualRadius() {
        return visualRadius;
    }

    int lightLevel() {
        return lightLevel;
    }

    int red() {
        return red;
    }

    int green() {
        return green;
    }

    int blue() {
        return blue;
    }
}
