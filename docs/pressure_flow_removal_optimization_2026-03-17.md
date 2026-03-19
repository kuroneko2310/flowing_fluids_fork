## Pressure flow removal optimization

- 目的:
  - 水流の見た目改善よりも計算そのものの必要性を見直し、`pressure / hydraulic` 系の重複サンプリングを流れ本体から外す。
- 問題:
  - 横流れで `pressure head`, `hydraulic drive`, `channel capacity` を別々に計算し、同じ column/近傍情報を何度も見ていた。
  - 方向選択でも pressure bias を使っていたため、安定面や浅い流れでも追加の近傍計算が混ざっていた。
  - `WaterPressureSystem` が無効でも Forge event 側は毎 tick / 毎 neighbor update で handler 呼び出しまで進んでいた。
- 修正:
  - `MixinFlowingFluid` の横流れと方向選択から pressure/hydraulic 依存を外し、`WaterFlowProfile` の既存解析結果だけで forward bias を決めるようにした。
  - 下方向の保持量調整も column pressure 計算ではなく `WaterFlowProfile` の regime ベース軽量判定へ置き換えた。
  - `FFSectionSampleContext` から directional hydraulic metrics cache を削除した。
  - `WaterPressureForgeEvents` は config 無効時に event handler 冒頭で return するようにした。
- ねらい:
  - 「軽量化のために流れを鈍くする」より先に、「同じ情報を何度も取りに行かない」構造へ寄せる。
  - 流れの押し出し感は `WaterFlowProfile` の regime 判定で残しつつ、圧力由来の重い追加計算は止める。
