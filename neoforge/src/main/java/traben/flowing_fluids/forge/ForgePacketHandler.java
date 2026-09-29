package traben.flowing_fluids.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;




import net.neoforged.neoforge.network.PacketDistributor;



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

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(String.valueOf(PROTOCOL_VERSION));
        registrar.playToClient(FFConfigPacket.TYPE, FFConfigPacket.CODEC, FFConfigPacket::messageConsumer);
        registrar.playToClient(FFVirtualFluidUpdatePacket.TYPE, FFVirtualFluidUpdatePacket.CODEC, FFVirtualFluidUpdatePacket::messageConsumer);
        registrar.playToClient(FFVirtualFluidChunkPacket.TYPE, FFVirtualFluidChunkPacket.CODEC, FFVirtualFluidChunkPacket::messageConsumer);
    }

    public static void sendVirtualFluidState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk.getPos(), new FFVirtualFluidUpdatePacket(level, pos));
        
    }

    public static void sendVirtualFluidChunk(ServerPlayer player, ServerLevel level, ChunkPos chunkPos) {
        
        PacketDistributor.sendToPlayer(player, new FFVirtualFluidChunkPacket(level, chunkPos));
        
    }

    public static void clearVirtualFluidChunk(ServerPlayer player, ServerLevel level, ChunkPos chunkPos) {
        
        PacketDistributor.sendToPlayer(player, new FFVirtualFluidChunkPacket(level, chunkPos, List.of()));
        
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

    private static void addVirtualFluidDirtyPositions(Set<BlockPos> dirtyPositions, BlockPos pos) {
        dirtyPositions.add(pos.immutable());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            dirtyPositions.add(cursor.immutable());
        }
    }

    private static void markBlockDirty(ClientLevel level, BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        minecraft.levelRenderer.setBlockDirty(pos, state, state);
    }

    private static void markVirtualFluidDirty(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        markBlockDirty(level, pos);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            markBlockDirty(level, cursor);
        }
    }

    public static class FFConfigPacket extends FFConfig implements CustomPacketPayload {
        public static final Type<FFConfigPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FlowingFluids.MOD_ID, "config"));
        public static final StreamCodec<FriendlyByteBuf, FFConfigPacket> CODEC = StreamCodec.of((buf, packet) -> packet.encoder(buf), FFConfigPacket::decoder);
        @Override public Type<FFConfigPacket> type() { return TYPE; }

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

        
        public static void messageConsumer(FFConfigPacket packet, IPayloadContext ctx) {
            if (packet.isValid) {
                FlowingFluids.config = packet;
                FlowingFluids.applyConfigRuntime();
                FlowingFluids.info("- Server Config data received and synced");
            } else {
                FlowingFluids.error("- Server Config data received and failed to sync");
                throw new RuntimeException("[Flowing Fluids] - Server Config data received and failed to sync");
            }

        }
        

        public void encoder(FriendlyByteBuf buffer) {
            FlowingFluids.config.encodeToByteBuffer(buffer);
        }
    }

    public static class FFVirtualFluidUpdatePacket implements CustomPacketPayload {
        public static final Type<FFVirtualFluidUpdatePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FlowingFluids.MOD_ID, "virtual_fluid"));
        public static final StreamCodec<FriendlyByteBuf, FFVirtualFluidUpdatePacket> CODEC = StreamCodec.of((buf, packet) -> packet.encoder(buf), FFVirtualFluidUpdatePacket::decoder);
        @Override public Type<FFVirtualFluidUpdatePacket> type() { return TYPE; }

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

        
        public static void messageConsumer(FFVirtualFluidUpdatePacket packet, IPayloadContext ctx) {
            ctx.enqueueWork(() -> packet.applyClient());

        }
        

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

    public static class FFVirtualFluidChunkPacket implements CustomPacketPayload {
        public static final Type<FFVirtualFluidChunkPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FlowingFluids.MOD_ID, "virtual_chunk"));
        public static final StreamCodec<FriendlyByteBuf, FFVirtualFluidChunkPacket> CODEC = StreamCodec.of((buf, packet) -> packet.encoder(buf), FFVirtualFluidChunkPacket::decoder);
        @Override public Type<FFVirtualFluidChunkPacket> type() { return TYPE; }

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

        
        public static void messageConsumer(FFVirtualFluidChunkPacket packet, IPayloadContext ctx) {
            ctx.enqueueWork(() -> packet.applyClient());

        }
        

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
                addVirtualFluidDirtyPositions(dirtyPositions, previous.pos());
            }

            ExtendedWaterlogStore.clearChunk(level, chunkPos);

            for (ExtendedWaterlogStore.StoredFluidEntry entry : entries) {
                if (entry.amount() <= 0 || entry.fluid() == Fluids.EMPTY) {
                    continue;
                }
                ExtendedWaterlogStore.set(level, entry.pos(), entry.fluid(), entry.amount());
                addVirtualFluidDirtyPositions(dirtyPositions, entry.pos());
            }

            for (BlockPos dirtyPos : dirtyPositions) {
                markBlockDirty(level, dirtyPos);
            }
        }
    }
}
