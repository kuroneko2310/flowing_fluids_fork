# Water State Visual Desync - 2026-03-17

## 症状
- 水がなめらかに流れず、気泡や中身だけが動くような見え方になる。
- 横流れで `1 block` ぶんが丸ごと飛ぶように見える。

## 原因
- `MixinFlowingFluid#getNewLiquid` の inject が、通常の水ブロックでも
  現在の fluid state をそのまま返していた。
- そのため、vanilla 側が新しい fluid state を計算する場面まで固定化され、
  見た目や状態遷移が崩れやすくなっていた。
- さらに hydraulic bonus が強いと、横流れ equalize 後に追加で複数レベル送り込み、
  `8 -> 0 / 0 -> 8` に近い移動が起きえた。
- そして lateral 系の複数経路で、source が `0` になるまで横へ移してよい前提が残っていた。
- とくに薄い `1 level` 水の edge drift と、equalize 後の hydraulic bonus が
  river / broad surface 上で `1 block` の空気穴や丸移動を作りやすかった。

## 修正
- `getNewLiquid` への介入を、拡張 waterlog の仮想 fluid state が必要な場合だけに限定。
- hydraulic bonus の横流れ追加転送量を、空マス相手は `1`、既存水ありでも `2` までに制限。
- river / broad surface / 隣接水の多い場所では、source を `0` にする lateral 移動を共通抑制。

## 再発防止
- `getNewLiquid` / `getOwnHeight` / `createLegacyBlock` まわりを触るときは、
  通常の水ブロックと virtual waterlog を分けて考える。
- 流れを速くしたいときは、まず tick cadence と方向選好で速さを作り、
  1 tick の横移動量は控えめに保つ。
