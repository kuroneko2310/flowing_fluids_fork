# Airborne Water Retention Adjustment (2026-03-28)

## 症状

- surface drain を保守的にしたあと、空中へ落ちる水の先端で 1 レベルだけ残る場面が増えた。
- とくに水路の流入口や段差ぎわで、空中に薄い水が貼りついたまま次の落下へ移りにくくなる。

## 原因

- `MixinFlowingFluid` の downward flow は、空気柱へ落ちるとき retention anchor があれば最小量を残す。
- この保守は canal の上流を全部吸い切らないために有効だが、最近まで流れていた active/frontier 水にも効くと、
  空中停止しやすい 1 レベル cap を増やしてしまう。
- さらに thin cap drift suppression も、同じような active/frontier 水まで止めると段差方向の探索が起きにくくなる。

## 対応

- `flow active` 中、または recent flow momentum が強い水では downward retention を行わないようにした。
- 同じ条件では thin cap drift suppression も外して、勢いのある先端は素直に落ち先を探せるようにした。

## 意図

- 落ち着いた支え付きの薄水は従来どおり保持する。
- でも直前まで流れていた先端だけは、空中停止より落下継続を優先する。
