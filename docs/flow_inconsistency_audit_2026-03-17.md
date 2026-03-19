# Flow Inconsistency Audit - 2026-03-17

## 見つかった不整合

### 1. 戻したはずの最適化がまだ本体 tick に残っていた
- `MixinFlowingFluid` で `macro interior dormancy`、`distance alignment`、`cost field fallback` がまだ有効だった。
- 以前「1,2 を元に戻した」と整理した内容と実コードが食い違っていた。
- これらは tick の間引きや進行方向の差し替えを行うため、水のまとまり方や停止感の原因になりうる。

### 2. 水の出入りごとに広域 component invalidation が走っていた
- 水が空になったり別 fluid に変わるたび、`FluidSpatialGrid.invalidateComponentsInRegion(...)` を
  半径 8-64 相当で呼ぶ構成になっていた。
- 雨・渇水・河川の浅水変化が多い環境では、この invalidation が連続して重くなりやすい。

### 3. 河川の浅水停滞は broad surface 以外の抑制で起きていた
- `river` は broad surface 判定から除外されていたが、
  `stable pool transfer` / `shallow flat transfer` / `exploratory spread` が river を見ていなかった。
- そのため drought や rain が作った浅い部分水が均されずに残っていた。

## 今回の修正
- river biome では浅水・安定水面・探索 spread の抑制を外した。
- 未撤去だった `macro dormancy`、`distance alignment`、`cost field fallback` を本体 tick から外した。
- 広域 component invalidation の呼び出しを止めた。

## まだ注意したい点
- 現在の実プレイ設定は `rain` と `river drought` がかなり強く、浅い部分水の発生頻度そのものが高い。
- `waterTickDelay=2` でも、サーバーが大きく遅れると見た目は止まって見えるので、
  `latest.log` の `Can't keep up!` は今後も合わせて確認する。

## 再発防止
- 「戻した」と判断した最適化は、説明だけでなく参照箇所を `rg` で再確認する。
- tick 間引きや path fallback は、流路の厳密さが必要な本体ロジックへ直接混ぜず、
  まず周辺の通知や遠距離休眠だけに限定する。
- 広域 invalidation を足すときは、雨・渇水・浅水のような高頻度更新と掛け合わせて負荷を見る。
