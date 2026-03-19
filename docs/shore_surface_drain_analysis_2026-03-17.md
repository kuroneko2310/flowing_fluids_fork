# Shore surface drain analysis (2026-03-17)

## 画像から見えたこと

- 浅瀬の水面が自然な勾配というより、`支えのある面が点々と欠ける` 見え方になっていた
- 岸際で `1 マスだけ抜けた` ような穴が散発していて、横流れの丸め誤差より `drain / evaporation` 系の症状に近かった
- 欠け方が海面付近と岸の境目に寄っているので、通常の dry puddle 蒸発より `infinite biome surface drain` の影響が強いと判断した

## 原因

- sea-level 付近の drain が `randomTick` と `fluid tick` の両方にあり、条件が重なると 1 レベル抜ける機会が二重にあった
- 通常蒸発も infinite biome / river を避けていなかったため、薄い表面水が ambient evaporation の対象に入りえた
- その結果、水平化で埋まる前に 1 レベルだけ抜けて、画像のような斑な水面になりやすかった

## 修正方針

- 通常時の river / infinite biome 水面は `保護された水面` として扱い、通常蒸発から除外
- sea-level drain は `支えの弱い孤立 partial` に限定
- `fluid tick` 側の sea-level drain をやめて、二重 drain を止める
