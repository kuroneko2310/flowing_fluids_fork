## 2026-03-29 original mod backports

- Compared the current Forge branch against the original mod changelog and release-era commits.
- Restored the Twilight Forest portal guard so the portal's fake level-1 water state no longer runs normal water upkeep ticks.
- Restored the Create item drain compatibility mixin so partially filled buckets report a scaled fluid amount when Create empties them.
- Restored sea-level override support so Flowing Fluids can use a custom sea level for infinite-biome refill, surface drain, and nearby broad-surface checks.

### Why these were chosen

- These were present in the original changelog/release commits but were not visible in the current Forge code path.
- Both are narrow compatibility fixes and fit the current Forge-only, minimal-change rule.
- The sea-level override work stays narrow by only touching the fluid-behaviour paths that already key off sea level, instead of broadening it into unrelated worldgen or loader-specific systems.

### Follow-up candidates

- The original mod also had broader auto-performance metrics/history reporting.
- The current branch already has its own runtime auto tick-delay system and broader water-wheel logic, so those should be compared carefully before backporting anything else.
