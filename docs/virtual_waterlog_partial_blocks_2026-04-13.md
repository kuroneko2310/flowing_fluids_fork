# Virtual Waterlog Partial Blocks

## 症状

- 階段やハーフブロックが vanilla の `WATERLOGGED` 二値処理に落ちて、内部水位が `0/8` しか持てなかった。
- その結果、部分ブロック内に入った水がフル水位のまま残りやすく、段差出口や横抜けがあっても自然に流れ出しにくかった。
- virtual waterlog セルは通常の fluid block ほど近傍更新で起きないため、出口が後から開いたときに排出開始が遅れる経路もあった。

## 原因

- `FFFluidUtils.isVanillaWaterloggable(...)` が stairs/slabs も vanilla waterlog として扱っていた。
- `setFluidStateAtPosToNewAmount(...)` が stairs/slabs を partial virtual fluid ではなく vanilla の `placeLiquid/pickupBlock` に流していた。
- `getEffectiveFluidState(...)` は block の base fluid state を先に見ていたので、virtual 側へ移した部分水位を優先しづらかった。
- 仮想流体セルの出口開放時に、近傍ブロック更新から fluid tick を起こす補助が不足していた。

## 今回の修正

- stairs/slabs を「shape-aware virtual fluid state」へ寄せ、vanilla waterlog の 0/8 制限から外した。
- collision shape の face opening を見る軽い判定を追加して、階段の向きや top/bottom の違いを出口判定に反映した。
- stairs/slabs で virtual 保持へ切り替えるときは `WATERLOGGED=false` に戻してから `ExtendedWaterlogStore` へ保存するようにした。
- `getEffectiveFluidState(...)` は virtual store を先に見るようにして、partial amount を正しく拾うようにした。
- Forge の `Level#setBlock(...)` 由来の更新で、周囲の virtual fluid cell を起こして「出口が開いたのに流れない」を減らした。

## 今後の注意

- 今回は Forge での流路・排出改善に絞っていて、render 高さそのものを stairs/slabs 専用に描き分ける変更はまだ入れていない。
- もし見た目の水面高さももっと block shape に寄せたくなったら、次は render 側の height 解釈を block context 付きで見る必要がある。
