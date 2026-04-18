# Drain Grate Downflow Note

- Symptom: Water sitting above iron bars, fences, or wall-like pass-through blocks did not reliably drain downward as a grate/inlet.
- Cause: The downward target resolver already found the block below an empty pass-through conduit, but the final flow gate still re-checked `source -> final target` as if they were directly adjacent. That made the pass-through path fail before the actual downward transfer ran.
- Fix: Reuse the resolved pass-through path and validate it in two adjacent steps instead.
  - `source -> immediate pass-through cell`
  - `conduit -> final downward target`
- Scope: Applied to both the modern downward flow path and the legacy downward flow path so grates behave the same in both modes.
- Follow-up: If rain runoff still feels weak after this, the next place to tune is rain landing selection rather than the grate pass-through itself.
