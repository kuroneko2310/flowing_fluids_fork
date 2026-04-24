# Create Basin external fluid compat memo

## Symptoms

- Create の Basin / Mechanical Mixer は、バケツで入れた水や溶岩には反応する。
- ただし、Basin ブロック空間に外から実際に流れ込んだ水や溶岩は、レシピ入力として見ていなかった。
- そのため、Mekanism などで外部から流体を置いたケースでは、見た目に水や溶岩が入っていても Mixer が始動しない。

## Cause

- `BasinBlockEntity.isEmpty()` は内部インベントリと内部タンクだけを見ていた。
- `BasinRecipe.apply(...)` も Basin の内部 `IFluidHandler` だけを入力源として扱っていた。
- Flowing Fluids 側の `effective fluid state` は Basin ブロック内の外部流体を保持できるが、Create の Basin レシピ処理には橋渡しされていなかった。

## Fix

- Forge の Create 連携だけで、Basin 内の `effective fluid state` を外部入力として参照する compat を追加した。
- Basin tick で外部流体量の変化を監視し、流入・流出時に `contentsChanged` を立て直すようにした。
- Basin レシピ適用時は、内部タンクで足りない分を外部流体から補って判定し、実際に消費したぶんは Basin ブロック内の流体量へ書き戻す。

## Notes

- 量の橋渡しは Flowing Fluids の 1 level = 125 mB に合わせている。
- Basin 外部流体の表現精度を超える端数は保持できないので、今後も Basin へ直接流し込む互換は 125 mB 単位を前提に考える。
- 今回は Forge 限定・Create Basin/Mixer 限定で修正し、他ローダーや共通化には広げていない。
