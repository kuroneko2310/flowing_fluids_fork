# Event-Driven Rain And Water Pressure Retirement (2026-03-28)

## 何を変えたか

- `RainWaterSystem` の chunk 対象収集を、毎回のプレイヤー半径全面走査から wake ベースへ変更した。
- 水圧システムは常駐ランタイムから引退させ、Forge 側の tick / neighbor / fluid placement フックでは動かさないようにした。

## 背景

- 雨は `onLevelTick(...)` のたびにプレイヤー周囲の chunk を半径ぶん集め直していたため、実際に水を置く前の候補収集だけで常時コストが積み上がっていた。
- 水圧システムは本流の fluid 処理とは別に、サーバースレッドで定期走査と近傍更新フックを持っていたため、機能の重さに対して常時負荷が見合いにくかった。

## 今回の方針

- 雨:
  - 雨開始時やプレイヤーが chunk をまたいだ時だけ、周囲 chunk を広く wake する。
  - プレイヤーが同じ chunk に留まっている間は、毎 interval に ring を1本ずつ短く更新して、全面再収集を避ける。
  - 実際の生成候補は wake 中の chunk だけを見る。
- 水圧:
  - 互換性のためクラスや config 項目は残す。
  - ただし live runtime は止め、status でも retired と返す。

## 期待する効果

- 雨天時の常時コストを、`players x radius^2` の収集から大きく外せる。
- プレイヤーが少ない、または移動が少ない状況ほど差が出やすい。
- 水圧システム由来の常駐 tick / update 負荷を完全に外せる。

## 触った主な箇所

- `common/src/main/java/traben/flowing_fluids/rain/RainWaterSystem.java`
- `common/src/main/java/traben/flowing_fluids/water/WaterPressureSystem.java`
- `forge/src/main/java/traben/flowing_fluids/forge/WaterPressureForgeEvents.java`

## 注意

- 雨は「常に半径全域を毎回見る」挙動ではなくなったので、静止中の広域散布は少しだけゆっくり更新される。
- その代わり、雨開始直後や chunk 移動時は広く wake するため、見た目の反応は保ちつつ常時負荷を抑える方向にしている。
