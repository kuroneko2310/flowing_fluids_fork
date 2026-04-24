# Rain collector full event absorb

## Symptoms

- Rain collector absorption intercepts rain before it becomes world water.
- If a collector had some tank space but not enough for the whole rain event, it could accept only part of the event and still report the rain as fully absorbed.
- The remaining rain amount would not be placed into the world.

## Cause

- `RainCollectorRuntime.tryAbsorbRainWater` only checked whether the collector had any space.
- `RainCollectorBlockEntity.addCollectedWater` can return a partial accepted amount when the tank is nearly full.
- The runtime API is boolean, so there is no way to pass the leftover rain amount back to the common rain queue.

## Fix

- Rain collector runtime now only intercepts a rain event when the collector can store the full event amount.
- Direct collector ticking still uses the normal tank fill path, so ordinary collection can top off naturally.

## Avoid next time

- A boolean interception hook should only consume an event when it can consume the whole event.
- If partial interception is desired later, change the hook to return the accepted amount instead of hiding leftovers.
