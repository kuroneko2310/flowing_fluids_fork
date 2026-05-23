# Equalization internal scale and defer loop fix - 2026-05-23

## Cause

- `EnhancedFluidBFS.equalizePositionsInternal` was summing `FluidSectionDataCache.amount(...)`, which is the block-state 0-8 scale.
- The same path distributes and writes through `FluidTickBuffer.bufferFluidChange(...)`, which expects the internal 0-63 scale.
- Moving water near outlets could also cycle through queue, surge-skip defer, and immediate requeue on every tick.

## Fix

- Read equalizer inputs with `FluidSectionDataCache.internalAmount(...)` so collection, distribution, buffered writes, and pool-stability conversion stay on the same internal scale.
- Extracted the internal equalizer distribution into `EnhancedFluidBFS.equalizeAmounts(...)` helpers for mass-conservation regression tests.
- Downward outlet equalizer wakeup no longer fires solely because an outlet exists; empty transitions still wake immediately, and outlet-only movement requires active flow, momentum, or a real level delta.
- Deferred moving surge candidates now enter a short cooldown instead of being requeued immediately.
- Removed the unused legacy `performEqualization` / `performParallelEqualization` path and its private helper state.

## Avoid next time

- Do not mix 0-8 block-state amounts with 0-63 internal amounts inside equalizer, scheduler, or buffer paths.
- For performance fixes around water movement, remove repeated work before adding broader tracking or scanners.
