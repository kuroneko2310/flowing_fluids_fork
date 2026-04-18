## 目的

- tick 終端の `flush/apply` 合流点で、仕事がない tick まで毎回重い反映処理へ入らないようにする
- chunk load 直後の frontier rebuild を即時全量実行せず、maintenance 側へ逃がして server spike を減らす
- `WaterFlowProfile` と macro/frontier 判定の同 tick 再計算を減らし、探索系の重なりを軽くする

## 今回の変更

### 1. tick 終端の flush/apply 合流点の待機コスト削減

- `FluidTickBuffer` に dimension 単位の `pendingSignals` を追加
- buffer へ変更を積んだときだけ signal を立て、`applyAll` は signal がない dimension では即 return
- `FlowingFluidsTick` では
  - buffer に pending があるときだけ `FluidTickBuffer.applyAll`
  - equalizer queue があるときだけ `ParallelFluidEqualizer.flush`
  - equalizer 反映後に buffer が増えたときだけ再 apply

これで、何も溜まっていない tick の終端で毎回 map をなめたり queue を drain しに行く無駄を減らした。

### 2. chunk load 時の frontier rebuild の遅延化

- `FluidSpatialGrid.initializeChunk` では frontier をその場で rebuild せず、chunk を `dirtyFrontierChunks` に積むだけに変更
- rebuild は `performMaintenance` と tick 終端から budget 付きで少しずつ処理
- fluid 設置/除去の局所更新では、周辺 frontier だけを狭く更新
- frontier が dirty な間は macro hint を保守的に返し、判定を壊さずに rebuild 完了までつなぐ

これで、worldgen や高速移動で chunk が連続初期化される場面でも、load 直後に frontier 全再構築が server thread に刺さりにくくなった。

### 3. WaterFlowProfile と macro/frontier 判定の再利用

- `FFSectionSampleContext` に直前 1 件の profile を持つ軽い last-hit cache を追加
- 同じ pos / fluid / amount の連続参照では map lookup や再解析を避ける
- `MixinFlowingFluid` の exploratory spread 抑制判定では、すでに取ってある `WaterFlowProfile` をそのまま渡して再利用
- `profile.isBroadSurface()` を直接使い、同 tick に broad-surface を取り直さないようにした

この変更は新しい広域キャッシュや常時監視を増やさず、既存の計算の重なりだけを削ることを狙っている。

## 判断メモ

- Forge 限定の流れは維持
- 常時処理や補助スレッドは増やしていない
- chunk load の spike と tick 終端の空振りコストを優先して削っている
- dirty frontier 中の macro hint は安全側に倒してあり、軽さのために挙動整合性を崩さない方を選んだ
