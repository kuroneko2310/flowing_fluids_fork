package traben.flowing_fluids.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

#if MC > MC_20_1
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
#else
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.Supplier;
#endif

import traben.flowing_fluids.ExtendedWaterlogStore;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ForgePacketHandler {
    private static final int PROTOCOL_VERSION = 3;
    private static final ResourceLocation EMPTY_FLUID_ID = BuiltInRegistries.FLUID.getKey(Fluids.EMPTY);

    public static final SimpleChannel INSTANCE =
        #if MC > MC_20_1
            ChannelBuilder
            .named(FlowingFluids.MOD_ID + "_channel")
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();
        #else
            NetworkRegistry.newSimpleChannel(
                FFFluidUtils.res(FlowingFluids.MOD_ID, "channel"),
                () -> String.valueOf(PROTOCOL_VERSION),
                String.valueOf(PROTOCOL_VERSION)::equals,
                String.valueOf(PROTOCOL_VERSION)::equals
            );
        #endif

    public static void init() {
        #if MC > MC_20_1
        INSTANCE.messageBuilder(FFConfigPacket.class)
                .encoder(FFConfigPacket::encoder)
                .decoder(FFConfigPacket::decoder)
                .consumerMainThread(FFConfigPacket::messageConsumer)
                .add();
        INSTANCE.messageBuilder(FFVirtualFluidUpdatePacket.class)
                .encoder(FFVirtualFluidUpdatePacket::encoder)
                .decoder(FFVirtualFluidUpdatePacket::decoder)
                .consumerMainThread(FFVirtualFluidUpdatePacket::messageConsumer)
                .add();
        INSTANCE.messageBuilder(FFVirtualFluidChunkPacket.class)
                .encoder(FFVirtualFluidChunkPacket::encoder)
                .decoder(FFVirtualFluidChunkPacket::decoder)
                .consumerMainThread(FFVirtualFluidChunkPacket::messageConsumer)
                .add();
        #else
        INSTANCE.registerMessage(0,
                FFConfigPacket.class,
                FFConfigPacket::encoder,
                FFConfigPacket::decoder,
                FFConfigPacket::messageConsumer);
        INSTANCE.registerMessage(1,
                FFVirtualFluidUpdatePacket.class,
                FFVirtualFluidUpdatePacket::encoder,
                FFVirtualFluidUpdatePacket::decoder,
                FFVirtualFluidUpdatePacket::messageConsumer);
        INSTANCE.registerMessage(2,
                FFVirtualFluidChunkPacket.class,
                FFVirtualFluidChunkPacket::encoder,
                FFVirtualFluidChunkPacket::decoder,
                FFVirtualFluidChunkPacket::messageConsumer);
        #endif
    }

    public static void sendVirtualFluidState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        #if MC > MC_20_1
        INSTANCE.send(new FFVirtualFluidUpdatePacket(level, pos), PacketDistributor.TRACKING_CHUNK.with(chunk));
        #else
        INSTANCE.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), new FFVirtualFluidUpdatePacket(level, pos));
        #endif
    }

    public static void sendVirtualFluidChunk(ServerPlayer player, ServerLevel level, ChunkPos chunkPos) {
        #if MC > MC_20_1
        INSTANCE.send(new FFVirtualFluidChunkPacket(level, chunkPos), PacketDistributor.PLAYER.with(player));
        #else
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new FFVirtualFluidChunkPacket(level, chunkPos));
        #endif
    }

    public static void clearVirtualFluidChunk(ServerPlayer player, ServerLevel level, ChunkPos chunkPos) {
        #if MC > MC_20_1
        INSTANCE.send(new FFVirtualFluidChunkPacket(level, chunkPos, List.of()), PacketDistributor.PLAYER.with(player));
        #else
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new FFVirtualFluidChunkPacket(level, chunkPos, List.of()));
        #endif
    }

    private static void writeFluid(FriendlyByteBuf buffer, Fluid fluid) {
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        buffer.writeResourceLocation(fluidId == null ? EMPTY_FLUID_ID : fluidId);
    }

    private static Fluid readFluid(FriendlyByteBuf buffer) {
        ResourceLocation fluidId = buffer.readResourceLocation();
        Fluid fluid = BuiltInRegistries.FLUID.get(fluidId);
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    private static boolean isClientLevel(ResourceLocation dimensionId) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        return level != null && level.dimension().location().equals(dimensionId);
    }

    private static void markVirtualFluidDirty(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (minecraft.levelRenderer == null || level == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        minecraft.levelRenderer.setBlockDirty(pos, state, state);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            BlockState neighborState = level.getBlockState(cursor);
            minecraft.levelRenderer.setBlockDirty(cursor, neighborState, neighborState);
        }
    }

    public static class FFConfigPacket extends FFConfig {
        private boolean isValid;

        FFConfigPacket() {
        }

        FFConfigPacket(FriendlyByteBuf buffer) {
            super(buffer);
        }

        public static FFConfigPacket decoder(FriendlyByteBuf buffer) {
            FFConfigPacket packet;
            if (FMLEnvironment.dist == Dist.CLIENT) {
                try {
                    FlowingFluids.info("- Server Config packet received");
                    packet = new FFConfigPacket(buffer);
                    packet.isValid = true;
                } catch (Exception e) {
                    FlowingFluids.error("- Server Config packet decoding failed.", e);
                    packet = new FFConfigPacket();
                    packet.isValid = false;
                }
            } else {
                packet = new FFConfigPacket(buffer);
                packet.isValid = false;
            }
            return packet;
        }

        #if MC > MC_20_1
        public static void messageConsumer(FFConfigPacket packet, CustomPayloadEvent.Context ctx) {
            if (packet.isValid) {
                FlowingFluids.config = packet;
                FlowingFluids.applyConfigRuntime();
                FlowingFluids.info("- Server Config data received and synced");
            } else {
                FlowingFluids.error("- Server Config data received and failed to sync");
                throw new RuntimeException("[Flowing Fluids] - Server Config data received and failed to sync");
            }
            ctx.setPacketHandled(true);
        }
        #else
        public void messageConsumer(Supplier<NetworkEvent.Context> ctx) {
            if (isValid) {
                FlowingFluids.config = this;
                FlowingFluids.applyConfigRuntime();
                FlowingFluids.info("- Server Config data received and synced");
            } else {
                FlowingFluids.error("- Server Config data received and failed to sync");
                throw new RuntimeException("[Flowing Fluids] - Server Config data received and failed to sync");
            }
            ctx.get().setPacketHandled(true);
        }
        #endif

        public void encoder(FriendlyByteBuf buffer) {
            FlowingFluids.config.encodeToByteBuffer(buffer);
        }
    }

    public static class FFVirtualFluidUpdatePacket {
        private final ResourceLocation dimensionId;
        private final BlockPos pos;
        private final Fluid fluid;
        private final int amount;

        FFVirtualFluidUpdatePacket(ServerLevel level, BlockPos pos) {
            this(level.dimension().location(), pos,
                    ExtendedWaterlogStore.get(level, pos).getType(),
                    ExtendedWaterlogStore.getAmount(level, pos));
        }

        FFVirtualFluidUpdatePacket(ResourceLocation dimensionId, BlockPos pos, Fluid fluid, int amount) {
            this.dimensionId = dimensionId;
            this.pos = pos.immutable();
            this.fluid = fluid;
            this.amount = Math.max(0, Math.min(8, amount));
        }

        FFVirtualFluidUpdatePacket(FriendlyByteBuf buffer) {
            this(
                    buffer.readResourceLocation(),
                    buffer.readBlockPos(),
                    readFluid(buffer),
                    buffer.readVarInt()
            );
        }

        public static FFVirtualFluidUpdatePacket decoder(FriendlyByteBuf buffer) {
            return new FFVirtualFluidUpdatePacket(buffer);
        }

        public void encoder(FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(dimensionId);
            buffer.writeBlockPos(pos);
            writeFluid(buffer, fluid);
            buffer.writeVarInt(amount);
        }

        #if MC > MC_20_1
        public static void messageConsumer(FFVirtualFluidUpdatePacket packet, CustomPayloadEvent.Context ctx) {
            ctx.enqueueWork(() -> packet.applyClient());
            ctx.setPacketHandled(true);
        }
        #else
        public void messageConsumer(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(this::applyClient);
            ctx.get().setPacketHandled(true);
        }
        #endif

        private void applyClient() {
            if (FMLEnvironment.dist != Dist.CLIENT || !isClientLevel(dimensionId)) {
                return;
            }

            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }

            if (amount <= 0 || fluid == Fluids.EMPTY) {
                ExtendedWaterlogStore.remove(level, pos);
            } else {
                ExtendedWaterlogStore.set(level, pos, fluid, amount);
            }
            markVirtualFluidDirty(pos);
        }
    }

    public static class FFVirtualFluidChunkPacket {
        private final ResourceLocation dimensionId;
        private final ChunkPos chunkPos;
        private final List<ExtendedWaterlogStore.StoredFluidEntry> entries;

        FFVirtualFluidChunkPacket(ServerLevel level, ChunkPos chunkPos) {
            this(level.dimension().location(), chunkPos, ExtendedWaterlogStore.getChunkEntries(level, chunkPos));
        }

        FFVirtualFluidChunkPacket(ServerLevel level, ChunkPos chunkPos, List<ExtendedWaterlogStore.StoredFluidEntry> entries) {
            this(level.dimension().location(), chunkPos, entries);
        }

        FFVirtualFluidChunkPacket(ResourceLocation dimensionId, ChunkPos chunkPos,
                                  List<ExtendedWaterlogStore.StoredFluidEntry> entries) {
            this.dimensionId = dimensionId;
            this.chunkPos = chunkPos;
            this.entries = List.copyOf(entries);
        }

        FFVirtualFluidChunkPacket(FriendlyByteBuf buffer) {
            ResourceLocation readDimensionId = buffer.readResourceLocation();
            ChunkPos readChunkPos = new ChunkPos(buffer.readInt(), buffer.readInt());
            int size = buffer.readVarInt();
            ArrayList<ExtendedWaterlogStore.StoredFluidEntry> readEntries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                readEntries.add(new ExtendedWaterlogStore.StoredFluidEntry(
                        buffer.readBlockPos(),
                        readFluid(buffer),
                        buffer.readVarInt()
                ));
            }
            this.dimensionId = readDimensionId;
            this.chunkPos = readChunkPos;
            this.entries = List.copyOf(readEntries);
        }

        public static FFVirtualFluidChunkPacket decoder(FriendlyByteBuf buffer) {
            return new FFVirtualFluidChunkPacket(buffer);
        }

        public void encoder(FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(dimensionId);
            buffer.writeInt(chunkPos.x);
            buffer.writeInt(chunkPos.z);
            buffer.writeVarInt(entries.size());
            for (ExtendedWaterlogStore.StoredFluidEntry entry : entries) {
                buffer.writeBlockPos(entry.pos());
                writeFluid(buffer, entry.fluid());
                buffer.writeVarInt(Math.max(0, Math.min(8, entry.amount())));
            }
        }

        #if MC > MC_20_1
        public static void messageConsumer(FFVirtualFluidChunkPacket packet, CustomPayloadEvent.Context ctx) {
            ctx.enqueueWork(() -> packet.applyClient());
            ctx.setPacketHandled(true);
        }
        #else
        public void messageConsumer(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(this::applyClient);
            ctx.get().setPacketHandled(true);
        }
        #endif

        private void applyClient() {
            if (FMLEnvironment.dist != Dist.CLIENT || !isClientLevel(dimensionId)) {
                return;
            }

            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }

            Set<BlockPos> dirtyPositions = new HashSet<>();
            for (ExtendedWaterlogStore.StoredFluidEntry previous : ExtendedWaterlogStore.getChunkEntries(level, chunkPos)) {
                dirtyPositions.add(previous.pos().immutable());
            }

            ExtendedWaterlogStore.clearChunk(level, chunkPos);

            for (ExtendedWaterlogStore.StoredFluidEntry entry : entries) {
                if (entry.amount() <= 0 || entry.fluid() == Fluids.EMPTY) {
                    continue;
                }
                ExtendedWaterlogStore.set(level, entry.pos(), entry.fluid(), entry.amount());
                dirtyPositions.add(entry.pos().immutable());
            }

            for (BlockPos dirtyPos : dirtyPositions) {
                markVirtualFluidDirty(dirtyPos);
            }
        }
    }
}
