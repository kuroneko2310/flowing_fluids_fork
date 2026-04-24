# Rain water partial placement

## Symptoms

- Queued rain placement could target an air or replaceable block with a small rain amount.
- The API bridge first placed vanilla water at the target, then added the requested rain amount through the Flowing Fluids API.
- That could turn a small rain placement into a full source cell before the requested amount was applied.

## Cause

- `Fluids.WATER.defaultFluidState().createLegacyBlock()` represents a full water source.
- Rain placement already carries a finite level amount, so using a full source as a bootstrap step mints extra water before the real amount logic runs.

## Fix

- Dry rain targets now call `FFFluidUtils.setFluidStateAtPosToNewAmount` with the requested first-cell amount directly.
- Existing water still uses the connected placement path, so larger rain bursts can spread through the normal Flowing Fluids logic.
- The bridge now checks effective fluid state first, so virtual or partial water is not mistaken for a dry cell.

## Avoid next time

- Do not use vanilla source placement as a setup step for finite rain amounts.
- For rain-born water, carry the requested level amount into the core fluid mutation path directly.
