# Water Level Distribution - 2026-03-17

## 何を見たか
- 通常の横流れ `MixinFlowingFluid#ff$flowToSides(...)` は、すでに「源と行き先の差を均す」方向で動いていた。
- でも薄い水が端へ抜ける特別ルートだけは `source=0 / dest=remaining` になっていて、ここだけ 1 タイル丸ごと横へ滑る見え方になっていた。
- `ParallelFluidEqualizer` / `EnhancedFluidBFS` 側は内部 0..255 水量で均し始めているので、通常流動側でも「離散 0..8 へ落とす前の目標水位」をちゃんと持ったほうが噛み合う。

## 今回の整え
- `FFFluidUtils.resolveDiscreteFlowBalance(...)` を追加。
- まず内部水位ベースで「どこまで destination に寄せたいか」を決めてから、実際に置ける 0..8 の組み合わせへ丸めるようにした。
- 丸めるときも `source + destination` の合計水量は崩さず、`minSourceAmount` を守る。
- 薄い水の edge spread でもこの分配を通すようにして、2 以上ある水がいきなり全量ワープしにくくした。
- 通常の横流れの pressure / hydraulic bonus も、最終的にはこの分配ヘルパーへ流して決めるようにした。

## 原因メモ
- 不自然さの本体は「探索ロジック」より「移送ルートごとに分配ルールが割れていたこと」。
- 特に edge spread の特例が、水平化の文脈を飛ばして直接配置していたのが大きかった。

## まだ残る注意
- Minecraft の見た目と blockstate は 0..8 なので、完全連続値にはならない。
- ただし今回の変更で、少なくとも `2 以上あるのに毎回まるごと隣へ移る` みたいな荒い移送はかなり減るはず。
