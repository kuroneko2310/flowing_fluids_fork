# Worldgen Chunk Frontier Stall (2026-03-28)

## 症状

- ワールド生成の進捗が `スポーン地点を準備中：99%` で止まる。
- `latest.log` ではその後に `spark` の `Timed out waiting for world statistics` が繰り返される。

## 原因

- `ChunkEvent.Load` から `FlowingFluidsTick.onChunkLoad(...)` が呼ばれ、`FluidSpatialGrid.initializeChunk(...)` の最後で frontier 再構築を走らせていた。
- この再構築は各水セルについて 6 方向の近傍を `level.getBlockState(...)` で見ており、chunk 境界セルで隣接 chunk の同期参照に入っていた。
- ワールド生成中はまだ chunk future の内側なので、ここで周辺 chunk を取りに行くと `ServerChunkCache` 待ちに入り、生成が実質停止する。

## 確認に使った材料

- `D:\CurseForge\Instances\main (1)\logs\latest.log`
- `D:\CurseForge\Instances\main (1)\createmod\flowfix\flowing_fluids\thread_dump_2026-03-28_1810_current.txt`
- `D:\CurseForge\Instances\main (1)\createmod\flowfix\flowing_fluids\thread_dump_2026-03-28_1815_confirm.txt`

Server thread は 2 回とも同じ場所で停止していた。

- `FluidSpatialGrid.isFrontierCell(...)`
- `FluidSpatialGrid.rebuildChunkFrontier(...)`
- `FluidSpatialGrid.initializeChunk(...)`
- `FlowingFluidsTick.onChunkLoad(...)`

## 対応

- chunk load 中の frontier 再構築だけ、chunk 内の `LevelChunkSection` から直接読む判定に差し替えた。
- chunk 外へは読みに行かず、chunk 境界や build height 外は保守的に frontier 扱いにする。

## 意図

- frontier hint は最適化用のヒントなので、初期化時は少し保守的でも良い。
- ここで隣接 chunk を同期参照しないことのほうが、ワールド生成の安定性として優先度が高い。
