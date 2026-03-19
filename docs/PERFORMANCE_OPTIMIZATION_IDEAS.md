# 段差フローシステム パフォーマンス最適化アイデア

## 現状分析

### 問題点
- `getBlockState()` / `getEffectiveFluidState()` が MixinFlowingFluid 内で **59回以上** 呼ばれている
- `hasNearbyStepDownOutlet()` は最大 **13回** のブロック状態取得を行う
  - 真下: 2回
  - 4方向 × (横1回 + 下2回) = 12回（最悪ケース）
- 同じ位置が複数の関数で繰り返し参照される

---

## 最適化アイデア

### 1. 段差検出結果のキャッシュ (効果: 高)

**現状**: 毎tick `hasNearbyStepDownOutlet()` を呼び出し

**改善案**: 結果をキャッシュし、ブロック変更時のみ無効化

```java
// AdaptiveTickScheduler に追加
private static final Long2ByteOpenHashMap stepDownCache = new Long2ByteOpenHashMap();
private static final byte CACHE_UNKNOWN = 0;
private static final byte CACHE_HAS_OUTLET = 1;
private static final byte CACHE_NO_OUTLET = 2;

public static boolean hasNearbyStepDownOutletCached(Level level, BlockPos pos,
        Fluid sourceFluid, int sourceAmount) {
    long key = pos.asLong();
    byte cached = stepDownCache.get(key);

    if (cached != CACHE_UNKNOWN) {
        return cached == CACHE_HAS_OUTLET;
    }

    // 実際の計算
    boolean result = computeHasNearbyStepDownOutlet(level, pos, sourceFluid, sourceAmount);
    stepDownCache.put(key, result ? CACHE_HAS_OUTLET : CACHE_NO_OUTLET);
    return result;
}

// ブロック変更時にキャッシュを無効化
public static void invalidateStepDownCache(BlockPos pos) {
    long key = pos.asLong();
    stepDownCache.remove(key);
    // 隣接も無効化
    for (Direction dir : Direction.values()) {
        stepDownCache.remove(pos.relative(dir).asLong());
    }
}
```

**期待効果**: 同じ位置への複数回呼び出しを1回に削減

---

### 2. ローカルブロック状態キャッシュ (効果: 高)

**現状**: 同じ tick 内で同じ位置の BlockState を複数回取得

**改善案**: tick 開始時にローカルキャッシュを作成

```java
// ThreadLocal で tick 単位のキャッシュ
private static final ThreadLocal<Long2ObjectOpenHashMap<BlockState>> BLOCK_CACHE =
    ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);
private static final ThreadLocal<Long2ObjectOpenHashMap<FluidState>> FLUID_CACHE =
    ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

@Unique
private BlockState ff$getCachedBlockState(Level level, BlockPos pos) {
    Long2ObjectOpenHashMap<BlockState> cache = BLOCK_CACHE.get();
    long key = pos.asLong();
    BlockState cached = cache.get(key);
    if (cached != null) {
        return cached;
    }
    BlockState state = level.getBlockState(pos);
    cache.put(key, state);
    return state;
}

// tick 終了時にクリア
@Unique
private void ff$clearTickCache() {
    BLOCK_CACHE.get().clear();
    FLUID_CACHE.get().clear();
}
```

**期待効果**: ブロック状態取得を 50-70% 削減

---

### 3. 早期リターンの最適化 (効果: 中)

**現状**: 条件チェックの順序が最適でない場合がある

**改善案**: 最も失敗しやすい条件を先にチェック

```java
// Before: 重い処理が先
private boolean shouldSuppressExploratorySpread(...) {
    WaterFlowProfile waterProfile = getWaterFlowProfile(...);  // 重い
    if (waterProfile.shouldSuppressExploratorySpread()) return true;
    if (hasNearbyStepDownOutlet(...)) return false;  // これが先にあるべき
    ...
}

// After: 軽い処理を先に
private boolean shouldSuppressExploratorySpread(...) {
    // 最も安価なチェックを先に
    if (!fluidState.is(FluidTags.WATER)) return false;
    if (amount <= 0) return false;

    // 段差チェック（キャッシュあり）を先に
    if (hasNearbyStepDownOutletCached(...)) return false;

    // 重い処理は最後に
    WaterFlowProfile waterProfile = getWaterFlowProfile(...);
    ...
}
```

---

### 4. ビットフラグによる状態圧縮 (効果: 中)

**現状**: 複数の boolean 状態を個別に管理

**改善案**: ビットフラグで圧縮

```java
// 状態フラグ定義
private static final int FLAG_HAS_STEP_DOWN = 1;
private static final int FLAG_HAS_SURFACE_EDGE = 2;
private static final int FLAG_IS_BROAD_SURFACE = 4;
private static final int FLAG_HAS_FLUID_ABOVE = 8;
private static final int FLAG_IS_RIVER_BIOME = 16;

// 一度の計算で全フラグを設定
@Unique
private int ff$computeFlowFlags(Level level, BlockPos pos, FluidState fluidState, int amount) {
    int flags = 0;

    if (hasNearbyStepDownOutlet(...)) flags |= FLAG_HAS_STEP_DOWN;
    if (hasImmediateSurfaceEdge(...)) flags |= FLAG_HAS_SURFACE_EDGE;
    if (isBroadSurfaceWater(...)) flags |= FLAG_IS_BROAD_SURFACE;
    if (hasFluidAbove(...)) flags |= FLAG_HAS_FLUID_ABOVE;
    if (FFFluidUtils.isRiverBiome(...)) flags |= FLAG_IS_RIVER_BIOME;

    return flags;
}

// 使用時
int flags = ff$computeFlowFlags(level, pos, fluidState, amount);
if ((flags & FLAG_HAS_STEP_DOWN) != 0) return false;  // 抑制しない
if ((flags & FLAG_IS_RIVER_BIOME) != 0) return false;
```

**期待効果**: 条件チェックの重複計算を削減

---

### 5. 軽量な段差検出 (効果: 高)

**現状**: `canSpreadToOptionallySameOrEmpty()` は複雑な判定を含む

**改善案**: 段差検出専用の軽量版を作成

```java
// 軽量版: 流れられるかの簡易チェック
@Unique
private boolean ff$canFlowToSimple(Level level, BlockPos from, BlockPos to, Fluid fluid) {
    BlockState toState = ff$getCachedBlockState(level, to);

    // 空気またはリプレース可能なら OK
    if (toState.isAir()) return true;
    if (toState.canBeReplaced(fluid)) return true;

    // 同じ流体で満水でないなら OK
    FluidState toFluid = toState.getFluidState();
    if (toFluid.getType().isSame(fluid) && toFluid.getAmount() < 8) return true;

    return false;
}

// 軽量な段差検出
@Unique
private boolean ff$hasNearbyStepDownFast(Level level, BlockPos pos, Fluid fluid) {
    // 真下チェック
    BlockPos below = pos.below();
    if (ff$canFlowToSimple(level, pos, below, fluid)) {
        return true;
    }

    // 4方向の横→下チェック
    for (Direction dir : Direction.Plane.HORIZONTAL) {
        BlockPos side = pos.relative(dir);
        if (!ff$canFlowToSimple(level, pos, side, fluid)) continue;

        BlockPos sideBelow = side.below();
        if (ff$canFlowToSimple(level, side, sideBelow, fluid)) {
            return true;
        }
    }

    return false;
}
```

**期待効果**: 段差検出を 40-60% 高速化

---

### 6. 空間分割による検索最適化 (効果: 中)

**現状**: 全ての水ブロックを個別に処理

**改善案**: チャンク単位で段差位置をプリ計算

```java
// チャンク単位の段差マップ
public class ChunkStepDownMap {
    // 16x16x16 のビットセット（段差があるかどうか）
    private final BitSet stepDownPositions = new BitSet(4096);

    // チャンクロード時にプリ計算
    public void computeForChunk(LevelChunk chunk) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos pos = new BlockPos(
                        chunk.getPos().getMinBlockX() + x,
                        y,
                        chunk.getPos().getMinBlockZ() + z
                    );
                    if (hasStepDownPotential(chunk, pos)) {
                        int index = (x << 8) | (z << 4) | (y & 0xF);
                        stepDownPositions.set(index);
                    }
                }
            }
        }
    }

    // O(1) で段差チェック
    public boolean hasStepDown(BlockPos pos) {
        int index = ((pos.getX() & 0xF) << 8) |
                    ((pos.getZ() & 0xF) << 4) |
                    (pos.getY() & 0xF);
        return stepDownPositions.get(index);
    }
}
```

**期待効果**: 段差検出を O(n) から O(1) に

---

### 7. 遅延評価パターン (効果: 中)

**現状**: 使わない可能性のある値も計算

**改善案**: 必要になるまで計算を遅延

```java
// 遅延評価ラッパー
private class LazyFlowContext {
    private final Level level;
    private final BlockPos pos;
    private final FluidState fluidState;
    private final int amount;

    // 遅延評価フィールド
    private Boolean hasStepDown = null;
    private Boolean hasSurfaceEdge = null;
    private WaterFlowProfile profile = null;

    public boolean hasNearbyStepDown() {
        if (hasStepDown == null) {
            hasStepDown = ff$hasNearbyStepDownFast(level, pos, fluidState.getType());
        }
        return hasStepDown;
    }

    public WaterFlowProfile getProfile() {
        if (profile == null) {
            profile = ff$getWaterFlowProfile(level, pos, fluidState, amount);
        }
        return profile;
    }
}
```

---

### 8. SIMD風バッチ処理 (効果: 高、実装難)

**現状**: 各位置を個別に処理

**改善案**: 近接する複数位置をまとめて処理

```java
// 4方向を一括でチェック
@Unique
private int ff$checkAllDirectionsFast(Level level, BlockPos pos, Fluid fluid) {
    int result = 0;

    // 一度に4方向のBlockStateを取得
    BlockState[] sideStates = new BlockState[4];
    BlockPos[] sidePositions = new BlockPos[4];

    int i = 0;
    for (Direction dir : Direction.Plane.HORIZONTAL) {
        sidePositions[i] = pos.relative(dir);
        sideStates[i] = level.getBlockState(sidePositions[i]);
        i++;
    }

    // 一括判定
    for (i = 0; i < 4; i++) {
        if (canFlowToSimple(sideStates[i], fluid)) {
            BlockPos below = sidePositions[i].below();
            BlockState belowState = level.getBlockState(below);
            if (canFlowToSimple(belowState, fluid)) {
                result |= (1 << i);  // この方向に段差あり
            }
        }
    }

    return result;
}
```

---

## 実装優先度

| アイデア | 効果 | 実装難度 | 優先度 |
|---------|------|---------|--------|
| 5. 軽量な段差検出 | 高 | 低 | ★★★★★ |
| 2. ローカルキャッシュ | 高 | 中 | ★★★★★ |
| 1. 段差結果キャッシュ | 高 | 中 | ★★★★☆ |
| 3. 早期リターン最適化 | 中 | 低 | ★★★★☆ |
| 7. 遅延評価 | 中 | 中 | ★★★☆☆ |
| 4. ビットフラグ | 中 | 中 | ★★★☆☆ |
| 6. 空間分割 | 中 | 高 | ★★☆☆☆ |
| 8. バッチ処理 | 高 | 高 | ★★☆☆☆ |

---

## 即座に適用可能な最適化

### A. hasNearbyStepDownOutlet の軽量化

```java
@Unique
private boolean flowing_fluids$hasNearbyStepDownOutletFast(Level level, BlockPos pos,
        Fluid sourceFluid, int sourceAmount) {
    // 1. 真下チェック（最も一般的なケース）
    BlockPos below = pos.below();
    BlockState belowState = level.getBlockState(below);
    if (belowState.isAir() || belowState.canBeReplaced(sourceFluid)) {
        return true;
    }
    FluidState belowFluid = belowState.getFluidState();
    if (belowFluid.getType().isSame(sourceFluid) && belowFluid.getAmount() < sourceAmount) {
        return true;
    }

    // 2. 横方向チェック（軽量版）
    BlockState stateAtPos = level.getBlockState(pos);
    for (Direction dir : Direction.Plane.HORIZONTAL) {
        BlockPos sidePos = pos.relative(dir);
        BlockState sideState = level.getBlockState(sidePos);

        // 横に流れられるか（簡易チェック）
        if (!sideState.isAir() && !sideState.canBeReplaced(sourceFluid)) {
            FluidState sideFluid = sideState.getFluidState();
            if (!sideFluid.getType().isSame(sourceFluid) || sideFluid.getAmount() >= sourceAmount) {
                continue;
            }
        }

        // 横の下チェック
        BlockPos sideBelowPos = sidePos.below();
        BlockState sideBelowState = level.getBlockState(sideBelowPos);
        if (sideBelowState.isAir() || sideBelowState.canBeReplaced(sourceFluid)) {
            return true;
        }
        FluidState sideBelowFluid = sideBelowState.getFluidState();
        if (sideBelowFluid.getType().isSame(sourceFluid) &&
            sideBelowFluid.getAmount() < Math.max(1, sourceAmount - 1)) {
            return true;
        }
    }

    return false;
}
```

### B. 条件チェックの最適化順序

```java
// shouldSuppressShallowFlatTransfer の最適化版
private boolean flowing_fluids$shouldSuppressShallowFlatTransferOptimized(...) {
    // 1. 最も安価なチェックを先に
    if (!sourceState.is(FluidTags.WATER)) return false;
    if (difference <= 0) return false;
    if (minimumRetainedAmount > 0) return false;

    // 2. 設定値チェック（メモリアクセスのみ）
    if (difference > 3) return false;
    if (sourceAmount > 3 || targetAmount > 2) return false;

    // 3. 河川バイオームチェック（キャッシュ可能）
    if (flowing_fluids$isRiverTransferZone(level, sourcePos, targetPos)) return false;

    // 4. 段差チェック（軽量版を使用）
    if (flowing_fluids$hasNearbyStepDownOutletFast(level, sourcePos, sourceState.getType(), sourceAmount)) {
        return false;
    }

    // 5. 重い処理は最後
    if (pressureHeadDelta > 0.75f) return false;
    ...
}
```

---

## 期待される改善効果

| 最適化 | 処理時間削減 | メモリ影響 |
|--------|-------------|-----------|
| 軽量段差検出 | 40-60% | なし |
| ローカルキャッシュ | 30-50% | +数KB/tick |
| 早期リターン | 10-20% | なし |
| 全体合計 | **50-70%** | +数KB/tick |
