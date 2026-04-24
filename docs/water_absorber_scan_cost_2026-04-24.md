# Water absorber scan cost guard

## Cause

`WaterAbsorberBlockEntity` scanned the whole configured cube when it could not find water. At the 120 block radius tier, that could mean more than 13 million positions in one operation, and the 5 tick machine cadence made the stall easier to hit.

## Fix

The scan now keeps the saved cursor but checks only a bounded number of in-radius positions per machine tick. It also treats the configured range as an actual radius instead of draining from cube corners outside the advertised range.

## Avoid next time

Large radius machines should advance through their search space incrementally. Do not let an empty result path scan the full volume in one tick.
