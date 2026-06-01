package traben.flowing_fluids.forge.hydraulic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import traben.flowing_fluids.FlowingFluids;

import java.util.ArrayList;
import java.util.List;

public final class FlowAnchorRangeRenderer {
    private static final int PROCESSING_SEGMENTS = 40;
    private static final int VISUAL_SEGMENTS = 56;
    private static final int HELD_DISPLAY_RADIUS = 96;
    private static BlockPos ff$pinnedAnchorPos;
    private static List<NearbyAnchor> ff$heldVisibleAnchors = List.of();
    private static List<NearbySuppressor> ff$heldVisibleSuppressors = List.of();
    private static long ff$lastHeldScanGameTime = Long.MIN_VALUE;
    private static BlockPos ff$lastHeldScanOrigin = BlockPos.ZERO;

    private FlowAnchorRangeRenderer() {
    }

    @Mod.EventBusSubscriber(modid = FlowingFluids.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onBlockHighlight(RenderHighlightEvent.Block event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }

            BlockPos pos = event.getTarget().getBlockPos();
            if (minecraft.level.getBlockState(pos).getBlock() instanceof FlowAnchorBlock block) {
                ff$renderAnchor(event.getPoseStack(), event.getCamera().getPosition(), event.getMultiBufferSource(), pos, block.tier(), true);
                return;
            }
            if (minecraft.level.getBlockEntity(pos) instanceof OceanRefillSuppressorBlockEntity suppressor) {
                ff$renderSuppressor(event.getPoseStack(), event.getCamera().getPosition(), event.getMultiBufferSource(), pos, suppressor.radius(), true);
            }
        }

        @SubscribeEvent
        public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
                return;
            }

            HitResult hitResult = minecraft.hitResult;
            if (!(hitResult instanceof BlockHitResult blockHitResult)) {
                return;
            }

            BlockPos pos = blockHitResult.getBlockPos();
            if (!(minecraft.level.getBlockState(pos).getBlock() instanceof FlowAnchorBlock)) {
                return;
            }

            event.setCanceled(true);
            event.setSwingHand(false);

            if (pos.equals(ff$pinnedAnchorPos)) {
                ff$pinnedAnchorPos = null;
                minecraft.player.displayClientMessage(Component.translatable("message.flowing_fluids.flow_anchor_range.off"), true);
                return;
            }

            ff$pinnedAnchorPos = pos.immutable();
            minecraft.player.displayClientMessage(Component.translatable("message.flowing_fluids.flow_anchor_range.on"), true);
        }

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                ff$pinnedAnchorPos = null;
                ff$clearHeldAnchors();
                return;
            }

            BlockPos highlightedAnchorPos = ff$getHighlightedAnchorPos(minecraft);
            MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
            boolean rendered = false;

            if (minecraft.player != null && ff$isHoldingSurveyor(minecraft.player)) {
                ff$refreshHeldAnchors(minecraft);
                for (NearbyAnchor anchor : ff$heldVisibleAnchors) {
                    if (anchor.pos().equals(highlightedAnchorPos)) {
                        continue;
                    }
                    ff$renderAnchor(
                        event.getPoseStack(),
                        event.getCamera().getPosition(),
                        bufferSource,
                        anchor.pos(),
                        anchor.tier(),
                        true
                    );
                    rendered = true;
                }
                BlockPos highlightedSuppressorPos = ff$getHighlightedSuppressorPos(minecraft);
                for (NearbySuppressor suppressor : ff$heldVisibleSuppressors) {
                    if (suppressor.pos().equals(highlightedSuppressorPos)) {
                        continue;
                    }
                    ff$renderSuppressor(
                        event.getPoseStack(),
                        event.getCamera().getPosition(),
                        bufferSource,
                        suppressor.pos(),
                        suppressor.radius(),
                        true
                    );
                    rendered = true;
                }
            } else {
                ff$clearHeldAnchors();
            }

            if (ff$pinnedAnchorPos != null) {
                if (!(minecraft.level.getBlockState(ff$pinnedAnchorPos).getBlock() instanceof FlowAnchorBlock block)) {
                    ff$pinnedAnchorPos = null;
                } else if (!ff$pinnedAnchorPos.equals(highlightedAnchorPos)) {
                    ff$renderAnchor(
                        event.getPoseStack(),
                        event.getCamera().getPosition(),
                        bufferSource,
                        ff$pinnedAnchorPos,
                        block.tier(),
                        false
                    );
                    rendered = true;
                }
            }

            if (rendered) {
                bufferSource.endBatch(RenderType.lines());
            }
        }
    }

    private static boolean ff$isHoldingSurveyor(Player player) {
        return player.getMainHandItem().is(ForgeHydraulicBlockRegistry.FLOW_ANCHOR_SURVEYOR.get())
            || player.getOffhandItem().is(ForgeHydraulicBlockRegistry.FLOW_ANCHOR_SURVEYOR.get());
    }

    private static void ff$refreshHeldAnchors(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            ff$clearHeldAnchors();
            return;
        }

        BlockPos playerPos = minecraft.player.blockPosition();
        long gameTime = minecraft.level.getGameTime();
        if (gameTime == ff$lastHeldScanGameTime && playerPos.equals(ff$lastHeldScanOrigin)) {
            return;
        }

        ff$lastHeldScanGameTime = gameTime;
        ff$lastHeldScanOrigin = playerPos.immutable();

        int radiusSq = HELD_DISPLAY_RADIUS * HELD_DISPLAY_RADIUS;
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int chunkRadius = Mth.ceil((float) HELD_DISPLAY_RADIUS / 16.0F);
        List<NearbyAnchor> nearbyAnchors = new ArrayList<>();
        List<NearbySuppressor> nearbySuppressors = new ArrayList<>();

        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                if (!minecraft.level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = minecraft.level.getChunk(chunkX, chunkZ);
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity.getBlockState().getBlock() instanceof FlowAnchorBlock block)) {
                        if (blockEntity instanceof OceanRefillSuppressorBlockEntity suppressor) {
                            BlockPos suppressorPos = blockEntity.getBlockPos();
                            if (suppressorPos.distSqr(playerPos) <= radiusSq) {
                                nearbySuppressors.add(new NearbySuppressor(suppressorPos.immutable(), suppressor.radius()));
                            }
                        }
                        continue;
                    }

                    BlockPos anchorPos = blockEntity.getBlockPos();
                    if (anchorPos.distSqr(playerPos) > radiusSq) {
                        continue;
                    }

                    nearbyAnchors.add(new NearbyAnchor(anchorPos.immutable(), block.tier()));
                }
            }
        }

        ff$heldVisibleAnchors = nearbyAnchors;
        ff$heldVisibleSuppressors = nearbySuppressors;
    }

    private static void ff$clearHeldAnchors() {
        ff$heldVisibleAnchors = List.of();
        ff$heldVisibleSuppressors = List.of();
        ff$lastHeldScanGameTime = Long.MIN_VALUE;
        ff$lastHeldScanOrigin = BlockPos.ZERO;
    }

    private static BlockPos ff$getHighlightedAnchorPos(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult blockHitResult)) {
            return null;
        }
        BlockPos hitPos = blockHitResult.getBlockPos();
        return minecraft.level.getBlockState(hitPos).getBlock() instanceof FlowAnchorBlock ? hitPos : null;
    }

    private static BlockPos ff$getHighlightedSuppressorPos(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult blockHitResult)) {
            return null;
        }
        BlockPos hitPos = blockHitResult.getBlockPos();
        return minecraft.level.getBlockEntity(hitPos) instanceof OceanRefillSuppressorBlockEntity ? hitPos : null;
    }

    private static void ff$renderAnchor(PoseStack poseStack,
                                        Vec3 cameraPos,
                                        MultiBufferSource bufferSource,
                                        BlockPos pos,
                                        FlowAnchorTier tier,
                                        boolean includeBlockOutline) {
        Vec3 center = Vec3.atCenterOf(pos);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (includeBlockOutline) {
            LevelRenderer.renderLineBox(
                poseStack,
                consumer,
                new AABB(pos),
                1.0F,
                1.0F,
                1.0F,
                0.9F
            );
        }

        ff$drawSphereRings(
            poseStack,
            consumer,
            center,
            tier.processingRadius(),
            PROCESSING_SEGMENTS,
            tier.red() / 255.0F,
            tier.green() / 255.0F,
            tier.blue() / 255.0F,
            0.9F
        );

        if (tier.visualRadius() > tier.processingRadius()) {
            ff$drawHorizontalRing(
                poseStack,
                consumer,
                center,
                tier.visualRadius(),
                VISUAL_SEGMENTS,
                0.9F,
                0.97F,
                1.0F,
                0.38F
            );
        }

        poseStack.popPose();
    }

    private static void ff$renderSuppressor(PoseStack poseStack,
                                            Vec3 cameraPos,
                                            MultiBufferSource bufferSource,
                                            BlockPos pos,
                                            int radius,
                                            boolean includeBlockOutline) {
        Vec3 center = Vec3.atCenterOf(pos);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (includeBlockOutline) {
            LevelRenderer.renderLineBox(
                poseStack,
                consumer,
                new AABB(pos),
                1.0F,
                0.76F,
                0.25F,
                0.9F
            );
        }

        ff$drawSphereRings(
            poseStack,
            consumer,
            center,
            radius,
            VISUAL_SEGMENTS,
            1.0F,
            0.36F,
            0.18F,
            0.86F
        );

        poseStack.popPose();
    }

    private static void ff$drawSphereRings(PoseStack poseStack,
                                           VertexConsumer consumer,
                                           Vec3 center,
                                           double radius,
                                           int segments,
                                           float red,
                                           float green,
                                           float blue,
                                           float alpha) {
        ff$drawRing(poseStack, consumer, center, radius, segments, red, green, blue, alpha, RingPlane.HORIZONTAL);
        ff$drawRing(poseStack, consumer, center, radius, segments, red, green, blue, alpha * 0.85F, RingPlane.VERTICAL_X);
        ff$drawRing(poseStack, consumer, center, radius, segments, red, green, blue, alpha * 0.85F, RingPlane.VERTICAL_Z);
    }

    private static void ff$drawHorizontalRing(PoseStack poseStack,
                                              VertexConsumer consumer,
                                              Vec3 center,
                                              double radius,
                                              int segments,
                                              float red,
                                              float green,
                                              float blue,
                                              float alpha) {
        ff$drawRing(poseStack, consumer, center, radius, segments, red, green, blue, alpha, RingPlane.HORIZONTAL);
    }

    private static void ff$drawRing(PoseStack poseStack,
                                    VertexConsumer consumer,
                                    Vec3 center,
                                    double radius,
                                    int segments,
                                    float red,
                                    float green,
                                    float blue,
                                    float alpha,
                                    RingPlane plane) {
        for (int index = 0; index < segments; index++) {
            double startAngle = (Math.PI * 2.0D * index) / segments;
            double endAngle = (Math.PI * 2.0D * (index + 1)) / segments;
            Vec3 start = plane.point(center, radius, startAngle);
            Vec3 end = plane.point(center, radius, endAngle);
            ff$line(poseStack, consumer, start, end, red, green, blue, alpha);
        }
    }

    private static void ff$line(PoseStack poseStack,
                                VertexConsumer consumer,
                                Vec3 start,
                                Vec3 end,
                                float red,
                                float green,
                                float blue,
                                float alpha) {
        PoseStack.Pose pose = poseStack.last();
        Vec3 normal = end.subtract(start).normalize();
        float normalX = (float) normal.x;
        float normalY = (float) normal.y;
        float normalZ = (float) normal.z;
        consumer.vertex(pose.pose(), (float) start.x, (float) start.y, (float) start.z)
            .color(red, green, blue, alpha)
            .normal(pose.normal(), normalX, normalY, normalZ)
            .endVertex();
        consumer.vertex(pose.pose(), (float) end.x, (float) end.y, (float) end.z)
            .color(red, green, blue, alpha)
            .normal(pose.normal(), normalX, normalY, normalZ)
            .endVertex();
    }

    private enum RingPlane {
        HORIZONTAL {
            @Override
            Vec3 point(Vec3 center, double radius, double angle) {
                return new Vec3(
                    center.x + (Mth.cos((float) angle) * radius),
                    center.y,
                    center.z + (Mth.sin((float) angle) * radius)
                );
            }
        },
        VERTICAL_X {
            @Override
            Vec3 point(Vec3 center, double radius, double angle) {
                return new Vec3(
                    center.x,
                    center.y + (Mth.cos((float) angle) * radius),
                    center.z + (Mth.sin((float) angle) * radius)
                );
            }
        },
        VERTICAL_Z {
            @Override
            Vec3 point(Vec3 center, double radius, double angle) {
                return new Vec3(
                    center.x + (Mth.cos((float) angle) * radius),
                    center.y + (Mth.sin((float) angle) * radius),
                    center.z
                );
            }
        };

        abstract Vec3 point(Vec3 center, double radius, double angle);
    }

    private record NearbyAnchor(BlockPos pos, FlowAnchorTier tier) {
    }

    private record NearbySuppressor(BlockPos pos, int radius) {
    }
}
