# Infinite Biome Surface Drain Batching (2026-03-29)

## 原因

- sea level の infinite biome surface drain は、条件に入った partial 水面を 1 マスずつ random tick で削っていた。
- 吸収自体は穏やかでも、海面際の薄い段差が広いと scheduler / slope cache / spatial grid 更新が細かく積み重なりやすかった。

## 今回の調整

- 起点マスの drain 条件はそのまま維持した。
- 実際の吸収だけを小さな局所バーストにして、同じ高さで skylight が通る隣接 thin surface までまとめて引けるようにした。
- 追加吸収量は `drainAmount + 1` を基本に、強めの partial だけさらに `+1` する小さな上乗せに留めた。

## 今後避けたい形

- sea-level の broad surface 全体を深く探索してまとめ吸収すること。
- 安定判定や露出条件を飛ばして、partial 水面を一律に batch drain すること。
- 吸収量を増やす代わりに常時監視や追加キャッシュを足して、総処理量を増やすこと。
