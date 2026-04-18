# Fluid Plant Drop Suppression 2026-04-13

## 症状

- 雨や水流で花や草のような装飾植物が壊れるたびにアイテム化し、地表や雨量の多い場所でアイテムエンティティが増えて重くなることがあった。

## 原因

- 流体を `FFFluidUtils.setFluidStateAtPosToNewAmount(...)` で設置する直前に、既存ブロックへ常に `FlowingFluid.beforeDestroyingBlock(...)` を呼んでいた。
- この経路は装飾植物でも vanilla のドロップ処理を通すため、雨由来や水流由来の連続破壊がそのままアイテム生成に繋がっていた。

## 今回の修正

- 水で置換される装飾植物だけは `beforeDestroyingBlock(...)` を通さず、静かに水へ置換する判定を追加した。
- 対象は `BushBlock` 系のうち、花や草のような装飾寄りの植物に寄せ、`BlockTags.CROPS`、`BlockTags.SAPLINGS`、`SweetBerryBushBlock` は従来通りドロップを残した。

## 再発を避けるためのメモ

- 流体置換の最終窓口で一律に破壊ドロップを呼ぶと、雨や流体 tick の多い状況でアイテムエンティティが急増しやすい。
- 今後も「自然発生の流体更新で壊れる軽い装飾物」は、ゲーム体験と負荷の両方を見て、静かな置換で済ませられるかを先に確認する。
