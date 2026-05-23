# Sea-level overflow evaporation (2026-05-22)

## Cause

Infinite-biome water is intentionally protected from normal drying so ocean, beach, and similar broad-water surfaces do not disappear. That protection also covered water stacked above sea level, while normal surface evaporation only targets thin water. As a result, surplus water above sea level could remain active and keep invoking heavier flow/search paths instead of disappearing.

## Fix

Add a sea-level overflow evaporation path to the existing random-tick drying system. The path targets broad-water biome water above the configured sea level within the configured excess band, respects daytime/sky/shade/rain checks, only drains exposed top cells, and refuses to delete water that can immediately fall downward.

Phase 2 also allows the first block above sea level, but with a small fixed edge multiplier so sea-level ripples dry much more slowly than obvious stacked overflow. Higher overflow uses the normal configured chance plus the height scale.

Phase 3 adds a guarded fluid-tick shortcut before the heavier horizontal flow path. If a broad-water overflow cell is already an exposed, non-falling, non-spreading evaporation candidate, the tick either evaporates it locally or schedules a slow local retry instead of running broad flow/search work again.

Phase 4 keeps the random-tick path aligned with the fluid-tick shortcut by refusing to evaporate overflow water that can still flow sideways. This prevents moving shore fronts from being deleted by random ticks while scheduled ticks would have let the same water spread normally.

Phase 5 makes the cleanup aggressive for configured infinite biomes. With `seaLevelOverflowEvaporationInstant` enabled, exposed overflow above sea level is drained immediately in random and scheduled ticks, including river, swamp, and configured infinite biome matches. This instant path ignores chance rolls, daytime-only, downward-fall, horizontal-flow, and retry scheduling, but keeps sky/ambient access, shade protection, rain protection, and top-cell-only checks so enclosed artificial water is not treated as world overflow.

## Recheck notes

Keep this path local and random-tick or slow-retry based. Do not reintroduce broad scans for overflow cleanup. If future tuning is needed for the legacy path, adjust the overflow chance, height scale, or excess band rather than adding a persistent scanner or cache. Both random-tick and fluid-tick legacy evaporation paths should preserve the horizontal escape guard so infinite-biome refill does not feed a supply -> moving-front deletion -> refill loop near sea level. The instant path intentionally removes that guard.
