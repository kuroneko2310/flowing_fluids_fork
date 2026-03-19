# 大量水での scheduler 近傍スキャン回帰メモ

## 何が重かったか

大量の水があるとき、`AdaptiveTickScheduler.shouldTick` は安定した水面の内側でも次の近傍処理を順番に走らせていた。

- `hasNearbyStepDown`
- `hasStrongLevelDifference`
- `calculateEquilibriumIndex`

この3つはどれも近傍の `getBlockState` / `getEffectiveFluidState` に依存していて、広い水塊の内側でも「周囲は全部水」という結果を毎回ほぼ同じ形で確認し直していた。

とくに回帰点だったのは、軽量化のために導入した `FluidSpatialGrid` を持っているのに、grid で「隣に fluid がある」と分かるケースでも blockstate 読み取りへ降りていたこと。

## 根本原因

- 軽量化用の判定が、広水面の内側で早期終了せず、近傍スキャンを重複実行していた
- `FluidSpatialGrid` の存在チェックを使わず、filled neighbor に対しても blockstate 読み取りを続けていた
- その結果、「安定していて何も起きない水」ほど判定コストだけを払い続ける構造になっていた

## 今回の修正

`AdaptiveTickScheduler` で以下を行った。

- `calculateEquilibriumIndex`
  - `FluidSpatialGrid` が fluid ありを返した近傍では、blockstate / effective fluid の読み取りを省略
- `shouldTick`
  - 下と水平4方向がすべて fluid で埋まっている場合、`hasNearbyStepDown` と `hasStrongLevelDifference` を丸ごとスキップ
- `hasAdjacentAir`
  - grid で fluid ありと分かる近傍は air 判定のために読み直さない
- `hasStrongLevelDifference`
  - grid 上で差分が閾値未満の近傍は blockstate を読まずに継続
- `hasNearbyStepDown`
  - 横隣やその下が grid 上で fluid ありなら、落下先探索の blockstate 読み取りを省略
- `isNarrowChannel`
  - hot path で毎回 `pos.north()` などを生成していたので、`MutableBlockPos` に置き換えて小さな割り当てを削減
- `isSolidWall`
  - grid 上で fluid ありと分かる近傍は wall 判定のために blockstate を読まない

あわせて品質面の整えとして、次も反映した。

- `WaterFlowProfile` の未使用 `clampSnapshotRadius` インスタンス版を削除して static 実装へ統一
- `MixinFlowingFluid` の未使用ローカル `combinedTotal` を削除

## 期待される効果

- 海や巨大水槽の内側で、tick あたりの world 参照回数がかなり減る
- 水が多いほど重くなる「判定だけの固定費」が下がる
- 新しい判定を足すのではなく、既存判定の読み取り量を削る方向なので、挙動差分は小さい

## 今後の注意

- 軽量化用キャッシュや profile を追加するときは、「その判定自体が blockstate を何回読むか」を先に確認する
- 広水面の内側で毎回同じ結論になる処理は、grid や既存キャッシュだけで早期終了できないかを優先して見る
- `WaterFlowProfile` 側の軽量化も、判定追加で重くならないように「広水面内側での最短経路」を意識して設計する
