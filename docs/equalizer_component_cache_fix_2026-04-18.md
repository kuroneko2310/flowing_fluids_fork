# equalizer component cache fix 2026-04-18

## 原因

- `FluidSpatialGrid` の `componentId` は Equalizer の代表点選定に使われていた。
- ただし流体量の更新時に `componentId` が局所的に無効化されず、古い連結情報が残りやすかった。
- さらに Equalizer 側では bucket をまたいでも同じ `componentId` をまとめて除外しており、広い水域で別の代表点まで落としやすかった。
- `componentId` の再キャッシュ対象にも air / replaceable が混ざり、実際の水域より広い空間を同一 component とみなしやすかった。

## 今回の修正

- 流体変更位置とその直交近傍の `componentId` を局所的に 0 へ戻すようにした。
- Equalizer の代表点除外は bucket ごとに判定するよう戻した。
- Equalizer の component 再キャッシュは、探索で触れた全セルではなく「同じ fluid を持つセル」だけに絞った。
- `AdaptiveTickScheduler.notifyFluidChangesBulk` の近傍リセットは 3x3x3 全域ではなく、平衡判定で実際に参照する 6 方向中心へ縮めた。

## 今後避けたいこと

- `componentId` を単なる最適化キャッシュとして扱うなら、更新側と参照側を必ずセットで見ること。
- 連結情報の dedupe は「どの粒度で重複とみなすか」を bucket / chunk / component の単位で混ぜないこと。
- BFS や equalizer の探索結果をそのまま連結成分へ使うと、air を含む探索条件と水域の定義がずれやすい。
