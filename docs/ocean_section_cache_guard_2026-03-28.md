# Ocean section cache guard (2026-03-28)

## Cause

- Keeping `FFSectionSampleContext` alive across the whole level tick looked attractive for broad oceans, but in practice
  many water cells only touched the cache once and still paid the cost of carrying larger profile/cache state around.
- The bigger miss was that section snapshots are most useful during read-heavy local analysis. Once a fluid tick starts
  writing nearby states, the cached neighborhood stops being a good fit and the bookkeeping can outweigh the win.

## Fix

- Return `FFSectionSampleContext` to per-fluid-tick lifetime.
- Add a `dirtyTick` guard so section snapshots are only built while the current fluid tick is still read-only.
- When a local write invalidates samples, drop the section cache and per-position water profiles for the rest of that
  fluid tick instead of rebuilding them again.

## Why this should be cheaper

- Quiet water keeps the small per-tick cache it already had.
- Write-heavy shore and surface-adjustment ticks stop spending extra work on section snapshots that are immediately invalid.
- The change stays local to the existing fluid tick flow and does not add new global state or cross-tick retention.
