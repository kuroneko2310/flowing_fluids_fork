# 水流距離調整機能 使用ガイド

## 概要

このガイドでは、Flowing Fluidsモッドの高度な水流距離調整機能の使用方法を説明します。
この機能により、水の流れる距離を細かく制御し、パフォーマンスとリアリズムのバランスを取ることができます。

---

## 新しい設定オプション

### 基本設定

#### `maxWaterFlowDistance` (デフォルト: 8)
- **説明**: 水が水平方向に流れることができる最大距離（ブロック単位）
- **範囲**: 1-256
- **影響**: 大きいほど水が遠くまで流れますが、パフォーマンスに影響
- **例**:
  ```
  /flowing_fluids settings maxWaterFlowDistance 16
  ```

#### `bfsMaxSearchDistance` (デフォルト: 16)
- **説明**: BFS（幅優先探索）アルゴリズムが水の平衡化を探索する最大距離
- **範囲**: 4-128
- **影響**: 大きいほど複雑な水流パターンを正確に処理しますが、CPU使用率が上昇
- **例**:
  ```
  /flowing_fluids settings bfsMaxSearchDistance 32
  ```

#### `slopeFindDistanceMultiplier` (デフォルト: 1.0)
- **説明**: 傾斜を見つけるための探索距離の倍率
- **範囲**: 0.5-3.0
- **影響**: 大きいほど水が低い場所を見つけやすくなりますが、計算コストが増加
- **例**:
  ```
  /flowing_fluids settings slopeFindDistanceMultiplier 1.5
  ```

---

### 適応型距離設定

#### `enableAdaptiveFlowDistance` (デフォルト: true)
- **説明**: 地形タイプに応じて自動的に流動距離を調整
- **効果**:
  - 川: より長距離の流れ
  - 海: 非常に長距離だが最適化された流れ
  - 運河: 中距離の流れ
  - 平地/山: 標準距離
- **例**:
  ```
  /flowing_fluids settings enableAdaptiveFlowDistance true
  ```

#### `riverFlowDistance` (デフォルト: 64)
- **説明**: 川バイオームでの水流距離
- **範囲**: 4-256
- **注意**: `enableAdaptiveFlowDistance`がtrueの時のみ有効
- **例**:
  ```
  /flowing_fluids settings riverFlowDistance 128
  ```

#### `oceanFlowDistance` (デフォルト: 128)
- **説明**: 海洋バイオームでの水流距離
- **範囲**: 4-512
- **注意**: 海は最適化が強く適用されるため、高い値でもパフォーマンス影響は限定的
- **例**:
  ```
  /flowing_fluids settings oceanFlowDistance 256
  ```

#### `canalFlowDistance` (デフォルト: 32)
- **説明**: 人工的な水路（運河）での水流距離
- **範囲**: 4-128
- **検出**: 平地に水がある場合、自動的に運河と判定
- **例**:
  ```
  /flowing_fluids settings canalFlowDistance 64
  ```

---

### パフォーマンスモニタリング

#### `enablePerformanceMonitoring` (デフォルト: false)
- **説明**: 詳細なパフォーマンスデータの収集を有効化
- **用途**:
  - 設定のチューニング
  - パフォーマンス問題のデバッグ
  - 最適な設定値の発見
- **注意**: 有効にするとわずかにオーバーヘッドが発生
- **例**:
  ```
  /flowing_fluids settings enablePerformanceMonitoring true
  ```

#### `performanceLogInterval` (デフォルト: 200)
- **説明**: パフォーマンスデータをログに出力する間隔（tick単位、20 tick = 1秒）
- **範囲**: 20-1200
- **例**: 10秒毎にログ出力
  ```
  /flowing_fluids settings performanceLogInterval 200
  ```

#### `enableDistanceBasedOptimization` (デフォルト: true)
- **説明**: 距離に基づく階層的最適化を有効化
- **効果**:
  - 遠距離の水流は低頻度で更新
  - 50-70%のtick削減（64ブロック以上の距離で）
  - 視覚的な影響は最小限
- **例**:
  ```
  /flowing_fluids settings enableDistanceBasedOptimization true
  ```

---

## 使用例とシナリオ

### シナリオ 1: バニラライクな体験（軽量）

サーバーへの負荷を最小限に抑えながら、バニラに近い動作を実現:

```
/flowing_fluids settings maxWaterFlowDistance 6
/flowing_fluids settings bfsMaxSearchDistance 12
/flowing_fluids settings enableAdaptiveFlowDistance false
/flowing_fluids settings enableDistanceBasedOptimization true
```

**期待される効果**:
- TPS影響: ほぼゼロ
- 見た目: バニラに近い
- メモリ使用量: 低

---

### シナリオ 2: リアルな川と海（推奨）

川や海で自然な水流を実現しつつ、パフォーマンスも維持:

```
/flowing_fluids settings maxWaterFlowDistance 16
/flowing_fluids settings bfsMaxSearchDistance 24
/flowing_fluids settings enableAdaptiveFlowDistance true
/flowing_fluids settings riverFlowDistance 64
/flowing_fluids settings oceanFlowDistance 128
/flowing_fluids settings canalFlowDistance 32
/flowing_fluids settings enableDistanceBasedOptimization true
```

**期待される効果**:
- TPS影響: 小
- 見た目: 非常にリアル
- 川が自然に流れる
- 海の波動が広範囲に伝わる

---

### シナリオ 3: 超リアル（ハイエンド）

最高のリアリズムを求める高性能サーバー向け:

```
/flowing_fluids settings maxWaterFlowDistance 32
/flowing_fluids settings bfsMaxSearchDistance 48
/flowing_fluids settings slopeFindDistanceMultiplier 2.0
/flowing_fluids settings enableAdaptiveFlowDistance true
/flowing_fluids settings riverFlowDistance 128
/flowing_fluids settings oceanFlowDistance 256
/flowing_fluids settings canalFlowDistance 64
/flowing_fluids settings enableDistanceBasedOptimization true
/flowing_fluids settings enablePerformanceMonitoring true
```

**期待される効果**:
- TPS影響: 中（最適化により軽減）
- 見た目: 極めてリアル
- 複雑な水流パターンの再現
- 大規模な河川システムが機能

---

### シナリオ 4: パフォーマンス最優先

古いハードウェアや多人数サーバー向け:

```
/flowing_fluids settings maxWaterFlowDistance 4
/flowing_fluids settings bfsMaxSearchDistance 8
/flowing_fluids settings enableAdaptiveFlowDistance false
/flowing_fluids settings waterTickDelay 3
/flowing_fluids settings enableDistanceBasedOptimization true
```

**期待される効果**:
- TPS影響: ほぼゼロ
- 見た目: シンプル
- 安定性: 非常に高い

---

## パフォーマンスモニタリングの使い方

### 1. モニタリングを有効化

```
/flowing_fluids settings enablePerformanceMonitoring true
/flowing_fluids settings performanceLogInterval 200
```

### 2. ゲームをプレイ

通常通りゲームをプレイし、水を配置したり、川や海を探索します。

### 3. ログを確認

サーバーログまたはゲームログに以下のような情報が出力されます:

```
=== Fluid Performance Monitor Report ===
Total fluid ticks: 15,423
Total tick time: 234.56 ms (avg: 15.210 μs/tick)
BFS operations: 3,842 (24.92% of ticks)
BFS time: 156.23 ms (avg: 40.652 μs/op)
BFS nodes visited: 234,521 (avg: 61.0 nodes/op)
Max BFS depth reached: 32 blocks
Max flow distance used: 16 blocks

Performance by distance:
  Distance 4: 8,234 ticks (53.40%), avg time: 8.234 μs
  Distance 8: 4,123 ticks (26.73%), avg time: 18.456 μs
  Distance 16: 2,056 ticks (13.33%), avg time: 35.678 μs
  Distance 32: 1,010 ticks (6.55%), avg time: 72.345 μs

Fast path hits: 9,234 (85.67%)
Slow path hits: 1,543 (14.33%)
Equilibrium skips: 5,678 (saved 26.91% of ticks)
Spatial grid hit rate: 78.45% (12,456 hits, 3,421 misses)
=====================================
```

### 4. 設定を調整

データを元に設定を微調整します:

- **BFS使用率が高い (> 30%)**: `bfsMaxSearchDistance`を減らす
- **平均tick時間が高い (> 20μs)**: `maxWaterFlowDistance`を減らす
- **Fast path率が低い (< 80%)**: 地形が複雑な可能性、`enableAdaptiveFlowDistance`を有効化
- **Equilibrium skip率が低い (< 20%)**: `enableDistanceBasedOptimization`を有効化

---

## トラブルシューティング

### 問題: 水が予想より遠くまで流れない

**原因**: 設定値が小さすぎる可能性

**解決策**:
```
/flowing_fluids settings maxWaterFlowDistance 16
/flowing_fluids settings enableAdaptiveFlowDistance true
```

---

### 問題: TPSが低下している

**原因**: 流動距離が大きすぎる、または最適化が無効

**解決策**:
```
/flowing_fluids settings enableDistanceBasedOptimization true
/flowing_fluids settings maxWaterFlowDistance 8
/flowing_fluids settings bfsMaxSearchDistance 16
```

---

### 問題: 川の水が不自然に動く

**原因**: 適応型距離が無効、またはBFS探索距離が不足

**解決策**:
```
/flowing_fluids settings enableAdaptiveFlowDistance true
/flowing_fluids settings riverFlowDistance 64
/flowing_fluids settings bfsMaxSearchDistance 24
```

---

### 問題: 海が重い

**原因**: 海洋距離が大きすぎる

**解決策**:
```
/flowing_fluids settings oceanFlowDistance 64
/flowing_fluids settings enableDistanceBasedOptimization true
```
海は自動的に最適化が適用されますが、それでも重い場合は距離を減らしてください。

---

## 高度な使用法

### 設定ファイルの直接編集

設定ファイル `config/flowing_fluids.json` を直接編集することもできます:

```json
{
  "waterFlowDistance": 4,
  "maxWaterFlowDistance": 16,
  "bfsMaxSearchDistance": 24,
  "slopeFindDistanceMultiplier": 1.0,
  "enableAdaptiveFlowDistance": true,
  "riverFlowDistance": 64,
  "oceanFlowDistance": 128,
  "canalFlowDistance": 32,
  "enablePerformanceMonitoring": false,
  "performanceLogInterval": 200,
  "enableDistanceBasedOptimization": true
}
```

編集後、サーバーまたはゲームを再起動してください。

---

## パフォーマンスとリアリズムのバランス

以下の表は、設定とその影響の関係を示しています:

| 設定値 | TPS影響 | リアリズム | メモリ | 推奨用途 |
|--------|---------|-----------|--------|----------|
| 距離: 4 | ★☆☆☆☆ | ★★☆☆☆ | 低 | 低スペックサーバー |
| 距離: 8 | ★★☆☆☆ | ★★★☆☆ | 中 | 一般的なサーバー |
| 距離: 16 | ★★★☆☆ | ★★★★☆ | 中 | 高性能サーバー |
| 距離: 32 | ★★★★☆ | ★★★★★ | 高 | ハイエンドサーバー |
| 距離: 64+ | ★★★★★ | ★★★★★ | 高 | シングルプレイ/実験 |

**注**: `enableDistanceBasedOptimization`が有効な場合、影響は1-2段階軽減されます。

---

## まとめ

- **デフォルト設定**: ほとんどのサーバーに適しています
- **パフォーマンス重視**: 距離を小さく、最適化を有効に
- **リアリズム重視**: 適応型距離を有効にし、地形別に設定
- **モニタリング**: 最適な設定を見つけるために活用

詳細なパフォーマンス分析については、`PERFORMANCE_ANALYSIS.md` を参照してください。

---

**更新日**: 2025-11-21
**バージョン**: 1.0
