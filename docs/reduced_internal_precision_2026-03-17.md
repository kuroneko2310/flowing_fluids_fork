# Reduced internal precision memo (2026-03-17)

## 見えていた問題

- `0-255` の内部量は細かすぎて、equalizer と安定判定が微差まで追い続けやすかった
- その結果、見た目では同じ `0-8` 水位なのに内部だけ差が残り、平均化の対象が広がりやすかった
- `ParallelFluidEqualizer` では内部量をそのまま `WaterFlowProfile` に渡す経路があり、`0-8` 前提の profile 判定が過大評価されていた

## 今回の変更

- `FluidAmountConverter` の内部量を `0-63` へ縮小
- blockstate 1 段あたりを `8` 内部量にして、vanilla の 8 段水位との対応を分かりやすく維持
- 旧 `0-255` 基準で決めていた閾値は `scaleLegacyInternal(...)` で新精度へ縮小
- dry cell activation と surge 判定も同じ比率で調整
- `ParallelFluidEqualizer` の profile 解析は、内部量ではなく実際の `FluidState#getAmount()` を使うよう修正

## 期待する効果

- equalizer が微差で広がりにくくなり、平面の平均化が暴れにくい
- 内部量の比較コストと churn が下がり、入口や浅水の不自然な再調整が減る
- `0-8` の見た目水位と内部判定のズレが小さくなり、挙動を追いやすくなる
