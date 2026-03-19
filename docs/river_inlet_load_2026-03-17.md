# River Inlet Load - 2026-03-17

## 症状
- 川から横へ水を流入させると、入口付近で水の動きが落ち着かず、更新負荷も大きくなる。
- 見た目としては、入口だけ落ち着かずにせわしなく水位が触れ続ける。

## 原因
- `WaterFlowProfile` では入口付近も `CHANNEL` と見なされやすく、
  `equalizer` をほぼ毎回キューし続ける。
- さらに `hydraulic intake boost` が川の入口で盛られすぎると、
  入口周辺の lateral transfer が前のめりになり、周辺の再計算も増える。
- その結果、入口近傍で「動きが変」なのと「重い」が同時に起きる。

## 修正
- `WaterFlowProfile.isRiverInletZone()` を追加して、
  `river biome / surface edge / 逃げ道あり / 下方向へ即落下しない`
  入口っぽい場所を判定するようにした。
- このゾーンでは:
  - `adjustScheduledDelay()` で channel の過剰加速をやめる
  - `shouldQueueEqualizer()` で小さい差分のたびに equalizer を投げない
  - `adjustEqualizerLoadFactor()` と `ParallelFluidEqualizer.prepare()` で BFS 深さとノード数を絞る
  - `getDirectionalIntakeBoost()` で hydraulic intake を弱める

## ねらい
- 川の押し出し感は残す
- でも入口近傍だけが過敏に暴れて、TPS と見た目の両方を崩す状態は避ける
