# Mekanism tank bucket mutation order

## Symptoms

- Mekanism tank bucket compat can drain connected Flowing Fluids water into an item tank, or place a partial tank amount back into the world.
- The compat path first changed the world and then executed the item tank `fill` or `drain`.
- If an item handler accepted the simulation but rejected or partially handled the execute step, water could be lost or duplicated.

## Cause

- World fluid state and item fluid storage are two separate authorities.
- Simulation checks reduce most risk, but the execute call is still the only authoritative item-side mutation.
- Mutating the world before that execute call makes failure recovery awkward because the world action is a runnable without a rollback result.

## Fix

- Pickup paths now execute item `fill` first and only remove world fluid after the item accepted the full amount.
- Placement paths now execute item `drain` first and only place world fluid after the item provided the full amount.
- The fix stays inside the Forge Mekanism tank bucket compat path.

## Avoid next time

- When a compat action touches both an item handler and the world, run the reversible or checkable handler mutation before the one-way world runnable.
- Keep simulation as a precheck, but still guard the execute result before changing the second authority.
