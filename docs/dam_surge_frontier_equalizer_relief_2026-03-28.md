## dam surge frontier equalizer relief - 2026-03-28

### 症状

- ダム放流や大量の既存水移動で、同じ chunk の前線 wake が同 tick に何度も重なって重くなる
- まだ荒れている最中の chunk で equalizer が走り、流動そのものと整流処理が競合して負荷が跳ねる

### 原因

- `AdaptiveTickScheduler.notifyFluidChange(...)` は変更ごとに frontier 近傍を短く起こすため、同じ chunk に変化が密集すると wake が重複しやすい
- `ParallelFluidEqualizer.prepare(...)` は chunk がその tick に激しく更新された直後でも候補を拾えるため、流れている途中の水面を整えに行ってしまう

### 修正

- `AdaptiveTickScheduler` で、同じ chunk がすでにその tick に触られていたら frontier wake を追加で広げない
- bulk notify でも、変更集合の外周だけを frontier wake 候補にして、内部の重複 wake を減らす
- `ParallelFluidEqualizer` では、その tick に触られた chunk で
  - `flow active`
  - recent momentum が強い
  - ほぼ満水量
  のどれかなら、forced recheck 以外の equalizer 準備を 1 tick 見送る

### ねらい

- 水そのものの前進は止めずに、同 tick の重複 wake と早すぎる整流だけを外す
- `fill` だけでなく、ダムや河川の大規模流動でも tick 密度が跳ねすぎないようにする
