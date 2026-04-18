# Server Block Update Displacement Guard (2026-03-28)

## 原因

- Forge の `Level#setBlock(...)` mixin は、流体を含むブロックが別ブロックへ置き換わるたびに displacement を試していた。
- この入口はプレイヤー設置だけでなく、サーバー側の内部配置や生成寄りの更新も通るため、流体系の追加処理が本来の block update と同じ tick で走っていた。

## 再発条件

- neighbor update を出さない `setBlock` 呼び出しでも mixin がそのまま流体 displacement を実行する。
- その結果、生成・自動配置・内部補正のような更新が、余計な fluid 探索や schedule を巻き込む。

## 今回の対応

- Forge 側の `MixinLevel` で `Block.UPDATE_NEIGHBORS` が立っていない更新は displacement をスキップするようにした。
- 通常の配置系更新はそのまま通しつつ、neighbor 通知を伴わないサーバー側更新には流体系を割り込ませない。

## 今後の方針

- block update に流体系を差し込むときは、まずその更新が「通常プレイの隣接反応を期待している更新」かを確認する。
- worldgen や内部補正のように neighbor 通知を抑えている経路には、同じ tick で追加の fluid 処理をぶら下げない。
