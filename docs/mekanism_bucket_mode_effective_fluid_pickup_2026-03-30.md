## Mekanism fluid tank bucket mode effective fluid pickup

- 日付: 2026-03-30
- 対象: Forge / Mekanism fluid tank bucket mode

### 症状

Mekanism の fluid tank を bucket mode で使ったとき、Flowing Fluids 側では水が存在していても回収できないケースがあった。

- 浅い partial 水位
- 実効 fluid state では 8 水位だが、`BlockState.getFluidState()` には出てこない仮想 waterlog 系セル

### 原因

Mekanism 本体の bucket mode pickup は `BlockState.getFluidState()` と source 判定を前提にしており、Flowing Fluids の `FFFluidUtils.getEffectiveFluidState(...)` を見ていなかった。

そのため、Flowing Fluids 側では回収可能な fluid でも、Mekanism 側の通常 pickup 経路からは見えずに素通りしていた。

### 修正方針

Forge 側の `MekanismFluidTankBucketCompat` に、bucket mode pickup 専用の connected fluid 回収を追加した。

- `FFFluidUtils.getEffectiveFluidState(...)` を起点に対象 fluid を確定する
- 単体の full/source でも partial でも、Flowing Fluids が管理している allowed fluid なら compat 側で一貫して回収する
- 単一セルの量だけでなく `collectConnectedFluidAmountAndRemoveAction(...)` で connected fluid をまとめて回収する
- ただし回収量はタンクの受け入れ可能量と、bucket mode 用の上限 4 バケツぶんまでに制限する
- タンクへ入れる量は、実際に回収した水位ぶんだけ mB へ変換して反映する
- mB→水位の変換は 1 水位 = 125 mB として上限値を明示し、複数バケツ回収時も 8 水位で頭打ちしないようにする
- pickup 音は block 側に専用音があればそれを優先し、なければ fluid 側の pickup 音へ戻す

### 再発防止メモ

Mekanism 互換を足すときは、`BlockState.getFluidState()` だけでなく `FFFluidUtils.getEffectiveFluidState(...)` が authority になっている場所かを先に確認すること。
また、bucket 的な回収は「クリックした1マスの見た目」ではなく、Flowing Fluids 側の connected amount 回収に寄せて水量を揃えること。
