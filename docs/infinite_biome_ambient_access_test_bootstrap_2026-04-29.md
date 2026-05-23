# Infinite Biome Ambient Access and Test Bootstrap Fix - 2026-04-29

## Cause

`classifyInfiniteBiomeAmbientAccess` treated laterally connected broad-water cells at exactly sea level as lacking ambient access unless they had water above or full water below. This made shore and beach style broad water fail the intended sea-level recovery path.

Several regression tests also touched `FlowingFluids.config` before bootstrapping Minecraft registries. Depending on test order, `FlowingFluids` could initialize through `Registries` before `Bootstrap.bootStrap()`, poisoning later tests with `NoClassDefFoundError`.

## Fix

- Allow broad-water biome cells at `y <= seaLevel` to use the lateral-neighbor ambient access path.
- Bootstrap Minecraft in config-touching regression tests before they access `FlowingFluids`.

## Avoid Next Time

Tests that touch `FlowingFluids`, `Registries`, `Blocks`, `Fluids`, or biome tags should call `SharedConstants.tryDetectVersion()` and `Bootstrap.bootStrap()` before the first static access. Sea-level infinite-biome behavior should be tested at both `seaLevel - 1` and `seaLevel`.
