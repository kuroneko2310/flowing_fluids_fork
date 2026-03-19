# Review follow-up stability fixes (2026-03-19)

## 原因メモ
- `RainWaterSystem` の `placementQueue` は `rainPlacementQueueSize <= 0` のとき無制限扱いで、長雨や処理詰まり時にキューだけが伸び続ける経路があった。
- `FlowingFluids` の `isManeuveringFluids` / `pistonTick` / `debug_killFluidUpdatesUntilTime` は複数箇所から読み書きされるのに可視性保証がなく、スレッド間で古い値を読む可能性があった。
- `HierarchicalDistanceManager` のプレイヤー近接キャッシュは静的共有で、次元ごとの分離とアンロード時の破棄が足りず、長時間稼働で不要エントリが残りやすかった。
- `FFConfig` は読み込み後の最低限の範囲補正がなく、負数や 0 がそのまま実行系に入り、雨キューや tick 間隔などの安全前提を崩せた。

## 今回の修正
- 雨の placement queue は常に 1 以上の上限で扱うようにし、設定異常時でも無制限成長しないようにした。
- 上記の runtime フラグは `volatile` にして、軽量なまま可視性を確保した。
- プレイヤー近接キャッシュを dimension 単位に分離し、`clearDimension(...)` でキャッシュ本体と更新時刻をまとめて破棄するようにした。
- `FFConfig.sanitizeRanges()` を追加し、雨系・scheduler 系・基本 flow/tick 系の下限と一部確率値を補正するようにした。
- 未完了コメントのうち、意味が確定していて安全に消せるものを整理した。

## 今後避けたい実装
- `<= 0 なら無制限` のようなフェイルオープンを、ワールド常駐キューやキャッシュで使わない。
- static runtime フラグを複数スレッドから触るときは、少なくとも `volatile` か atomic を前提にする。
- プレイヤーやチャンクのキャッシュは、chunk だけでなく dimension をキー設計に含めて、アンロード時の purge 経路まで一緒に持つ。
