package traben.flowing_fluids.forge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.water.FluidFlowActivityTracker;

@Mixin(Level.class)
public abstract class MixinLevel {


    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
                     ,ordinal = 1 
            )
    
        , locals = LocalCapture.CAPTURE_FAILHARD)
    private void flowing_fluids$displaceFluids(final BlockPos pos, final BlockState state, final int flags, final int recursionLeft,
                                               final CallbackInfoReturnable<Boolean> cir, final LevelChunk levelchunk,
                                               final Block block, final BlockSnapshot blockSnapshot,
                                               final BlockState old, final int oldLight,
                                               final int oldOpacity, final BlockState blockstate) {
    
        // Real fluid motion for flow watchers (Create water wheels); a no-op while nothing is watched.
        FluidFlowActivityTracker.onBlockChanged((Level) (Object) this, pos, old, state);
        // Skip server-side placement/generation writes that intentionally avoid neighbor updates.
        if ((flags & Block.UPDATE_NEIGHBORS) == 0) {
            return;
        }
        Level level = (Level) (Object) this;
        FFFluidUtils.displaceFluids(level, pos, state, flags, levelchunk, old);
        FFFluidUtils.wakeAdjacentVirtualFluidCells(level, pos);
    }

}
