# Deep Ocean Inlet Horizontal Probe Relief (2026-04-12)

- Symptom:
  海底の穴や洞窟入口が infinite-biome の供給口として働くのは正しいが、その入口セルが下へ流したあとに残す薄い 1 レベル水でも毎 tick 横方向の spread / slope 探索を続け、広い海底では負荷が積み上がりやすかった。

- Cause:
  `MixinFlowingFluid.tick(...)` は、空気の縦穴へ流すとき source 側に `drop-off` 分を残してから横流れ判定へ進む。
  洞窟入口ではその retained cap は最終的に refill / non-consume で戻る一時状態なのに、deep search まで含む横探索を毎回走らせていた。

- Fix:
  infinite-biome の immediate downward outlet で、`retainedMinimum` だけ残した tick は horizontal search を早期終了するようにした。
  これで「入口が供給口として下へ流し続ける」仕様は維持しつつ、広い海面や海底穴まわりで不要な横探索を減らせる。

- Recurrence note:
  入口 source の供給挙動を保ちたい場合でも、実際に見せたい主役が downward feed なのか lateral redistribution なのかを分けて考えること。
  一時的な retained cap に本流と同じ探索コストを払うと、広域水面で負荷が膨らみやすい。
