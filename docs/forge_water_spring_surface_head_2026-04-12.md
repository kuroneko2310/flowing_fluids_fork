## Forge water spring surface head memo

- Cause:
  Upward water spring blocks still capped their pulse column at the local strength band of 1-5 blocks. That worked for cave drips, but it meant a straight shaft above the spring would stop short instead of rising to the current surface in taller or bedrock-shifted worlds.
- Fix:
  For Forge upward water springs only, resolve the target column height from the spring position to the local `WORLD_SURFACE` mouth using bedrock-relative height. The full-height column is used only when every cell up to that mouth can already accept water, so blocked shafts still fall back to the old short pulse behavior. Once that shaft is already full of water, spring emission is also allowed to spread upward so the head can keep raising the water level above the current top cell.
- Recurrence note:
  When changing spring pressure or pulse height again, check whether the behavior is meant to be a short decorative pulse or a real pressure head that should follow the dimension floor and surface together.
