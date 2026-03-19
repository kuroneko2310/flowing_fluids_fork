# Core profile optimization memo (2026-03-17)

## 何が根っこで重かったか

- `WaterFlowProfile.analyze(...)` が同じ位置に対して横4方向、上下、縦列、安定 tick を別々の関数で何度も読み直していた
- `MixinFlowingFluid` と `ParallelFluidEqualizer` が同じ tick / 同じ位置でも別ルートで profile を再解析していた
- `stableTicks` や縦列高さのような重めの判定まで、候補になっていない浅水や局所水でも毎回走っていた
- `FluidSectionDataCache` の保持量は内部 0-255 精度なのに、一部呼び出し側が 0-8 水位として扱っていて、cache が有効になった瞬間に水理計算が過剰になる筋があった

## 今回の整え

- `WaterFlowProfile` の近傍解析を単一パス化して、上下と横4方向を1回の収集でまとめる
- `stableTicks` は broad surface / reservoir 候補のときだけ評価する
- 縦列高さは pressure / inlet 判定に必要な候補だけ測る
- `FFSectionSampleContext` から profile 解析へ section cache を渡して、tick 中の再計算と再サンプリングを減らす
- `ParallelFluidEqualizer` も同じ section cache を使って、入口近傍だけ別解釈にならないようにそろえる
- `FluidSectionDataCache.amount*` を block-state 水位へ戻して、cache 有効時だけ水理補正が暴れる単位ズレを解消する

## 期待する変化

- 流入口や合流点での「急に忙しくなる」感じが減る
- cache が効いたときでも見た目の押し出しが過剰になりにくい
- 浅い水や平面水で、必要以上に重い判定へ入らず自然に止まりやすくなる

## 今後の方針

- 次に詰めるなら `canFluidFlowFromPosToDirection(...)` のために取っている `BlockState` を、必要条件が満たされた方向だけにさらに絞る
- ただし今回の段階では、挙動互換を崩しにくい範囲で「重複読取の削減」と「不要計算の遅延化」を優先した
