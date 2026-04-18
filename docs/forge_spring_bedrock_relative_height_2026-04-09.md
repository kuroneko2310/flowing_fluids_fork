## Forge spring height compatibility memo

- Cause:
  Several Forge spring worldgen entries and deep lava strength bands still used vanilla absolute Y thresholds such as `56`, `-20`, and `29`. In modded dimensions with different min build heights, those thresholds drifted away from the intended layer relative to bedrock.
- Fix:
  Converted the Forge spring placement bands and deep lava "hotter/deeper" checks to resolve old vanilla Y targets as offsets from the current dimension floor. This keeps vanilla-like placement in standard worlds while following custom bedrock depths.
- Recurrence note:
  When adding new Forge spring features, avoid raw absolute Y anchors unless the behavior is intentionally tied to a fixed world height rather than the local bedrock floor.
