# River Flat Flow Stall - 2026-03-17

## 症状
- 河川バイオームの広い水面で、浅い部分水がギザギザの柱状に残り、均されずに流れが止まって見える。
- スクリーンショット時刻まわりでは `Can't keep up!` が複数回出ており、見た目上の停止感が強くなっていた。

## 原因
- `MixinFlowingFluid` の `flowing_fluids$shouldSuppressStablePoolTransfer(...)` と
  `flowing_fluids$shouldSuppressShallowFlatTransfer(...)` は、
  broad surface 判定とは別系統の「浅い平坦水面の抑制」を行っていた。
- 河川は `FFFluidUtils.classifyBroadSurfaceWater(...)` では除外されていたが、
  上記 2 つの抑制は river biome を見ていなかったため、
  drought / rain で発生した 1-4 レベルの浅水が均されずに残っていた。
- `flowing_fluids$shouldSuppressExploratorySpread(...)` も river を特別扱いしておらず、
  河川の浅い停滞水が横方向へ流れを探す動きまで抑えやすかった。

## 修正
- river biome 上の水では `stable pool transfer` 抑制を無効化。
- river biome 上の水では `shallow flat transfer` 抑制を無効化。
- river biome 上の水では `exploratory spread` 抑制を無効化。

## 再発防止
- broad surface を river から除外していても、後段の抑制ロジックが river を知らないと同種の停滞が起きる。
- 水面安定化ロジックを追加するときは、
  `ocean / beach / river / 人工水路` のどこに効かせるかを個別に確認する。
- 見た目が「止まっている」不具合では、例外だけでなく `latest.log` の lag 警告も合わせて確認する。
