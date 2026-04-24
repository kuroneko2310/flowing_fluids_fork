# Forge Sodium hidden flow texture render cost

## Cause

Forge's Sodium/Embeddium fluid renderer mixin used `@ModifyExpressionValue` on
`FluidState#getFlow(...)` for `hideFlowingTexture`.

That made the rendered flow vector become `Vec3.ZERO`, but only after Sodium had
already calculated the fluid flow vector. Looking at many moving water surfaces
therefore still paid the neighbor fluid-state sampling cost during chunk fluid
mesh rebuilds.

## Fix

Change the mixin to `@Redirect` the `getFlow(...)` call.

When `hideFlowingTexture` is enabled, return `Vec3.ZERO` before the flow vector
is calculated. When it is disabled, call the vanilla `fluidState.getFlow(...)`
path unchanged.

## Future note

For render-only visual suppression, prefer skipping the expensive calculation at
the call site instead of calculating first and replacing the result afterward.

## Follow-up

Virtual fluid chunk sync also dirtied each updated cell and its six neighbors one
entry at a time. Adjacent virtual fluid cells caused repeated client render dirty
calls for the same positions.

Chunk packet application now gathers all affected positions into one set before
calling `setBlockDirty`. Single-cell updates still use the direct path, so normal
small updates do not allocate the set.
