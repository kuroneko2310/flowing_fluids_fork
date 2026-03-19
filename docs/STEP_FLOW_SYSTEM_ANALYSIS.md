# 段差フローシステム 詳細解析

## 概要

flowing_fluids mod の段差フローシステムは、水が段差（階段状の地形）を検出して適切に流れ落ちるための複雑な判定ロジックを持つ。このドキュメントでは、システムの動作原理、問題点、および改善案を詳述する。

---

## システム構成図

```
┌─────────────────────────────────────────────────────────────────┐
│                     メイン tick 処理                              │
│                  (MixinFlowingFluid.spread)                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              1. 下方向フロー (checkAndFlowDown)                   │
│   - 真下への流れを優先的に処理                                     │
│   - 下が満水なら remainingAmount を返す                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              2. 水量による分岐                                     │
│   remainingAmount > dropOff(1)?                                  │
│   ├─ YES → flowToSides() 横方向フロー                             │
│   └─ NO  → flowToEdges 処理（薄い水の段差探索）                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (flowToEdges = true の場合)
┌─────────────────────────────────────────────────────────────────┐
│              3. 薄い水の段差探索                                   │
│   ├─ getImmediateThinEdgeDrop() - 即座の端検出                    │
│   ├─ shouldSuppressThinCapDrift() - 抑制判定                      │
│   └─ getLowestSpreadableLookingFor4BlockDrops() - 深い探索        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              4. 深い探索 (Deep Spread Search)                     │
│   ├─ shouldSuppressExploratorySpread() - 探索抑制判定             │
│   └─ getValidDirectionFromDeepSpreadSearch() - BFS探索            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 主要関数の詳細解説

### 1. `hasImmediateDownwardOutlet()`

**役割**: 真下1マスに水が落ちられるか判定

**ロジック**:
```java
private boolean hasImmediateDownwardOutlet(Level level, BlockPos pos,
        Fluid sourceFluid, int sourceAmount) {
    BlockPos belowPos = pos.below();
    BlockState belowState = level.getBlockState(belowPos);
    FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);

    // 下に流れられるか？
    if (!canSpreadToOptionallySameOrEmpty(..., Direction.DOWN, ...)) {
        return false;
    }

    // 下が空、異種流体、または自分より少ない水量なら outlet あり
    return belowFluid.isEmpty()
        || !belowFluid.getType().isSame(sourceFluid)
        || belowFluid.getAmount() < sourceAmount;
}
```

**問題点**:
- **真下1マスしか見ない** - 横に1歩進んだ先の段差を検出できない
- 階段状の地形で水が「固まる」原因の一つ

---

### 2. `hasNearbyStepDownOutlet()` (新規追加)

**役割**: 横1歩 + 下1マスの段差を検出

**ロジック**:
```java
private boolean hasNearbyStepDownOutlet(Level level, BlockPos pos,
        Fluid sourceFluid, int sourceAmount) {
    // まず真下をチェック
    if (hasImmediateDownwardOutlet(level, pos, sourceFluid, sourceAmount)) {
        return true;
    }

    // 4方向の横マスをチェック
    for (Direction dir : Direction.Plane.HORIZONTAL) {
        BlockPos sidePos = pos.relative(dir);

        // 横に流れられるか？
        if (!canSpreadToOptionallySameOrEmpty(...)) {
            continue;
        }

        // 横のマスの真下に落ちられるか？
        if (hasImmediateDownwardOutlet(level, sidePos, sourceFluid,
                Math.max(1, sourceAmount - 1))) {
            return true;
        }
    }

    return false;
}
```

**改善効果**:
- 1段の階段を検出可能に
- 段差端での「固まり」を大幅に軽減

**残存問題**:
- 2段以上の段差は依然として検出困難
- 横2歩 + 下のようなケースは未対応

---

### 3. `shouldSuppressThinCapDrift()`

**役割**: 薄い水（1-2レベル）の無駄な横流れを抑制

**ロジック**:
```java
private boolean shouldSuppressThinCapDrift(Level level, BlockPos pos,
        FluidState fluidState, int amount) {
    // 水以外は対象外
    if (!fluidState.is(FluidTags.WATER)) return false;

    // 薄い水（dropOff以下）のみ対象
    if (amount <= 0 || amount > getDropOff(level)) return false;

    // ★重要: 段差出口があるなら抑制しない
    if (hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount)) {
        return false;
    }

    // 小さなクラスターは安定化
    if (isSmallSupportedThinSurfaceCluster(...)) return true;

    // 上に水があるなら流動継続
    if (hasFluidAbove(level, pos, fluidState.getType())) return false;

    // 下が満水で、周囲も満水なら安定化
    FluidState belowFluid = getEffectiveFluidState(level, pos.below(), ...);
    if (belowFluid.getAmount() < 8) return false;

    int supportedBaseNeighbors = 0;
    for (Direction dir : Direction.Plane.HORIZONTAL) {
        FluidState baseNeighbor = getEffectiveFluidState(level, belowPos.relative(dir), ...);
        if (baseNeighbor.getAmount() >= 8) {
            supportedBaseNeighbors++;
        }
    }

    return supportedBaseNeighbors >= 3;  // 3方向以上が満水なら抑制
}
```

**設計意図**:
- 広い水面上の薄い水が無駄に動き回るのを防ぐ
- パフォーマンス最適化（不要な tick を削減）

**問題点**:
- `supportedBaseNeighbors >= 3` の条件が厳しすぎる場合がある
- 「広い水面の端」でも段差があれば流れるべきだが、判定順序によっては抑制されてしまう

---

### 4. `shouldSuppressExploratorySpread()`

**役割**: 深い探索（slope search）を行うかどうかの判定

**ロジック**:
```java
private boolean shouldSuppressExploratorySpread(Level level, BlockPos pos,
        FluidState fluidState, int amount) {
    // 水以外は対象外
    if (!fluidState.is(FluidTags.WATER)) return false;

    // 薄いクラスターは早期抑制
    if (amount <= getDropOff(level) && isSmallSupportedThinSurfaceCluster(...)) {
        return true;
    }

    // WaterFlowProfile による抑制判定
    WaterFlowProfile waterProfile = getWaterFlowProfile(level, pos, fluidState, amount);
    if (waterProfile.shouldSuppressExploratorySpread()) return true;
    if (waterProfile.isPressureDriven()) return false;  // 圧力駆動なら探索継続

    // 川バイオームは抑制しない
    if (FFFluidUtils.isRiverBiome(level.getBiome(pos))) return false;

    // 広い水面の処理
    if (isBroadSurfaceWater(level, pos, fluidState, amount)) {
        if (AdaptiveTickScheduler.isFlowActiveNow(level, pos)) return false;
        if (hasImmediateSurfaceEdge(level, pos, fluidState.getType())) return false;
        return !hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount);
    }

    // 海/ビーチバイオームでの特別処理
    boolean broadWaterBiome = isOceanBiome(biome) || isBeachBiome(biome);
    int maxExploratoryAmount = broadWaterBiome ? 6 : 4;
    if (amount > maxExploratoryAmount) return false;

    // 上に水があるなら探索継続
    if (hasFluidAbove(level, pos, fluidState.getType())) return false;

    // ★重要: 段差出口があるなら抑制しない
    if (hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount)) {
        return false;
    }

    // 安定度チェック
    if (AdaptiveTickScheduler.getPoolStableTicks(level, pos, 20) < 4) return false;

    // 下の支持チェック
    if (!supportedBelow) return false;

    // 横方向ルート数による判定
    int routeCount = countSpreadableHorizontalRoutes(level, pos, ...);
    if (routeCount >= 3) return true;  // 3方向以上開いていれば抑制
    return routeCount == 2 && getPoolStableTicks(...) >= 8;  // 2方向で8tick安定なら抑制
}
```

**設計意図**:
- 海や広い湖での不要な探索を削減
- パフォーマンス最適化

**問題点**:
- 条件が複雑すぎて、意図しない抑制が発生しやすい
- `routeCount` による判定が段差検出と競合する場合がある

---

### 5. `getLowestSpreadableLookingFor4BlockDrops()`

**役割**: 最も低い方向を探索し、4ブロック先までの落下点を検出

**ロジック概要**:
```java
private Direction getLowestSpreadableLookingFor4BlockDrops(
        Level level, BlockPos blockPos, FluidState fluidState,
        int amount, boolean requiresSlope) {

    Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(level.random);
    Direction[] validDirections = new Direction[4];
    int[] neighbourAmounts = new int[4];
    int validCount = 0;

    // 1. 各方向の水量をチェック
    for (Direction direction : shuffled) {
        BlockPos sidePos = blockPos.relative(direction);
        FluidState sideFluid = getEffectiveFluidState(level, sidePos, ...);

        if (canSpreadToOptionallySameOrEmpty(...)) {
            // 2レベル以上低ければ即座に選択
            if (sideFluid.getAmount() <= amount - forcedDifference) {
                return direction;  // 早期リターン
            }
            validDirections[validCount] = direction;
            neighbourAmounts[validCount] = sideFluid.getAmount();
            validCount++;
        }
    }

    // 2. 有効な方向がなければ null
    if (validCount == 0) return null;
    if (validCount == 1) return validDirections[0];

    // 3. 水量でソート（低い順）
    // ... ソート処理 ...

    // 4. 深い探索
    if (requiresSlope && shouldSuppressExploratorySpread(...)) {
        return null;  // 探索抑制
    }

    return getValidDirectionFromDeepSpreadSearch(level, blockPos, ...);
}
```

**問題点**:
- `requiresSlope` が true の時、`shouldSuppressExploratorySpread()` で抑制されると探索が行われない
- 深い探索の前に抑制判定が入るため、近くの段差を見逃す可能性

---

### 6. `checkAndFlowDown()`

**役割**: 下方向への流れを処理

**重要なロジック - Retention（保持）**:
```java
// 下が空気の場合、全量を落とさず一部を保持
if (fluidDownAmount == 0 && actualStateDown.isAir() && amountDestCanAccept == amount) {
    if (hasRetentionAnchor(level, blockPos, fluidState)) {
        int retained = getDropOff(level);  // 通常は1

        // WaterFlowProfile による保持緩和
        if (waterProfile != null) {
            retained = Math.max(0, retained - waterProfile.getDownwardRetentionRelief());
        }

        amountDestCanAccept = amount - retained;  // 保持分を残す
        retainedMinimum = true;
    }
}
```

**設計意図**:
- 滝の上流が干上がるのを防ぐ
- 上流での横方向平衡化を維持

**問題点**:
- 段差の端で保持が働くと、「落ちきらず、横にも行かず」の状態になりやすい
- `hasRetentionAnchor()` の条件が広すぎる可能性

---

## データフロー図

```
水の tick 発生
      │
      ▼
┌────────────────┐
│ checkAndFlowDown│
└────────────────┘
      │
      ├─ 下に流れた → 終了
      │
      ▼ (remainingAmount > 0)
┌────────────────────────────────────────┐
│ remainingAmount > dropOff(1)?          │
├────────────────────────────────────────┤
│ YES: flowToSides()                     │
│      └─ 横方向に水を分配               │
│                                        │
│ NO:  flowToEdges 処理                  │
│      ├─ getImmediateThinEdgeDrop()    │
│      │   └─ 即座の端を検出             │
│      │                                 │
│      ├─ shouldSuppressThinCapDrift()? │
│      │   ├─ YES: 安定化して終了       │
│      │   └─ NO: 深い探索へ            │
│      │                                 │
│      └─ getLowestSpreadable...()      │
│          ├─ shouldSuppressExploratory?│
│          │   └─ YES: null を返す      │
│          │                             │
│          └─ getValidDirection...()    │
│              └─ BFS で探索            │
└────────────────────────────────────────┘
```

---

## 現在の問題点まとめ

### 問題1: 段差検出の深度不足

**症状**: 2段以上の階段で水が止まる

**原因**: `hasNearbyStepDownOutlet()` は横1歩 + 下1マスしか見ない

**改善案**:
```java
// 横2歩までチェックする拡張版
private boolean hasNearbyStepDownOutlet2(Level level, BlockPos pos,
        Fluid sourceFluid, int sourceAmount) {
    if (hasImmediateDownwardOutlet(...)) return true;

    for (Direction dir : Direction.Plane.HORIZONTAL) {
        BlockPos step1 = pos.relative(dir);
        if (!canSpreadTo(..., step1, ...)) continue;

        // 1歩目で下に落ちられるか
        if (hasImmediateDownwardOutlet(level, step1, ...)) return true;

        // 2歩目をチェック
        BlockPos step2 = step1.relative(dir);
        if (!canSpreadTo(..., step2, ...)) continue;
        if (hasImmediateDownwardOutlet(level, step2, ...)) return true;
    }
    return false;
}
```

---

### 問題2: 抑制条件の過剰

**症状**: 段差近くでも水が流れない

**原因**: 複数の抑制関数が重なって、正当なフローを止めている

**改善案**:
1. 抑制関数の優先順位を明確化
2. 段差検出を抑制判定の「前」に行う
3. 抑制条件を緩和するフラグを追加

```java
// 例: 強制フロー条件の追加
private boolean shouldForceFlow(Level level, BlockPos pos, FluidState fluidState) {
    // 近くに明確な出口があるなら強制フロー
    if (hasNearbyStepDownOutlet2(level, pos, fluidState.getType(), fluidState.getAmount())) {
        return true;
    }
    // 水位差が大きいなら強制フロー
    if (hasStrongLevelDifference(level, pos, 3)) {
        return true;
    }
    return false;
}
```

---

### 問題3: 保持（Retention）と横流れの競合

**症状**: 段差の端で水が「保留」状態になる

**原因**: `checkAndFlowDown()` での保持と、その後の横探索抑制が両方適用される

**改善案**:
```java
// 保持が働いた場合は横探索を強制する
if (retainedMinimum) {
    // 横に段差があるなら保持を解除
    if (hasNearbyStepDownOutlet(level, blockPos, fluidState.getType(), amount)) {
        // 保持分も含めて下に流す
        amountDestCanAccept = amount;
        retainedMinimum = false;
    }
}
```

---

### 問題4: WaterFlowProfile との整合性

**症状**: プロファイルによる抑制と段差検出が競合

**原因**: `WaterFlowProfile.shouldSuppressExploratorySpread()` が true を返すと、段差検出の結果が無視される

**改善案**:
```java
// shouldSuppressExploratorySpread 内での順序変更
WaterFlowProfile waterProfile = getWaterFlowProfile(...);

// 先に段差チェック
if (hasNearbyStepDownOutlet(...)) {
    return false;  // 段差があれば抑制しない
}

// その後にプロファイルチェック
if (waterProfile.shouldSuppressExploratorySpread()) {
    return true;
}
```

---

## 推奨される修正優先順位

### 優先度1: 即効性のある修正

1. **`hasNearbyStepDownOutlet()` の拡張**
   - 横2歩までの検出を追加
   - L字型の段差を検出

2. **抑制条件の見直し**
   - `shouldSuppressThinCapDrift()` で `supportedBaseNeighbors >= 3` を `>= 4` に緩和
   - または段差検出を条件の最上位に移動

### 優先度2: 構造的な改善

3. **段差検出の一元化**
   - `StepDownDetector` クラスの作成
   - キャッシュ機構の導入

4. **保持ロジックの改善**
   - 段差近くでの保持を緩和
   - `WaterFlowProfile` との統合

### 優先度3: パフォーマンス最適化

5. **検出結果のキャッシュ**
   - 段差検出は高コストなので結果をキャッシュ
   - tick 間で再利用

---

## テストケース

### テスト1: 1段階段
```
水水水
■■□    ← 1段下がり
  ■■■

期待: 水が右に流れて落ちる
```

### テスト2: 2段階段
```
水水水
■■□
  ■□    ← 2段下がり
    ■■■

期待: 水が右に流れて2段落ちる
```

### テスト3: L字段差
```
水水水
■■■□
    □   ← L字型
    ■■■

期待: 水が右に流れて、さらに右下に落ちる
```

### テスト4: 広い水面の端
```
水水水水水水水水
■■■■■■■■□
            ■■■

期待: 右端の水が段差から落ちる（中央の水は安定）
```

---

## 適用済み修正

### 修正1: 水位2-3での段差検出漏れ (2026-03-18)

**問題**: `shouldSuppressStablePoolTransfer()` と `shouldSuppressShallowFlatTransfer()` で `hasImmediateDownwardOutlet()` のみ使用しており、横1歩先の段差を検出できていなかった。

**症状**: 水位2-3の水が段差から流れない

**修正内容**:
1. `shouldSuppressStablePoolTransfer()` に `hasNearbyStepDownOutlet()` チェックを追加
2. `shouldSuppressShallowFlatTransfer()` の両方のケースで `hasImmediateDownwardOutlet()` を `hasNearbyStepDownOutlet()` に置換

**修正箇所**:
- `MixinFlowingFluid.java` 行 1384-1388 (broadSurface ケース)
- `MixinFlowingFluid.java` 行 1434-1436 (broadSurface 内)
- `MixinFlowingFluid.java` 行 1453-1455 (non-broadSurface ケース)

---

## 残存する潜在的問題

### 問題A: 2段以上の階段

`hasNearbyStepDownOutlet()` は横1歩 + 下1マスしか見ないため、2段以上の階段では依然として問題が発生する可能性がある。

**改善案**: `hasNearbyStepDownOutlet2()` を実装し、横2歩までの検出を追加。

### 問題B: L字型段差

横に1歩、さらに別方向に1歩進んでから落ちるケースは現在検出できない。

### 問題C: パフォーマンス

`hasNearbyStepDownOutlet()` は最大4方向 × 2回のブロック状態取得を行うため、高頻度で呼ばれるとパフォーマンスに影響する可能性がある。

**改善案**: 結果をキャッシュし、tick間で再利用。

---

## 結論

段差フローシステムは複数の抑制ロジックとバランスを取りながら動作しているが、条件の複雑さから意図しない動作が発生しやすい。主な改善点は:

1. **段差検出の深度を増やす** - 横2歩までの検出
2. **抑制条件の優先順位を明確化** - 段差があれば常にフロー
3. **保持ロジックの改善** - 段差近くでは保持を緩和
4. **キャッシュの導入** - パフォーマンス維持

これらの修正により、階段状の地形での水の自然な流れが改善されると期待される。
