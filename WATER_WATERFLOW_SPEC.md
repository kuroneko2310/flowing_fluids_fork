# 水まわり仕様＆蒸発ロジックまとめ（Flowing Fluids 現行実装）

本ドキュメントは、Flowing Fluids が提供する水関連の仕様・挙動、とくにランダムティックでの蒸発／補充処理を、AI が理解しやすい粒度で整理したものです。

## 全体像
- 水はほぼ有限資源として扱われ、レベル1〜7の水は流動・消費の対象になる。一方で海・川・湿地などの無限水源バイオームでは特別な補充・非消費ロジックが存在する【F:README.MD†L132-L138】【F:common/src/main/java/traben/flowing_fluids/config/FFConfig.java†L28-L74】。
- 各種チャンス値（蒸発、雨補充、バイオーム補充など）は `/flowing_fluids settings` コマンドから変更可能で、クライアント／サーバ間で同期される【F:common/src/main/java/traben/flowing_fluids/config/FFCommands.java†L399-L471】【F:common/src/main/java/traben/flowing_fluids/config/FFConfig.java†L21-L118】。
- 蒸発や補充は水の `randomTick` 内で処理され、クライアント・無効化設定・ブラックリスト対象・遠距離プレイヤー不在などの場合はスキップされる【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L45-L66】【F:common/src/main/java/traben/flowing_fluids/config/FFConfig.java†L89-L118】。

## ランダムティック処理フロー（`MixinWaterFluid.randomTick`）
1. 前提チェック：クライアント側、無効化状態、対象外フルイド、プレイヤー距離設定によるスキップを判定【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L45-L66】。
2. バイオーム・高度キャッシュ：
   - `isWithinInfBiomeHeights` … 無限バイオーム（海・川・湿地など）で補充／排水を行う高度条件。`fastBiomeRefillAtSeaLevelOnly` が有効なら海面/その直下のみ、それ以外は海面かつY>0【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L58-L65】。
   - `hasSkyLight` … 空からの光が届くか（雨補充やバイオーム補充で使用）【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L62-L65】。
   - `isInfBiome` … 対象位置のバイオームが無限水源として扱われるか【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L64-L65】。
3. 水量別の処理分岐：
   - **非満水（1〜7レベル）**の場合、順に「バイオーム補充/排水」→「雨補充」→「ネザー蒸発」→「通常蒸発」を試行する。いずれか成功で後続処理を打ち切る（蒸発成功時のみ終了しないケースもあり）【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L66-L87】。
   - **満水（8レベル）**の場合は雨補充のみ試行（増量が成功しても戻らず、そのまま他処理なし）【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L87-L93】。

## 補充・排水ロジック詳細
### 雨補充 `ff$tryRainFill`
- 成功条件：`chance < min(rainRefillChance, evaporationChanceV2/3)` かつ「雨が降っている」「頭上が空」「(無限バイオームの海面/直下でない)」「デザート村バイオームでない」【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L99-L116】。
- 補充量：雷雨なら +2、通常の雨なら +1。`FFFluidUtils.placeConnectedFluidAmountAndPlaceAction` で連結水量を考慮しつつレベルを上げ、差分が出た場合にのみ適用【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L108-L114】。
- 理由：雨補充率が蒸発率の1/3以下に制限され、降雨による無限生成を防止【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L99-L104】。

### 無限バイオーム補充／排水 `ff$tryBiomeFillOrDrain`
- **海面での排水**：海面Yで、乱数が「非消費率」「補充率」「雨補充率」のいずれかを下回ると発動。直下が満水の水、天空光が届く、無限バイオームであることが条件。現在レベルから2減少させ、流入した水を吸収するイメージ【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L120-L136】。
- **海面/直下での補充**：`isWithinInfBiomeHeights` が true かつ水量<8、乱数が補充率未満の場合に発動。無限バイオームかつ天空光ありならレベルを+2する【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L137-L145】。
- 関連設定：`oceanRiverSwampRefillChance`（基礎補充率）、`infiniteWaterBiomeNonConsumeChance`（非消費率）、`rainRefillChance`（雨時の補充率併用）、`fastBiomeRefillAtSeaLevelOnly`（高度制約）【F:common/src/main/java/traben/flowing_fluids/config/FFConfig.java†L28-L74】【F:common/src/main/java/traben/flowing_fluids/config/FFCommands.java†L399-L447】。

## 蒸発ロジック詳細
### 通常蒸発 `ff$tryEvaporate`
- 成功条件：`chance < evaporationChanceV2` かつ「水量が `getDropOff(level)` 以下」「直下が空気（流入源が無い）」【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L152-L161】。
- 効果：対象水ブロックを空気に置き換え。主にレベル1の水たまり掃除を想定【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L152-L159】。

### ネザー蒸発 `ff$tryEvaporateNether`
- 成功条件：`chance < evaporationNetherChance` かつバイオームがネザータグを持つ【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L165-L179】。
- 効果：水量1なら即消滅（空気化）、それ以上なら3レベル減少。オーバーワールドより高速な蒸発を実現【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L168-L175】。

## デフォルト値と設計意図
- `evaporationChanceV2` / `evaporationNetherChance` ともにデフォルト 1.0（100%）で、対象条件を満たすと毎回蒸発処理を試みる設定【F:common/src/main/java/traben/flowing_fluids/config/FFConfig.java†L28-L33】【F:common/src/main/java/traben/flowing_fluids/config/FFCommands.java†L56-L71】。
- `rainRefillChance` デフォルト 0.3（30%）、`oceanRiverSwampRefillChance` デフォルト 1.0（100%）で、降雨や無限バイオームを通じて時間経過とともに補充される【F:common/src/main/java/traben/flowing_fluids/config/FFConfig.java†L28-L33】。
- 雨補充率を蒸発率の1/3以下に抑制することで「雨で無限増殖し、世界が水で満たされる」事故を防ぐ設計になっている【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L99-L105】。
- 無限バイオームでは「海面で水を吸う」動きと「海面/直下で水を足す」動きの両方が存在し、過剰流入や枯渇のバランスを取る【F:common/src/main/java/traben/flowing_fluids/mixin/MixinWaterFluid.java†L120-L145】。

## 運用メモ
- 目視テストの際は、雨天・海面・ネザーバイオームなど条件ごとに時間を進めてランダムティック挙動を観察すると差異を確認しやすいです。
- 蒸発／補充率を大きく変更する場合は、`rainRefillChance` を蒸発率の1/3以下に抑える仕様を念頭に計算する必要があります（設定値が高くても内部で制限されるため）。
- バイオーム補充が働かない場合は「高度条件」「天空光」「対象バイオームタグ」の3点をまず確認してください。
