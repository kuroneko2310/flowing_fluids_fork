## Create hose pulley + Pipez compatibility memo

- Date: 2026-04-18
- Scope: Forge / Create hose pulley / Pipez and other high-throughput fluid pipes

### Symptom

Pipez fluid pipes probe large transfers with `drain(..., SIMULATE)` before moving fluid.
Create's hose pulley handler is mostly shaped around one-bucket world interactions, so large
external pipe requests can under-transfer or misread available fluid.

### Cause

- The hose pulley world interaction path is centered on 1000 mB steps.
- Repeating `SIMULATE` bucket-by-bucket can count the same connected water body more than once
  and accidentally present finite water as if it were infinite.
- The normal hose pulley placement override was also hard-setting a full level-8 root cell first.
  That made water placement brittle in tight spots and could raise the tip cell high enough to
  damage the hose pulley setup itself.
- The bulk fill compatibility path also treated the existing internal buffer and the new incoming
  transfer too separately. When partial water remained inside the hose pulley, later incoming water
  could fail to continue placement or buffering naturally from that leftover state.

### Fix direction

- Add a narrow bulk-transfer compatibility path in the hose pulley handler for large external
  pipe `fill` and `drain` requests.
- For draining, use `collectConnectedFluidAmountAndRemoveAction(...)` to measure the connected
  finite amount first, and only consume that amount during `EXECUTE`.
- For filling, use `placeConnectedFluidAmountAndPlaceAction(...)` to place only the amount the
  world can actually absorb, then keep the remainder in the internal buffer.
- Evaluate hose pulley filling from the combined amount of `internalTank + incoming resource`, then
  return only the truly accepted portion of the new transfer. This lets partial leftover water keep
  flowing forward instead of stalling the next insert.
- External pipe filling now uses a second, wider placement search when the nearby downward spread
  still has room left to place, so larger injections can reach a broader connected area without
  pretending the source is infinite.
- Normal hose pulley water placement now caps the root cell at level 7 and pushes the remainder
  downward or sideways first, so the pulley does not try to sit on top of its own full source.
- Keep the existing Create one-bucket compatibility path intact.

### Avoid next time

- Do not loop `pullNext(..., true)` or `tryDeposit(..., true)` for bulk compatibility.
- Keep `SIMULATE` and `EXECUTE` anchored to the same world-authoritative connected-amount or
  placement action so finite water stays finite.
