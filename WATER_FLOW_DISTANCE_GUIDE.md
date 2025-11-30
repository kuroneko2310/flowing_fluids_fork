# 水流距離調整機能 使用ガイド

## 概要

このガイドでは、Flowing Fluidsモッドの高度な水流距離調整機能の使用方法を説明します。
この機能により、水の流れる距離を細かく制御し、パフォーマンスとリアリズムのバランスを取ることができます。

---

## 新しい設定オプション

### 基本設定

#### `maxWaterFlowDistance` (デフォルト: 16)
- **説明**: 水が水平方向に流れることができる最大距離（ブロック単位）
- **範囲**: 1-256
- **影響**: 大きいほど水が遠くまで流れますが、パフォーマンスに影響
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 16
  ```

#### `bfsMaxSearchDistance` (デフォルト: 20)
- **説明**: BFS（幅優先探索）アルゴリズムが水の平衡化を探索する最大距離
- **範囲**: 4-128
- **影響**: 大きいほど複雑な水流パターンを正確に処理しますが、CPU使用率が上昇
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 32
  ```

**長距離向け最適化のポイント**:
- `maxWaterFlowDistance` を伸ばしても、探索予算は 50% までしか絞られないため、湖や長い運河の排水が途中で止まりにくくなります。
- BFS の水平補助探索は流動距離に応じて自動で拡張され、遠方の水位も平均化に巻き込みやすくなりました。
- 入口が狭い排水路では `inlet_probe_max_steps` を 6–12 にすると、勾配方向に軽量な直線探査を挟み、3ブロック程度で詰まる挙動を軽減できます。

#### `slopeFindDistanceMultiplier` (デフォルト: 1.0)
- **説明**: 傾斜を見つけるための探索距離の倍率
- **範囲**: 0.5-3.0
- **影響**: 大きいほど水が低い場所を見つけやすくなりますが、計算コストが増加
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances slope_find_distance_multiplier 1.5
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
  /flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance on
  ```

#### `riverFlowDistance` (デフォルト: 64)
- **説明**: 川バイオームでの水流距離
- **範囲**: 4-256
- **注意**: `enableAdaptiveFlowDistance`がtrueの時のみ有効
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances river_flow_distance 128
  ```

#### `oceanFlowDistance` (デフォルト: 128)
- **説明**: 海洋バイオームでの水流距離
- **範囲**: 4-512
- **注意**: 海は最適化が強く適用されるため、高い値でもパフォーマンス影響は限定的
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances ocean_flow_distance 256
  ```

#### `canalFlowDistance` (デフォルト: 32)
- **説明**: 人工的な水路（運河）での水流距離
- **範囲**: 4-128
- **検出**: 平地に水がある場合、自動的に運河と判定
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances canal_flow_distance 64
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
  /flowing_fluids settings behaviour performance_monitoring enable_performance_monitoring on
  ```

#### `performanceLogInterval` (デフォルト: 200)
- **説明**: パフォーマンスデータをログに出力する間隔（tick単位、20 tick = 1秒）
- **範囲**: 20-1200
- **例**: 10秒毎にログ出力
  ```
  /flowing_fluids settings behaviour performance_monitoring performance_log_interval 200
  ```

#### `enableDistanceBasedOptimization` (デフォルト: true)
- **説明**: 流れ元からの推定距離に応じて水流の更新頻度を段階的に下げる階層的最適化。近くは毎tick、遠くほど間引きます。
- **動作仕様**:
  - 距離別の更新間隔（flowDistance は流路上のブロック距離を指します）:
    - 0–4 ブロック: 毎tick
    - 5–16 ブロック: 2tick に 1 回
    - 17–32 ブロック: 4tick に 1 回
    - 33–64 ブロック: 8tick に 1 回
    - 65 ブロック以上: 10tick に 1 回
  - いずれの距離でも、プレイヤーが 32 ブロック以内にいる場合は常に毎tick更新に昇格。
  - 更新タイミングは座標ハッシュで散らし、同じ距離帯の水流が同時に大量更新しないよう負荷を平準化。
  - `bfsMaxSearchDistance` や `riverFlowDistance` など距離拡張系の設定と組み合わせても安全にスケールさせることを目的としています。
- **メリット**:
  - 64 ブロックを超える長距離水路で 50–70% の tick 削減を実現しつつ、近景の見た目や操作感は維持。
  - プレイヤー接近時は即座に通常頻度に戻るため、探索や建築時の遅延を抑制。
- **使いどころと注意**:
  - 運河・排水路など長距離で流れを維持したい場合はオン推奨。`canalFlowDistance` や `bfs_max_search_distance` を伸ばす際の前提設定として想定されています。
  - 水量変化をフレーム単位で厳密に観測したい検証用途ではオフにすると全距離毎tick更新になります（負荷増に注意）。
- **例**:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
  ```

---

## 使用例とシナリオ

### シナリオ 1: バニラライクな体験（軽量）

サーバーへの負荷を最小限に抑えながら、バニラに近い動作を実現:

```
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 6
/flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 12
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance off
/flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
```

**期待される効果**:
- TPS影響: ほぼゼロ
- 見た目: バニラに近い
- メモリ使用量: 低

---

### シナリオ 2: リアルな川と海（推奨）

川や海で自然な水流を実現しつつ、パフォーマンスも維持:

```
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 16
/flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 24
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance on
/flowing_fluids settings behaviour advanced_flow_distances river_flow_distance 64
/flowing_fluids settings behaviour advanced_flow_distances ocean_flow_distance 128
/flowing_fluids settings behaviour advanced_flow_distances canal_flow_distance 32
/flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
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
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 32
/flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 48
/flowing_fluids settings behaviour advanced_flow_distances slope_find_distance_multiplier 2.0
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance on
/flowing_fluids settings behaviour advanced_flow_distances river_flow_distance 128
/flowing_fluids settings behaviour advanced_flow_distances ocean_flow_distance 256
/flowing_fluids settings behaviour advanced_flow_distances canal_flow_distance 64
/flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
/flowing_fluids settings behaviour performance_monitoring enable_performance_monitoring on
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
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 4
/flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 8
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance off
/flowing_fluids settings behaviour tick_delays__aka__flow_speeds water 3
/flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
```

**期待される効果**:
- TPS影響: ほぼゼロ
- 見た目: シンプル
- 安定性: 非常に高い

---

## パフォーマンスモニタリングの使い方

### 1. モニタリングを有効化

```
/flowing_fluids settings behaviour performance_monitoring enable_performance_monitoring on
/flowing_fluids settings behaviour performance_monitoring performance_log_interval 200
```

### 2. ゲームをプレイ

通常通りゲームをプレイし、水を配置したり、川や海を探索します。

### 3. パフォーマンスデータを確認

ゲーム内で以下のコマンドを使用して、現在のパフォーマンス統計を表示できます:

```
/flowing_fluids settings behaviour performance_monitoring show_stats
```

データをリセットするには:

```
/flowing_fluids settings behaviour performance_monitoring reset_stats
```

### 4. ログを確認

サーバーログまたはゲームログに以下のような情報が出力されます:

```
=== 流体パフォーマンスモニター レポート ===
総流体tick数: 15,423
総tick時間: 234.56 ms (平均: 15.210 μs/tick)
BFS操作回数: 3,842 (tick数の 24.92%)
BFS時間: 156.23 ms (平均: 40.652 μs/操作)
BFS訪問ノード数: 234,521 (平均: 61.0 ノード/操作)
最大BFS深度: 32 ブロック
使用された最大流動距離: 16 ブロック

距離別パフォーマンス:
  距離 4: 8,234 ticks (53.40%), 平均時間: 8.234 μs
  距離 8: 4,123 ticks (26.73%), 平均時間: 18.456 μs
  距離 16: 2,056 ticks (13.33%), 平均時間: 35.678 μs
  距離 32: 1,010 ticks (6.55%), 平均時間: 72.345 μs

高速パスヒット: 9,234 (85.67%)
低速パスヒット: 1,543 (14.33%)
平衡スキップ: 5,678 (tick数の 26.91% 削減)
空間グリッドヒット率: 78.45% (12,456 ヒット, 3,421 ミス)
=====================================
```

### 5. 設定を調整

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
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 16
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance on
```

---

### 問題: TPSが低下している

**原因**: 流動距離が大きすぎる、または最適化が無効

**解決策**:
```
/flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 8
/flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 16
```

---

### 問題: 川の水が不自然に動く

**原因**: 適応型距離が無効、またはBFS探索距離が不足

**解決策**:
```
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance on
/flowing_fluids settings behaviour advanced_flow_distances river_flow_distance 64
/flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 20
```

---

### 問題: 海が重い

**原因**: 海洋距離が大きすぎる

**解決策**:
```
/flowing_fluids settings behaviour advanced_flow_distances ocean_flow_distance 64
/flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
```
海は自動的に最適化が適用されますが、それでも重い場合は距離を減らしてください。

---

## コマンド一覧

### 高度な流動距離設定

すべての高度な流動距離設定は以下のパスでアクセスできます:
```
/flowing_fluids settings behaviour advanced_flow_distances [option] [value]
```

| コマンド | 説明 | 範囲 | デフォルト |
|---------|------|------|-----------|
| `max_water_flow_distance` | 最大水平流動距離 | 1-256 | 8 |
| `bfs_max_search_distance` | BFS最大探索距離 | 4-128 | 16 |
| `slope_find_distance_multiplier` | 傾斜探索距離の倍率 | 0.5-3.0 | 1.0 |
| `enable_adaptive_flow_distance` | 適応型距離調整 | on/off | on |
| `river_flow_distance` | 川での流動距離 | 4-256 | 64 |
| `ocean_flow_distance` | 海での流動距離 | 4-512 | 128 |
| `canal_flow_distance` | 運河での流動距離 | 4-128 | 32 |
| `enable_distance_based_optimization` | 距離ベース最適化 | on/off | on |

**使用例**:
```
# 最大流動距離を16ブロックに設定
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance 16

# 適応型距離調整を有効化
/flowing_fluids settings behaviour advanced_flow_distances enable_adaptive_flow_distance on

# 川の流動距離を128ブロックに設定
/flowing_fluids settings behaviour advanced_flow_distances river_flow_distance 128
```

---

### パフォーマンスモニタリング

すべてのパフォーマンスモニタリング設定は以下のパスでアクセスできます:
```
/flowing_fluids settings behaviour performance_monitoring [option] [value]
```

| コマンド | 説明 | 範囲 | デフォルト |
|---------|------|------|-----------|
| `enable_performance_monitoring` | パフォーマンス追跡を有効化 | on/off | off |
| `performance_log_interval` | ログ出力間隔（tick） | 20-1200 | 200 |
| `show_stats` | 現在の統計を表示 | - | - |
| `reset_stats` | 統計をリセット | - | - |

**使用例**:
```
# パフォーマンスモニタリングを有効化
/flowing_fluids settings behaviour performance_monitoring enable_performance_monitoring on

# ログ間隔を5秒（100 tick）に設定
/flowing_fluids settings behaviour performance_monitoring performance_log_interval 100

# 現在の統計を表示
/flowing_fluids settings behaviour performance_monitoring show_stats

# 統計をリセット
/flowing_fluids settings behaviour performance_monitoring reset_stats
```

---

### クイックリファレンス

現在の設定値を確認するには、値を指定せずにコマンドを実行します:
```
/flowing_fluids settings behaviour advanced_flow_distances max_water_flow_distance
```

すべての設定をデフォルトにリセットするには:
```
/flowing_fluids settings reset_all_to_defaults
```

---

## 高度な使用法

### 設定ファイルの直接編集

設定ファイル `config/flowing_fluids.json` を直接編集することもできます:

```json
{
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
