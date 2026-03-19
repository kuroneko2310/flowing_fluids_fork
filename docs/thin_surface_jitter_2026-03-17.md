# Thin Surface Jitter - 2026-03-17

## 症状
- 平面上の `水位1` が単体、または 2-3 個だけつながった小さな水たまりで、
  左右へ高速に滑るような横移動が起きる。

## 原因
- このサイズの薄水でも `AdaptiveTickScheduler` が `connected flow line` 扱いして tick を前のめりにしていた。
- さらに `thin cap drift` と `exploratory spread` が「細い流れ」と誤認して、
  平面上の小さな薄水クラスターを横方向へ押し続けていた。
- 本来は川筋や水路の流芯ではなく、平面でいったん落ち着く残り水に近い。

## 修正
- `FFFluidUtils.isSmallSupportedThinSurfaceCluster(...)` を追加して、
  `平面 / 支持あり / 上に水なし / 下へ逃げ道なし / 水位が薄い / 連結数 3 以下`
  の小クラスターを判定するようにした。
- この判定に当たる水は:
  - `AdaptiveTickScheduler.isConnectedFlowLine(...)` で加速対象から外す
  - `shouldSuppressThinCapDrift(...)` で横滑りを止める
  - `shouldSuppressExploratorySpread(...)` で探索的な横移動を止める
  - `randomTick` の遠距離 leveling も行わない

## ねらい
- 川や細水路の勢いは残す
- でも、平面に残った `水位1` の小さな塊だけは落ち着いてその場に留まるようにする
