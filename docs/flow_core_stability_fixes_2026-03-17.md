# Flow core stability fixes (2026-03-17)

## 今回おかしかった動き
- `ParallelFluidTickManager` が raw `level.getFluidState()` を見ていて、virtual waterlog や pass-through 上の水を tick 候補から落としていた。
- `ParallelFluidEqualizer` の代表点スキャンも raw fluid state 基準だったので、見えている水と equalizer の対象がずれることがあった。
- 接続成分 ID はキャッシュされるのに、通常の水量変更時に invalidation されず、古い接続判定が残る経路があった。
- `SUBTERRANEAN_POOL` が `MID` 距離から macro scheduling へ入れたため、遠くない場所でも寝かせすぎる可能性があった。
- `forced recheck` が立っている水でも、macro scheduling / still reservoir 早期 return が先に走って再確認を遅らせうる状態だった。

## 修正
- parallel tick と equalizer の入口を `FFFluidUtils.getEffectiveFluidState(...)` に統一。
- `FFFluidUtils.notifyCaches(...)` で周辺の connected component を invalidation するように変更。
- `WaterFlowProfile.shouldUseMacroScheduling(...)` の `SUBTERRANEAN_POOL` は `FAR / DISTANT` のみに制限。
- `MixinFlowingFluid.tick` では `AdaptiveTickScheduler.hasForcedRecheck(...)` 中の macro dormancy / still reservoir 早期 return を止めた。

## 期待する改善
- 見えているのに tick しない水、equalizer に拾われない水が減る。
- 一度つながっていた扱いが残り続けることで起きる、平衡化の空振りや macro 判定の誤爆が減る。
- 中距離の地下水が不自然に寝続ける症状を抑えやすくなる。
