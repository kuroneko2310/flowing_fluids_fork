# Distance snapshot scaling optimization (2026-03-19)

## Cause
- Equalizer request prep was already reducing `maxDepth`, `maxNodes`, and later supplemental exploration using `distanceLoadFactor`.
- But snapshot capture still used the raw config floors from `inletProbeMaxSteps` and `horizontalSupplementDepth`.
- That meant distance-heavy configurations could shed BFS work while still paying for a larger-than-needed cube capture up front.

## Fix
- Snapshot radius is now derived from the same scaled inlet-probe and horizontal-supplement depths that the equalizer actually uses during the request.
- The radius still honors the current BFS depth and the regime-specific caps from `WaterFlowProfile`.
- Broad water bodies keep their small capped snapshots, while channels and impounded flows no longer keep an inflated capture radius after load shedding.

## Why this is safe
- The new radius is never smaller than the actual `maxDepth` used by the BFS request.
- The scaled radius matches the later supplemental sweeps, so follow-up probes do not reach outside the captured snapshot.
- Regime clamps still apply, so large calm surfaces and reservoir-like water keep their existing safety envelopes.

## Repro / future caution
- Repro shape: medium-to-large equalizer runs with `bfsMaxSearchDistance`, `horizontalSupplementDepth`, or `inletProbeMaxSteps` above defaults.
- Future rule: if distance load shedding reduces the work budget for a request, capture radius should shrink with the same effective depths instead of sticking to the raw config floor.
