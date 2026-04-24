# Fluid hot path allocation reduction

## Symptoms

- Legacy water side-flow selection rebuilt a stream pipeline, comparator, filtered list, and a boolean holder on each check.
- The same legacy path kept its state and flow-down scratch maps populated until the next search.
- Modern side-flow selection still allocated two small arrays on each search even though the candidate count is always the four horizontal directions.
- Per-tick water amount and connected-head caches were cleared at tick start, but could retain entries until the next fluid tick if the tick exited through an exception.

## Cause

- The old legacy path sorted by amount through `Arrays.stream(...).sorted(...).toList()`.
- The modern path already used primitive arrays, but created them locally every call.
- The tick caches are thread-local scratch state, so keeping entries after a failed tick only increases retained memory and risks stale diagnostic reads before the next clear.

## Fix

- Reuse thread-local four-slot direction and amount buffers for side-flow candidate sorting.
- Replace the legacy stream/list path with a small stable insertion sort over the same buffers.
- Clear water amount and connected-head caches in the tick `finally` block after refill cleanup.
- Clear legacy state and flow-down scratch maps when their search returns.

## Avoid next time

- For four-direction hot paths, prefer a tiny loop over streams or per-call collections.
- Thread-local caches should have a clear lifetime: begin with empty state, and release scratch entries when the tick ends.
