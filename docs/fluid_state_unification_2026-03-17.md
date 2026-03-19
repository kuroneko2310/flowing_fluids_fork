# Fluid State Unification - 2026-03-17

## Summary
- Root issue: fluid state creation had split rules across lateral flow, downward flow, waterlog handling, random-tick leveling, biome refill/drain, and block displacement.
- Main fix: route those paths through shared transition helpers in `FFFluidUtils` so partial storage, full-only waterlogging, and connected add/remove behavior use the same policy.

## Implemented
- `setFluidStateAtPosToNewAmount(...)`
  - Normalizes requested levels before writing.
  - Virtual waterlog blocks keep partial levels.
  - Vanilla waterloggable blocks stay full-or-empty only.
  - Normal block replacement still writes fluid blocks directly.
- `transferFluidAmount(...)`
  - New shared transfer path for waterlog-special handling and local random-tick settling.
  - Prevents direct `source=0 / dest=8` writes unless the destination really supports that transition.
- `applyConnectedFluidAmountDelta(...)`
  - New shared helper for biome refill, drought drain, evaporation, and similar random-tick edits.
  - Keeps those systems closer to connected fluid behavior instead of arbitrary single-tile rewrites.
- `applyLocalFluidAmountDelta(...)`
  - Added after review to keep environmental drain and evaporation local to the touched tile.
  - Important nuance: refill wants connected spread, but evaporation/drain should not secretly pull from neighboring tiles.
- `displaceFluids(...)`
  - Fixed direct air placement to use the remaining amount, not the original pre-spread amount.

## Risk Notes
- Vanilla `LiquidBlockContainer` behavior is still fundamentally full-or-empty, so partial pass-through should prefer virtual waterlog blocks.
- Any future direct call pair like `setFluidStateAtPosToNewAmount(from, 0)` and `setFluidStateAtPosToNewAmount(to, 8)` should be treated as suspicious and reviewed against `transferFluidAmount(...)`.
- Random-tick settling should stay local unless the full path is stable broad-surface water; otherwise it starts to look like remote teleport motion again.
- Reads that influence movement should prefer `getEffectiveFluidState(...)` over raw `level.getFluidState(...)` when virtual waterlog or pass-through blocks may be involved.
