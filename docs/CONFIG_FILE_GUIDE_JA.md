# Flowing Fluids 設定ファイルガイド

`config/flowing_fluids.json` では mod の全設定を管理できます。ここでは主要項目をセクション別に整理し、実際に編集しやすいサンプルを示します。Gson は未知のキーを無視するため、以下のように `_comment` を残しておく形でもそのまま利用可能です。

## 1. 基本挙動と性能
- `enableMod` / `enableDisplacement` / `enablePistonPushing`: コア機能のオンオフ。
- `waterFlowDistance` と `maxWaterFlowDistance`: 通常の水平流れ距離と上限。上限を伸ばすと探索コストが増えるため、`enableAdaptiveFlowDistance` と併用して調整すると安全です。
- `performanceLogInterval`, `enablePerformanceMonitoring`: パフォーマンス計測の頻度と有効化。

## 2. 降雨による補水
- `enableRainSystem`: 雨システム全体の有効/無効。
- `rainGenerateIntervalTicks`: 処理間隔（tick 単位）。
- `rainAttemptsPerChunk` / `rainMaxChunksPerTick`: 1 チャンクあたりの試行回数と 1 tick で処理するチャンク数上限。

## 3. 水圧システム
- `enableWaterPressure`: 木製ドアやフェンスゲートに水圧を与える機能のオンオフ。
- `waterPressureScanInterval`: 監視対象をスキャンする間隔。
- `waterPressureBreakThreshold`: 破損に必要な圧力。高くするほど壊れにくくなります。

## 4. 除外設定とブラックリスト
- `excludedDimensions`: ここに列挙したディメンションでは Flowing Fluids の挙動を無効化します。ID は `minecraft:the_nether` のように名前空間付きで記述してください。
- `fluidBlacklist`: 追加したくない流体 ID を列挙します。水圧や雨システムもこのリストを参照します。

## 5. フルサンプル（コメント付き）
実際の `flowing_fluids.json` を上書きして使えるサンプルです。デフォルト値に近い構成を基に、よく触る項目をまとめています。

```json
{
  "_comment": "=== 基本挙動と性能 ===",
  "enableMod": true,
  "enableDisplacement": true,
  "enablePistonPushing": true,
  "waterFlowDistance": 4,
  "maxWaterFlowDistance": 16,
  "bfsMaxSearchDistance": 20,
  "slopeFindDistanceMultiplier": 1.0,
  "enableAdaptiveFlowDistance": true,
  "riverFlowDistance": 64,
  "oceanFlowDistance": 128,
  "canalFlowDistance": 32,
  "enablePerformanceMonitoring": false,
  "performanceLogInterval": 200,
  "enableDistanceBasedOptimization": true,

  "_comment_rain": "=== 降雨システム ===",
  "enableRainSystem": true,
  "rainGenerateIntervalTicks": 20,
  "rainAttemptsPerChunk": 20,
  "rainBaseGenerateChance": 0.05,
  "rainMaxChunksPerTick": 16,

  "_comment_pressure": "=== 水圧システム ===",
  "enableWaterPressure": false,
  "waterPressureScanInterval": 20,
  "waterPressureScanAttempts": 4,
  "waterPressureBreakThreshold": 12.0,
  "waterPressureChunkRadius": 2,

  "_comment_limits": "=== 除外設定とブラックリスト ===",
  "excludedDimensions": [
    "minecraft:the_nether",
    "minecraft:the_end"
  ],
  "fluidBlacklist": [
    "minecraft:flowing_water"
  ]
}
```

### 編集のコツ
- **再起動**: ファイルを編集したらサーバー／クライアントを再起動して反映させてください。
- **段階的変更**: 距離や試行回数を大きく動かすと負荷が急増するため、少しずつ変更して挙動を確認するのが安全です。
- **除外設定の確認**: ディメンション除外が正しく効いていない場合は、ID の綴りと大文字小文字を再確認してください。`excludedDimensions` は mod 同期時にクライアントにも伝搬されます。

### さらに詳しく
水流距離の調整方針やパフォーマンスへの影響については `WATER_FLOW_DISTANCE_GUIDE.md` を参照してください。
