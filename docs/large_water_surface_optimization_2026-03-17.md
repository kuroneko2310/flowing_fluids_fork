# Large Water Surface Optimization - 2026-03-17

## Problem
- Large oceans, lakes, and flat canals were still expensive even after lowering normal flow distance.
- The main cost was not just the number of fluid blocks, but the amount of helper work attached to each small update:
  broad-surface checks, deep spread search, equalizer queueing, and repeated pressure sampling.

## What Changed
- Calm broad-surface interior water now reschedules itself with a longer delay instead of re-running the full side-flow logic every time.
- Small internal level changes on calm broad surfaces no longer always enqueue the parallel equalizer.
- Parallel equalizer requests that start from calm broad-surface interiors now run with a much smaller depth and node budget.
- Default and local config values were shifted toward cheaper large-water behavior:
  slower water tick cadence, shorter max adaptive distance, smaller horizontal supplement, weaker diffusion, and lighter rain/refill pressure.
- Water logic is now classified into separate profiles before heavy work begins:
  `TRICKLE`, `LOCAL`, `CHANNEL`, `LARGE_BODY`, `IMPOUNDED`, and `BREACH`.
- The tick path, deep spread search, and equalizer now all share that same profile so large quiet water,
  narrow directional flow, and dam-like pressure fronts are no longer treated as the same workload.
- Far and distant calm large-water interiors can now use queued macro re-ticks instead of immediately
  re-entering the full fluid tick path.
- Pressure and hydraulic bonuses are reduced inside quiet large water, but boosted for impounded/breach fronts
  so dam-release behavior stays lively while the basin behind it stays cheap.
- Underground springs, enclosed cave pools, and flooded cave chambers are now classified separately from surface water.
- Subterranean enclosed water with no skylight, no clear outlet, and stable support now uses longer local sleep delays,
  shorter slope search, and a much smaller equalizer budget.

## Why This Is Safe
- River zones are still excluded from broad-surface suppression, so flowing channels keep their directional feel.
- Surface edges and downward outlets still bypass the calm-surface shortcut, so shorelines and drains keep reacting quickly.
- Stronger changes such as empty/full transitions or large level jumps still enqueue equalization.

## Follow-up Guidance
- If large oceans are still heavy, reduce `maxWaterFlowDistance` before lowering normal `waterFlowDistance`.
- If rainfall keeps waking up too much water, tune rain generation and infinite-biome refill before weakening river behavior.
- When changing broad-surface logic again, always check three cases separately:
  ocean/beach interiors, river channels, and man-made drains.
- Also test underground cave reservoirs separately from surface lakes; they now intentionally favor stability and low CPU cost over constant micro-adjustment.
- If breach fronts look too aggressive, tune the profile multipliers first before raising global `waterTickDelay`.
- If far-away reservoirs still cost too much, prefer extending macro delay buckets rather than shrinking near-player flow quality.
