## 目的

`ParallelFluidEqualizer.flush` が、同 tick に大量更新された直後の水面でも代表候補の事前走査を先に全部回してしまい、結局 `prepare()` 側で「fresh surge なので今 tick は等化しない」と捨てる分まで前段コストを払っていた。

## 原因

- 流体更新後の `FluidTickBuffer.applyAll` で chunk touch と flow active が記録される
- その同じ tick 終端で `ParallelFluidEqualizer.flush` が走る
- ただし fresh surge の遅延判定は `prepare()` の後段にあり、`selectRepresentativeSources()` の候補抽出・bucket 化・`scanCandidate()` は先に実行される

このため、今 tick は確実に遅延させる候補でも、

- `BlockPos` 化
- load 判定
- fluid state 読み出し
- component 参照
- representative 選別

まで進んでしまっていた。

## 今回の修正

- `selectRepresentativeSources()` に早期スキップを追加
- `forced recheck` でない
- 直近で chunk touch 済み
- かつ `flow active` / 慣性強め / 高水位 のいずれか

という候補は、`prepare()` で捨てる前に候補抽出段階で除外するようにした。

## 再発防止メモ

- 「この tick では必ず後段で落とす候補」がある場合、重い前段走査の前で弾けないかを先に見る
- equalizer まわりは BFS 本体だけでなく、候補抽出・代表選別・profile 判定の前処理コストも含めて見る
