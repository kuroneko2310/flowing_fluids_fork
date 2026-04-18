# 仮想 waterlog の見た目同期メモ 2026-04-13

## 症状

- 階段、ハーフブロック、フェンスなどの内部に仮想的に保持した水位が、流体計算には反映されても client 側の見た目へ出ない
- とくに block state 自体が変わらないケースでは、水位が変わっても再描画されず、満水のまま見えたり、水が見えないまま残る

## 原因

- `ExtendedWaterlogStore` は server 側だけの一時保存で、client に同期されていなかった
- 描画時の `RenderChunkRegion#getFluidState` は仮想 waterlog を見ず、通常の block / fluid state だけを読んでいた
- `LiquidBlockRenderer` も近傍流体や高さ判定で `BlockState#getFluidState()` を直接読んでおり、`getter.getFluidState(pos)` に寄せただけでは内部水の面が欠ける経路が残っていた
- 仮想水の amount 更新だけでは block update が飛ばないため、chunk mesh の作り直しも起きなかった

## 今回の対応

- Forge の独自 packet で、仮想 waterlog の単体更新と chunk watch 時の chunk 全体同期を追加
- `Level#getFluidState` と `RenderChunkRegion#getFluidState` を仮想 waterlog を含む effective fluid state に寄せた
- client が packet を受けたら、その位置と隣接位置を再描画するようにした
- 切断時は client 側の `ExtendedWaterlogStore` を全消去して、次ワールドへの持ち越しを防いだ

## 今後の注意

- 仮想 waterlog は block state 変更なしで amount だけ変わるので、server 側保存だけでは半分しか直らない
- 見た目修正を触るときは、`ExtendedWaterlogStore`、`Level#getFluidState`、`RenderChunkRegion#getFluidState`、`LiquidBlockRenderer` の近傍参照、packet 同期、再描画をまとめて確認する
