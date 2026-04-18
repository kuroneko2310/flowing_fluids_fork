# Infinite Biome Deep Refill And Drain (2026-04-12)

- Symptom:
  `infinitybiome` 系の海で、sea-level の薄い水が random tick でもほとんどドレインされず、海の深い場所でも補充が弱くて欠けたまま残りやすかった。
- Cause:
  infinite biome のドレイン / 補充の入口が `sky light > 0` に強く依存していて、深海や上を覆われた ocean 系の水塊では ambient maintenance が走りにくかった。さらに flowing refill は深さに関係なく低い chance / max amount のままだった。
- Fix:
  infinite biome の ambient 判定を「空が見える」だけでなく「海面下のちゃんとつながった ocean / beach / shore 水塊」でも通すようにし、deep ocean では flowing refill の chance と最大補充量を深さに応じて大きく上げた。あわせて、sea-level の薄い 1-2 レベル水は一回で抜けやすい drain 量に寄せた。
- Recurrence note:
  infinite biome の水挙動をまた触るときは、`sky light` の有無だけで surface / deep ocean の upkeep を切らず、sea level 判定・水塊のつながり・深さによる圧の違いをまとめて見ること。
