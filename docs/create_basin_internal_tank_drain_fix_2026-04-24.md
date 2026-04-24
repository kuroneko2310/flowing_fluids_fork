# Create Basin internal tank drain fix

## Symptoms

- Forge-only Create Basin external-fluid compat could match recipes using world water or lava near the Basin.
- During that compat path, internal Basin tank fluid was reduced by mutating the `FluidStack` returned from `IFluidHandler#getFluidInTank`.
- Some handlers may return a copied stack, so shrinking it can fail to update the actual tank.

## Cause

- The compat path mixed two fluid sources: Basin internal tanks and Flowing Fluids external world fluid.
- External fluid was written back through `CreateBasinExternalFluidCompat`, but internal tank fluid did not go back through `IFluidHandler#drain`.
- Simulated internal-tank extraction also only recorded the tank amount after a fluid ingredient was fully satisfied, so a recipe that needed fluid from multiple tanks could re-count part of the same tank during simulation.

## Fix

- Internal tank consumption now uses `IFluidHandler#drain(..., EXECUTE)`.
- Simulated tank extraction records every partial drain before continuing to the next tank.
- The fix stays inside the Forge Create Basin compat mixin and does not change common fluid state construction.

## Avoid next time

- Treat `getFluidInTank` as a view for checking, not as the authority for mutation.
- When simulating multi-source drains, record partial consumption immediately so later checks cannot reuse the same amount.
