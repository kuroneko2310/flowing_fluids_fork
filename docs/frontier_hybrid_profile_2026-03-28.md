# Frontier Hybrid Profile 2026-03-28

## 目的

水 tick のたびに詳細な近傍判定を最後まで走らせるのではなく、
「明らかに静かな広水面」は軽い一次判定で calm body 扱いに寄せ、
前線や出口を持つ水だけ従来の詳細判定へ進める。

同時に、同 tick 中のローカル書き換え後でも section sample cache を全停止させず、
変更された section だけ差し替えて残りを再利用する。

## 変更点

1. `WaterFlowProfile`
- `sampleBasicNeighborhood(...)` を追加
- `qualifiesForFastCalmInterior(...)` と `tryFastCalmInteriorProfile(...)` を追加
- 明らかな full-width の calm water は `LARGE_BODY` を即返す
- それ以外だけ従来の `sampleNeighborhood(...)` に進む

2. `FFSectionSampleContext`
- ローカル書き換え時に `dirtyTick` で cache 全停止していた流れをやめた
- `FluidSectionDataCache.invalidate(pos)` で touched section だけ無効化する
- water profile cache は安全側で全クリアしつつ、section cache 自体は生かす

## 期待している効果

- 広い静水面での `canFluidFlowFromPosToDirection(...)` 呼び出し回数の削減
- 同 tick 内で連続的に起こる局所更新後の section cache 再構築量の削減
- 既存の tick / equalizer / scheduler の流れを崩さないまま、前線だけ精密に見る方向へ寄せる

## ロールバックしやすい単位

1. 一次判定だけ戻したい場合
- `common/src/main/java/traben/flowing_fluids/optimization/WaterFlowProfile.java`
  - `sampleBasicNeighborhood(...)`
  - `qualifiesForFastCalmInterior(...)`
  - `tryFastCalmInteriorProfile(...)`
  - `analyze(...)` の fast path 呼び出し

2. section cache の局所 invalidation だけ戻したい場合
- `common/src/main/java/traben/flowing_fluids/FFSectionSampleContext.java`
  - `invalidate(...)`
  - `shouldBuildSectionCache(...)`
  - `dirtyTick` 削除まわり

## 注意

- 今回は Forge 側の水挙動改善に必要な共通ロジックだけを触っている
- 物理ルール自体は大きく変えず、判定の段階化と cache 再利用に寄せた
- calm water を誤判定すると前線の更新が鈍るので、広水面 fast path は
  `amount == 8`, `hasFluidAbove == false`, `surfaceEdgeCount == 0`, `lateralWaterNeighbors == 4`
  に絞ってある
