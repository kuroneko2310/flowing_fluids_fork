## bulk fill air column regression - 2026-03-28

### 症状

- `fill` や connected spread で大量の水を一度に生成すると、空中の水柱や崖ぎわで重くなりやすい
- 生成直後の途中段に水が残り、落下が数 tick もたつくことがある

### 原因

- connected fill / connected remove の適用が、各セルごとに
  - `AdaptiveTickScheduler.notifyFluidChange(...)`
  - `ChunkLocalSlopeCache.clearForFluidChange(...)`
  - `FluidSpatialGrid` 更新
  - connected component invalidation
  を個別に走らせていた
- 大量生成時は水そのものより、通知と cache 破棄の連打が重くなっていた
- 生成されたセルは active にはなるが、空中へそのまま落ちる列でも tick 予約が薄く、途中水が少し残りやすかった

### 修正

- `FFFluidUtils` の connected fill / connected remove 適用中だけ、fluid change 通知を thread-local の小さい batch にまとめる
- apply 完了後に
  - `FluidSpatialGrid.setFluidAt(...)`
  - `AdaptiveTickScheduler.notifyFluidChangesBulk(...)`
  - `ChunkLocalSlopeCache.clearChunk(...)`
  - connected component invalidation
  を一度ずつ流す
- bulk で置かれた水のうち、下へそのまま流せるセルは `scheduleTick(..., 1)` を追加して、空中列のもたつきを減らす

### 今後の注意

- batch 化は `connected fill / connected remove` の apply にだけ限定している
- 単発の `setFluidStateAtPosToNewAmount(...)` まで広げると責務が重くなりやすいので、まずはこの範囲で止める
- もし次に重さが残るなら、探索そのものではなく `fill を呼ぶ側が同 tick に何度同じ塊を触っているか` を見る
