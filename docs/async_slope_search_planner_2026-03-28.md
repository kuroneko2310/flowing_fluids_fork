# Async Slope Search Planner (2026-03-28)

## 原因

- `MixinFlowingFluid#getValidDirectionFromDeepSpreadSearch(...)` は、遠距離の水面でも server thread 上で `getSlopeDistance(...)` の再帰探索を行っていた。
- とくに広い平面や遠距離の海面では、cache miss のたびに複数方向へ deep search が走り、server tick の重い山になりやすかった。

## 再発条件

- FAR / DISTANT tier の water tick で slope cache が空のまま deep spread search に入る。
- 方向ごとの slope distance をその場で同期計算するため、server thread が探索コストを丸ごと抱える。

## 今回の対応

- `AsyncSlopeSearchPlanner` を追加し、遠距離水面の slope search を immutable snapshot から worker pool で先読みするようにした。
- server thread 側は `ChunkLocalSlopeCache` をまず参照し、未計算なら async request を投げて短く defer する。
- world の反映や `setBlock` は引き続き server thread だけで行い、off-thread では read-only な方向探索だけを担当させる。
- 仮想 waterlog / pass-through ブロックが絡む局所ケースは async 対象から外して、危ない経路を広げないようにした。

## 今後の方針

- さらに分散を進める場合も、world write を worker に渡さず「snapshot 計算だけ async、反映は server thread」の境界を守る。
- 重い経路を増やさないため、まず slope search / horizontal planning の cache hit 率と defer 回数を見てから次を考える。
