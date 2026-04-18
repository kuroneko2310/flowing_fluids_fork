## Forge spring capped pressure head memo

- Cause:
  Upward Forge springs only kept their full-height pressure when the shaft stayed completely open to the surface. If a cap block sat above the shaft, the special reach logic dropped out and the spring fell back to the short decorative pulse band instead of filling right up to the stopper.
- Fix:
  Upward water and lava springs now resolve a straight pressure reach in two stages. Open sky water shafts still climb to the local surface, and capped shafts now keep a full column up to the last fluid-accepting block directly below the stopper. The pulse interval was also tightened so the column refills and spills sideways more assertively once that head is established.
- Recurrence note:
  When touching spring pressure again, treat "open to surface" and "capped by a solid block" as the same family of vertical head behavior. If a shaft is intentionally continuous, prefer keeping the column anchored to the highest reachable cell instead of dropping back to the short ambient pulse range.
