# Marine plant flow-check optimization (2026-03-19)

## Cause
- `MixinSeaGrassBlock`, `MixinTallSeaGrassBlock`, and `MixinKelpPlantBlock` all call `FFFluidUtils.canFluidFlowToNeighbourFromPos(...)` from `canSurvive`.
- The old helper re-read the source blockstate once per horizontal direction before it even looked at the neighbor.
- In kelp forests or dense seagrass beds, lots of fluid neighbor updates turn that repeated "can this plant still hold water?" question into a quiet hotspot.

## Fix
- Added an overload of `canFluidFlowToNeighbourFromPos(...)` that accepts the caller's already-known source `BlockState`.
- Switched marine plant survival checks to pass their current state directly.
- Reused the same overload from water evaporation checks, because they ask the same horizontal escape question.

## Why this is safe
- The actual pass/fail decision still routes through `canFluidFlowFromPosToDirection(...)`.
- Virtual waterlog handling, pass-through rules, and normal target fit checks are unchanged.

## Repro / future caution
- Repro shape: large ocean or shoreline with lots of kelp or seagrass while nearby water keeps settling.
- Future rule: if the caller already has the local `BlockState`, use the overload that accepts it instead of re-fetching the origin for every horizontal direction.
