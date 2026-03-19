# Hydraulic Batching Regression Memo (2026-03-17)

## Summary

The experimental "1,2" optimization pass caused incorrect flow behavior and was reverted.
The pressure-driven hydraulic model itself was kept, but the batching/cadence additions were removed.

## Most Likely Cause

The highest-risk regression point was changing lateral equalization in `MixinFlowingFluid.ff$flowToSides(...)`
from direct level assignment:

- `FFFluidUtils.setFluidStateAtPosToNewAmount(...)`

to flow/buffer-oriented updates:

- `flowing_fluids$setOrRemoveWaterAmountAt(...)`
- `spreadTo2(...)`

This was likely wrong because lateral equalization is a precise "set both cells to exact amounts" operation,
while the flow helpers are designed for directional spread and carry extra side effects. That made update order
and internal flow behavior bleed into an operation that should stay symmetric and exact.

## Secondary Cause

The lane/segment hydraulic cache was probably too coarse.

Cached values for:

- upstream support
- intake boost

were shared across nearby cells in the same lane segment. In straight channels this may be fine, but in real
terrain the following can diverge inside the same segment:

- branch splits
- local wall changes
- intake depth differences
- short vertical changes

That can make pressure leak sideways or apply to cells that only look similar geometrically.

## Additional Low-Delay Risk

With `waterTickDelay` around `2-3`, cadence synchronization/fractional-delay style behavior is more visible.
When nearby cells align too strongly, the flow can look like it pulses:

- moving together
- pausing together
- resuming together

At low delay values, even a small phase mistake becomes obvious.

## Confidence Ranking

1. Most likely: lateral equalization was changed into flow-style buffered updates.
2. Next: hydraulic lane/segment cache was too coarse for local terrain differences.
3. Contributing factor: cadence synchronization was too visible at low delay values.

## Safer Direction For Future Optimization

If batching/perf work is retried, prefer this order:

1. Keep equalization exact and per-cell. Do not replace it with spread-style helpers.
2. Limit caching to read-only scoring inputs before final direction/transfer is chosen.
3. Restrict batching to notifications, invalidations, and distant dormancy scheduling.
4. Be extra conservative when `waterTickDelay <= 3`.

## Files Involved

- `common/src/main/java/traben/flowing_fluids/mixin/MixinFlowingFluid.java`
- `common/src/main/java/traben/flowing_fluids/AdaptiveTickScheduler.java`
