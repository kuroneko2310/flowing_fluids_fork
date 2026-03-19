## 症状

- 2026-03-19 の `latest.log` / crash report で、ワールド tick 中に `IllegalClassLoadError` が発生。
- 原因は `MixinWaterFluid` から `traben.flowing_fluids.mixin.MixinFluidRegressionLogic` を通常クラスとして直接参照していたこと。

## 何が危なかったか

- `*.mixin` パッケージ配下のクラスは Mixin 側が所有物として扱うため、通常コードから直接読むと class-load 時点で落ちることがある。
- 今回は純ロジックの小さな回帰ガードでも、配置先が mixin パッケージだっただけでサーバー tick を止めるクラッシュになった。

## 今回の修正

- 純ロジックを `traben.flowing_fluids.FluidRegressionLogic` へ移動。
- `MixinWaterFluid` と `MixinFlowingFluid` は新しい共通ヘルパーを参照するよう更新。
- 既存の回帰テストも新しい配置へ追従。

## 次に避けたい実装

- mixin 本体以外の再利用ロジック、定数、判定関数は `*.mixin` 配下に置かない。
- mixin から共有したい処理は、通常パッケージの helper / util / logic クラスへ逃がしてから参照する。
- classload 周りの不具合はコンパイルでは見えにくいので、mixin から新しい helper を呼ぶ変更では起動確認かテストをセットで行う。
