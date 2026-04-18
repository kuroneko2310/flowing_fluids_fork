# Water surface equalizer delay fix (2026-03-28)

## What went wrong

- Broad calm water (`LARGE_BODY`) only queued equalizer work when the local amount delta reached `3` or the cell was still inside the short `flowActive` window.
- With `waterTickDelay = 1`, ordinary fluid ticks arrived often enough that the surface still looked like it settled.
- With `waterTickDelay = 3`, that short active window ended before small `1-2` level dents were revisited, so the water surface could stop one step short of leveling.

## Fix

- Let `LARGE_BODY` queue equalizer work again on delta `>= 2`.
- Lower the visited-promotion variance threshold for `LARGE_BODY` from `4` to `2` so nearby calm-surface dents are gathered into the same averaging pass instead of being left behind.

## Safe rule for future work

- Broad-surface throttling should reduce endless churn, not require a large visible step before calm water is allowed to smooth back out.
- When comparing `1tick` and `3tick` behavior, check whether a short-lived "active" flag is the only thing still waking the equalizer.
