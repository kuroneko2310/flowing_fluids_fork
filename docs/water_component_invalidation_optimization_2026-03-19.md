# Water component invalidation optimization (2026-03-19)

## Cause
- `FFFluidUtils.setFluidStateAtPosToNewAmount(...)` updated the spatial grid and then invalidated connected-fluid components for almost every write.
- Most ordinary water ticks only change the local amount at an already-wet position, so the connected region itself does not change.
- That meant broad surfaces and canals were repeatedly throwing away component membership just to rebuild it again during later BFS and equalizer passes.

## Fix
- Component invalidation now only happens when the local transition can actually change connectivity:
  - dry -> wet
  - wet -> dry
  - fluid type swap
- Pure amount changes inside the same non-empty fluid tile keep the existing component cache.
- Removal paths were updated to use the same rule so topology changes still invalidate correctly.

## Why this is safe
- Connected components are only used to describe reachability, not exact height distribution.
- Raising or lowering the amount of a tile without removing the fluid does not split or merge the region.
- Topology-changing transitions still invalidate immediately, so BFS can rebuild the region when a channel opens, closes, or dries out.

## Repro / future caution
- Repro shape: broad ocean surfaces, flat canals, or reservoirs where many tiles keep trading 1-2 levels without drying.
- Future rule: if a cache tracks connectivity rather than amount, do not invalidate it for every level tweak; invalidate only when presence or fluid identity changes.
