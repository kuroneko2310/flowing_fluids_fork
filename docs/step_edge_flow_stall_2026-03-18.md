# Step Edge Flow Stall - 2026-03-18

## 原因
- `MixinFlowingFluid.tick` の `flowToEdges` 分岐で、薄い水の段差落下判定が `flowing_fluids$getImmediateThinEdgeDrop(...)` だけになっていた。
- 以前は `flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(..., 1, true)` をフォールバックに使っていて、1 マス横に寄ってから落ちるような短い段差も拾えていた。
- このフォールバックが抜けたことで、隣接ブロックが即落下口ではない段差地形で水が「安定した」とみなされ、そのまま止まっていた。

## 修正
- まず即時の薄い段差落下チェックを行い、見つからない場合だけ広めの slope 探索へフォールバックする形に戻した。
- ただし `flowing_fluids$shouldSuppressThinCapDrift(...)` が真になる、本当に落ち着かせたい薄い表面水はそのまま抑制を優先する。

## 再発防止メモ
- 「表面安定化」の条件を増やすときは、同じ分岐にある「段差へ逃がすフォールバック探索」を一緒に確認する。
- 特に `remainingAmount <= dropOff` の薄い水は、平坦面のドリフト抑制と段差落下の両立が必要なので、隣接判定だけに縮めない。
