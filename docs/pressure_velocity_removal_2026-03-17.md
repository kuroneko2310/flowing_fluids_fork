# Pressure velocity removal memo (2026-03-17)

## 現在の仕様の整理

圧力まわりは大きく 2 系統あった。

- `pressure / hydraulic drive`
  - 水位差や入口圧を見て、どちらへどれだけ流しやすいかを決める
  - 横流れの分配量や forward bias に効く
- `pressure-based speed`
  - hydraulic で tick delay を短縮する
  - channel velocity を使って勢いや慣性を盛る
  - 見た目より計算回数が多く、入口で特に重くなりやすい

## 今回やめたもの

- hydraulic による tick delay 短縮
- channel velocity を使った方向バイアス加算
- channel velocity を使った慣性増幅

## 残したもの

- `pressureHeadDelta` による押し出し量の補正
- `hydraulicDrive` による流路の優先
- `hydraulicCapacity` による移動量の補正

## 理由

- 速度機能は近傍サンプリングの割に体感差が薄く、入口や合流点での負荷源になっていた
- 一方で、圧力差による「どちらへ流れやすいか」は残したほうが、この mod らしい水位ベース挙動を保ちやすい
- そのため今回は `速度は廃止 / 水位差による押し出しは維持` の形にした
