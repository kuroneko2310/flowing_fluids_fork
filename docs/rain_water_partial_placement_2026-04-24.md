# Finite fluid placement from dry starts

## Symptoms

- Queued rain placement could target an air or replaceable block with a small rain amount.
- The API bridge first placed vanilla water at the target, then added the requested rain amount through the Flowing Fluids API.
- That could turn a small rain placement into a full source cell before the requested amount was applied.
- The underlying API placement helper also did not place anything when the start cell was dry, even though the API contract says placement starts from that position.

## Cause

- `Fluids.WATER.defaultFluidState().createLegacyBlock()` represents a full water source.
- Rain placement already carries a finite level amount, so using a full source as a bootstrap step minted extra water before the real amount logic ran.
- `addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull` only delegated to connected placement, and connected placement requires the origin to already hold the same fluid.

## Fix

- `addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull` now seeds a dry, compatible start cell with the requested finite amount first.
- Vanilla waterloggable cells still require a full 8-level placement, matching the existing waterlogging rule.
- Any leftover amount then continues through connected placement, preserving the normal spread behavior.
- Rain placement now delegates directly to the API again, so finite placement has one authority.

## Avoid next time

- Do not use vanilla source placement as a setup step for finite rain amounts.
- If an API says placement starts from a position, dry-start behavior belongs in the API/helper, not in every caller.
