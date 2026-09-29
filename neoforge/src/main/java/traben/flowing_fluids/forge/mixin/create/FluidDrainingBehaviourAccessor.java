package traben.flowing_fluids.forge.mixin.create;

import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(FluidDrainingBehaviour.class)
public interface FluidDrainingBehaviourAccessor {

    @Accessor(value = "fluid", remap = false)
    Fluid ff$getFluid();

    @Accessor(value = "fluid", remap = false)
    void ff$setFluid(Fluid fluid);
}


