# Evaporation misfire analysis (2026-03-17)

## 誤作動していた点

- 通常蒸発が `孤立 puddle` だけでなく、まだ横へ流れられる浅い水にも触りうる状態だった
- 熱源蒸発も `近くに熱源がある` だけで連結水面へ効きやすかった
- 川の干ばつ drain が `flow active` より前に評価されていて、動いている川の水でも薄くなる筋があった
- river drought の条件が広く、川の表面水でも edge 判定なしで drain 対象になりやすかった

## 今回の修正

- 通常蒸発は `infinite biome/river 保護水面`, `flow active`, `横へ流せる水` を除外
- 熱源蒸発も同じく `保護水面`, `flow active`, `横へ流せる水` を除外
- 川の drought drain は `flow active` を先に除外
- drought drain 自体も `保護水面ではない`, `supported でない or lateral 1 以下の edge-like shallow water` に限定

## 期待する効果

- connected な水面や岸の浅い連結水が勝手に 1 マス欠けにくくなる
- 熱源や dry season があっても、本流や大きい水面ではなく端の薄水だけが減りやすくなる
- 蒸発系の演出は残しつつ、通常の川・海・無限バイオームでの誤作動をかなり抑えられる
