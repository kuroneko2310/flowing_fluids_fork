# Inlet Profile Cache - 2026-03-17

## 何を直したか
- `WaterFlowProfile` は 1 tick の中で同じ位置に対して何度も計算されやすかった。
- 入口判定も river 専用に寄っていて、湖や広い水面の出口っぽい場所では同じ種類の過敏さが残りやすかった。

## 修正
- `FFSectionSampleContext` に `waterProfile(...)` キャッシュを追加して、
  同一 tick / 同一位置 / 同一水量 の `WaterFlowProfile` を使い回すようにした。
- 水が動いた位置は `invalidate(...)` で profile キャッシュも落とすようにした。
- `WaterFlowProfile.isInletZone()` を追加して、
  `surface edge / 逃げ道あり / 即落下なし / ある程度の水量支持あり`
  の入口っぽい場所を river 以外でも扱うようにした。
- 入口ゾーン全般で:
  - tick 加速を弱める
  - equalizer の再キューを鈍くする
  - equalizer の BFS 深さとノード数を絞る
  - hydraulic intake boost を抑える

## ねらい
- 毎回同じ profile を作り直す無駄を減らす
- 川だけでなく、湖や広い水面の出口でも入口近傍の暴れ方と負荷を抑える
