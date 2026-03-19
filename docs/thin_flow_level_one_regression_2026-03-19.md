# Thin flow level-one regression (2026-03-19)

## Cause
- `MixinFlowingFluid#flowing_fluids$shouldSuppressShallowFlatTransfer(...)` was suppressing very shallow transfers even when a dry neighboring tile should have received the first thin flow level.
- That made `2 -> 0` and `3 -> 0` shallow edge cases stay as thicker source blobs instead of splitting into a visible `1`-level tail.
- The result was a world that visually favored level 2 water over level 1 trickles.

## Fix
- Added a small regression helper to explicitly preserve shallow dry-edge flow creation.
- The shallow-flat suppression now steps aside for `source <= 3` into an empty neighboring tile, so the discrete balance logic can create the expected thin tail.
- Connected placement now also allows tiny leftovers (`1..3`) to fan out across dry candidates instead of forcing them into a single level-2/3 blob.
- Narrowed the remaining `3 -> 0` regression by giving shallow dry-edge balancing a slight negative destination bias and by skipping the later positive pressure bias for that exact case.

## Why this is safe
- The exception is intentionally narrow: shallow water only, dry destination only.
- Broad flat jitter suppression still applies to already-wet shallow surfaces, which is where most of the visual noise came from.
- The existing discrete balance logic still decides the actual split, so this change only restores access to level 1 results instead of forcing them everywhere.
- Larger connected fills still keep the old coherence preference, so rain/refill and bulk transfers do not explode into noisy one-level dust.
- Drop-off-retained lateral flows still use the old balancing path, so downhill support and source preservation do not get weakened by the thin-tail tweak.

## Repro / future caution
- Repro shape: shallow water sheet on a flat supported surface that needs to spread into a dry neighboring tile.
- Future rule: suppression intended to calm jitter should not block the first creation of a thin visible tail on dry ground.
- Future rule: if a shallow dry-edge case is intentionally preserved, later profile/pressure bias must not immediately thicken that same edge back into level 2.
