# 水流距離とパフォーマンスの分析と最適化案

## 概要

このドキュメントは、水流距離がパフォーマンスに与える影響を分析し、さらなる最適化のアイデアを提案します。

---

## 1. 距離によるパフォーマンス影響の分析

### 1.1 計算量の増加パターン

水流距離が増加すると、以下の計算量が影響を受けます:

| 距離 | BFS探索ノード数 (理論値) | 処理時間 (予測) | メモリ使用量 |
|------|------------------------|----------------|------------|
| 4ブロック | ~100ノード | 基準 (100%) | 基準 |
| 8ブロック | ~400ノード | 200-300% | 150% |
| 16ブロック | ~1,600ノード | 500-800% | 250% |
| 32ブロック | ~6,400ノード | 1,500-2,500% | 500% |
| 64ブロック | ~25,600ノード | 5,000-10,000% | 1,200% |
| 128ブロック | ~100,000ノード | 15,000-30,000% | 3,000% |

**計算根拠:**
- BFS探索は距離の2乗に比例して増加 (2D平面の場合)
- 垂直方向を含めると距離の3乗に近づく
- キャッシュミス率の増加により、実際の処理時間は理論値より悪化

### 1.2 既存の最適化システムの効果

現在実装されている最適化システム:

#### A. Adaptive Tick Scheduler (60-80%削減)
- **平衡状態スキップ**: 安定した水流はtickをスキップ
- **距離の影響**: 長距離になると平衡到達が遅れ、効果が低下
  - 4ブロック: 80%削減
  - 16ブロック: 60%削減
  - 64ブロック: 30-40%削減 (予測)

#### B. Spatial Grid (60-80%のBFS削減)
- **現在の実装**: 16×16×16セルの3層構造
- **距離の影響**:
  - グリッドサイズが固定のため、長距離では効率低下
  - 64ブロック超では、複数チャンクにまたがりキャッシュミス増加
  - **提案**: 距離に応じて動的にグリッド解像度を調整

#### C. Parallel Fluid Tick Manager (2-4倍高速化)
- **マルチコア活用**: 非隣接チャンクを並列処理
- **距離の影響**:
  - 長距離流動は隣接チャンク数が増え、並列化効率低下
  - 4ブロック: 4倍高速化
  - 64ブロック: 2倍程度に低下 (予測)

#### D. Chunk Local Slope Cache (15-30%削減)
- **LRUキャッシュ**: 64エントリ/チャンク
- **距離の影響**:
  - 長距離ではキャッシュヒット率低下
  - **提案**: 距離に応じてキャッシュサイズを動的調整

---

## 2. 新しい最適化アイデア

### 2.1 階層的距離管理 (Hierarchical Distance Management)

**アイデア**: 水流距離を階層的に管理し、遠距離は低頻度で更新

```java
// 疑似コード
if (distance <= 4) {
    updateEveryTick();  // 毎tick更新
} else if (distance <= 16) {
    updateEvery(2);     // 2tick毎
} else if (distance <= 32) {
    updateEvery(5);     // 5tick毎
} else {
    updateEvery(10);    // 10tick毎
}
```

**効果予測**:
- 64ブロック距離で50-70%のtick削減
- 視覚的な影響は最小限 (遠方の水は気づかれにくい)

---

### 2.2 適応型BFS深度制限 (Adaptive BFS Depth Limiting)

**問題**: 現在のBFS探索は最大距離まで常に探索

**解決策**: 地形タイプと流体量に応じて動的に探索深度を調整

```java
int calculateOptimalBFSDepth(TerrainType terrain, int fluidAmount) {
    int baseBudget = config.bfsMaxSearchDistance;

    // 地形による調整
    float terrainMultiplier = switch(terrain) {
        case OCEAN -> 0.5f;      // 海は広いが単純
        case RIVER -> 1.5f;      // 川は複雑な流れ
        case CANAL -> 1.2f;      // 運河は人工的
        case MOUNTAIN -> 0.8f;   // 山は垂直方向が主
        default -> 1.0f;
    };

    // 流体量による調整 (少量の水は短距離)
    float amountMultiplier = fluidAmount / 8.0f;

    return (int)(baseBudget * terrainMultiplier * amountMultiplier);
}
```

**効果予測**:
- 平均BFSノード数を30-50%削減
- 地形に応じた最適化で自然な流れを維持

---

### 2.3 遅延伝播システム (Lazy Propagation System)

**アイデア**: 遠距離の水流変化を即座に反映せず、バッチで処理

**実装**:
1. **変更キュー**: 距離16ブロック超の変更をキューに蓄積
2. **バッチ処理**: 10-20tick毎にまとめて処理
3. **優先度付け**: プレイヤーに近い変更を優先

```java
class LazyPropagationQueue {
    PriorityQueue<FluidUpdate> queue;

    void addUpdate(BlockPos pos, int newLevel, int distance) {
        int priority = calculatePriority(pos, distance);
        queue.offer(new FluidUpdate(pos, newLevel, priority));
    }

    void processBatch(int maxUpdates) {
        for (int i = 0; i < maxUpdates && !queue.isEmpty(); i++) {
            FluidUpdate update = queue.poll();
            applyUpdate(update);
        }
    }
}
```

**効果予測**:
- CPU負荷を時間軸で分散: ピーク負荷を50-60%削減
- メモリ使用量: 約10-20%増加 (許容範囲)

---

### 2.4 空間分割による局所性最適化 (Spatial Partitioning)

**問題**: 長距離流動では関係のない領域まで探索

**解決策**: 流動方向を予測し、探索範囲を円錐形に制限

```java
// 重力と地形の傾斜から流動方向を予測
Vec3 predictFlowDirection(BlockPos pos) {
    Vec3 gradient = calculateTerrainGradient(pos);
    Vec3 gravity = new Vec3(0, -1, 0);
    return gradient.add(gravity).normalize();
}

// 円錐形の探索範囲
boolean shouldExplore(BlockPos current, BlockPos target, Vec3 flowDir) {
    Vec3 toTarget = new Vec3(target.subtract(current));
    double angle = Math.acos(flowDir.dot(toTarget.normalize()));
    return angle < Math.PI / 3; // 60度の円錐
}
```

**効果予測**:
- 探索ノード数を40-60%削減
- 特に下り坂や河川で効果的

---

### 2.5 予測的平衡検出 (Predictive Equilibrium Detection)

**アイデア**: 機械学習/ヒューリスティックで平衡状態を早期予測

**実装**:
```java
class EquilibriumPredictor {
    // 過去N tickの変化量を追跡
    private Deque<Float> recentChanges = new ArrayDeque<>();

    boolean willReachEquilibrium(BlockPos pos, int lookAhead) {
        // 変化量の減衰率を計算
        float decayRate = calculateDecayRate(recentChanges);

        // 今後lookAhead tick後の変化量を予測
        float predictedChange = getCurrentChange() *
            Math.pow(decayRate, lookAhead);

        // 閾値以下なら平衡と判断
        return predictedChange < EQUILIBRIUM_THRESHOLD;
    }
}
```

**効果予測**:
- 平衡到達を平均3-5tick早く検出
- 不要なtickを10-15%削減

---

### 2.6 GPU加速による大規模流体計算 (GPU-Accelerated Fluid Simulation)

**対象**: 64ブロック以上の超長距離流動

**アプローチ**:
- OpenCL/VulkanによるGPUコンピュート活用
- 大量の流体ブロックを並列計算
- CPUとGPUのハイブリッド処理

**実装の考慮事項**:
```
CPU処理:
- 距離 < 16ブロック: 既存の最適化で十分高速
- プレイヤー周辺: レイテンシ重視

GPU処理:
- 距離 >= 64ブロック: 並列性が高い
- 海洋/大河川: 大量のブロックを一括処理
- バックグラウンド更新: レイテンシ許容
```

**効果予測**:
- 超長距離(128ブロック+)で10-20倍の高速化
- 実装コスト: 高 (プラットフォーム互換性の問題)

---

### 2.7 動的品質調整 (Dynamic Quality Scaling)

**アイデア**: FPS/TPS に応じて自動的に流体品質を調整

```java
class DynamicQualityScaler {
    private static final int TARGET_TPS = 20;
    private MovingAverage tpsHistory = new MovingAverage(100);

    void adjustQuality() {
        float avgTPS = tpsHistory.getAverage();

        if (avgTPS < TARGET_TPS * 0.8) {
            // パフォーマンス低下時
            config.maxWaterFlowDistance *= 0.9;
            config.bfsMaxSearchDistance *= 0.9;
            config.waterTickDelay += 1;
        } else if (avgTPS > TARGET_TPS * 0.95) {
            // 余裕がある時
            config.maxWaterFlowDistance = Math.min(
                config.maxWaterFlowDistance * 1.05,
                DEFAULT_MAX_DISTANCE
            );
        }
    }
}
```

**効果予測**:
- TPS維持率: 95%以上
- プレイヤー体験: 自動調整で常に最適

---

## 3. 最適化の優先順位と実装計画

### フェーズ1: 即座に実装可能 (低コスト、高効果)

1. **階層的距離管理** (実装コスト: 低, 効果: 高)
   - 既存コードへの追加が容易
   - 効果が確実に見込める

2. **適応型BFS深度制限** (実装コスト: 中, 効果: 高)
   - 地形検出ロジックの追加が必要
   - BFSシステムの修正

3. **予測的平衡検出** (実装コスト: 低, 効果: 中)
   - 既存の平衡検出システムの拡張

### フェーズ2: 中期実装 (中コスト、高効果)

4. **遅延伝播システム** (実装コスト: 中, 効果: 高)
   - 新しいキューシステムの実装
   - 既存tickロジックとの統合

5. **空間分割最適化** (実装コスト: 中, 効果: 中)
   - 流動方向予測の実装
   - BFS探索範囲の制限ロジック

6. **動的品質調整** (実装コスト: 中, 効果: 中)
   - パフォーマンスモニタリング統合
   - 自動調整ロジック

### フェーズ3: 長期実装 (高コスト、状況依存)

7. **GPU加速** (実装コスト: 高, 効果: 状況依存)
   - プラットフォーム互換性の問題
   - 超長距離流動のみで効果
   - ハイエンドサーバー向け

---

## 4. 推奨設定値

### 設定プリセット

#### プリセット1: バランス型 (推奨)
```json
{
  "waterFlowDistance": 4,
  "maxWaterFlowDistance": 8,
  "bfsMaxSearchDistance": 16,
  "enableAdaptiveFlowDistance": true,
  "riverFlowDistance": 32,
  "oceanFlowDistance": 64,
  "enableDistanceBasedOptimization": true
}
```
- **用途**: 一般的なサーバー
- **TPS影響**: 最小限
- **見た目**: 自然な水流

#### プリセット2: パフォーマンス重視
```json
{
  "waterFlowDistance": 3,
  "maxWaterFlowDistance": 6,
  "bfsMaxSearchDistance": 12,
  "enableAdaptiveFlowDistance": true,
  "riverFlowDistance": 16,
  "oceanFlowDistance": 32,
  "enableDistanceBasedOptimization": true,
  "waterTickDelay": 3
}
```
- **用途**: 低スペックサーバー
- **TPS影響**: ほぼゼロ
- **見た目**: やや単純化

#### プリセット3: リアリズム重視
```json
{
  "waterFlowDistance": 6,
  "maxWaterFlowDistance": 16,
  "bfsMaxSearchDistance": 32,
  "enableAdaptiveFlowDistance": true,
  "riverFlowDistance": 128,
  "oceanFlowDistance": 256,
  "enableDistanceBasedOptimization": true
}
```
- **用途**: ハイエンドサーバー/シングルプレイ
- **TPS影響**: 中程度 (最適化により軽減)
- **見た目**: 非常にリアル

---

## 5. ベンチマーク方法

### 測定シナリオ

1. **平地水源** (4×4の水源を設置)
   - 距離: 4, 8, 16, 32, 64ブロック
   - 測定: tick時間、BFSノード数、メモリ使用量

2. **急斜面** (山の上から水を流す)
   - 距離: 同上
   - 測定: 流下速度、CPU使用率

3. **運河** (長い直線水路)
   - 長さ: 100, 500, 1000ブロック
   - 測定: 平衡到達時間、総tick数

4. **海洋** (広大な水域)
   - サイズ: 100×100, 500×500ブロック
   - 測定: TPS影響、最大BFS深度

### 測定コマンド

```
/flowing_fluids performance start
/flowing_fluids performance log
/flowing_fluids performance reset
```

---

## 6. 結論

### 主要な発見

1. **距離とパフォーマンスは二次関数的に関係**
   - 2倍の距離 = 3-4倍の負荷

2. **既存の最適化は短距離に最適化**
   - 16ブロック以下: 優れた効率
   - 64ブロック以上: 効率大幅低下

3. **階層的アプローチが最も効果的**
   - 距離に応じた更新頻度調整
   - 地形タイプ別の最適化
   - 動的な品質調整

### 次のステップ

1. **フェーズ1の最適化を実装** (階層的距離管理、適応型BFS)
2. **ベンチマーク実施** (実測データ収集)
3. **設定プリセットの提供** (ユーザーが簡単に選択可能)
4. **継続的な改善** (フィードバックに基づく調整)

---

## 7. 参考資料

### 理論的背景
- **BFSアルゴリズム**: 時間計算量 O(V+E), V=ノード数, E=エッジ数
- **空間計算量**: O(d³), d=距離 (3次元空間)
- **キャッシュ効率**: L1/L2キャッシュサイズを超えると性能劣化

### 類似研究
- Minecraft水流最適化: Lithiumモッド (チャンク境界最適化)
- 流体力学: Navier-Stokes方程式の数値解法
- ゲーム物理: SPH (Smoothed Particle Hydrodynamics)

---

**作成日**: 2025-11-21
**バージョン**: 1.0
**次回更新**: ベンチマークデータ収集後
