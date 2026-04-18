# Surface Drain Inlet Regression (2026-03-28)

## 症状

- 川や海へ水路から水を流し込んだとき、sea level の境界で大きな tick 落ちが出ることがある。
- `latest.log` では `Can't keep up!` が連続し、`water_infinite_biome_surface_drain_chance = 1.0` のような強い drain 設定で悪化しやすかった。

## 原因

- `MixinWaterFluid.randomTick(...)` の sea-level infinite biome drain は partial 水位を random tick ごとに判定していた。
- 流入口では partial 水位が大量にできやすく、drain のたびに scheduler / slope cache / spatial grid 更新が走る。
- そのあと refill や equalization が戻そうとするため、流入口の前線だけで小さな往復が続きやすかった。

## 対応

- surface drain は `amount <= 4` の薄い sea-level 水だけに限定した。
- `AdaptiveTickScheduler.isFlowActiveNow(...)` と flow inertia を見て、流入直後の前線では drain しないようにした。
- `AdaptiveTickScheduler.getPoolStableTicks(...)` を使って、少し落ち着いた partial 水だけ drain 候補にした。
- drain 量は amount に応じて少し増やすようにして、対象セルの往復回数を減らした。

## 意図

- 海や川そのものを広く起こすのではなく、流入口の薄い境界だけが過剰に往復しないようにする。
- random tick 1 回あたりの判定回数はそのままでも、実際に drain まで進むセルを減らし、成功時の往復回数も減らす。
