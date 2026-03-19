# flowing_fluids mod 改善TODO

## Phase 1: 即座に対応すべき項目 (高優先度)

### 1.1 設定値の検証と自動補正
- [ ] `FFConfig.java` に設定値の整合性チェック機構を追加
  - `maxWaterFlowDistance >= waterFlowDistance` の検証
  - `bfsMaxSearchDistance` と `waterFlowDistance` の関連性チェック
  - 無効な組み合わせを検出して警告ログ出力

### 1.2 キャッシュ管理の統一 (CacheSupervisor)
- [ ] 複数の並行データ構造を統一管理するクラスを導入
  - `AdaptiveTickScheduler`
  - `FluidSpatialGrid`
  - `ChunkLocalSlopeCache`
  - `FluidActivityTracker`
- [ ] `clearDimension()` の一元化でリソースリーク防止

### 1.3 例外ハンドリングの改善
- [ ] `catch(Exception e)` を例外型別ハンドラに分離
- [ ] `InterruptedException`, `RejectedExecutionException` の適切な処理
- [ ] `finally`/`try-with-resources` で ExecutorService の確実なクリーンアップ

### 1.4 マルチスレッド管理の統一
- [ ] `ParallelExecutionManager` クラスの導入
  - `EnhancedFluidBFS`
  - `ParallelFluidTickManager`
  - `RainWaterSystem`
  の ExecutorService を一元管理

---

## Phase 2: 短期的な改善 (中優先度)

### 2.1 テストカバレッジの拡充 (現在 ~26% → 目標 50%+)
- [ ] `AdaptiveTickScheduler` の equilibrium index 計算テスト
- [ ] `EnhancedFluidBFS` の方向キャッシュ機構テスト
- [ ] `ParallelFluidTickManager` の chunk graph coloring テスト
- [ ] ディメンション切り替え、チャンク unload のエッジケーステスト

### 2.2 設定プリセットシステム
- [ ] プリセット定義の実装
  - `BALANCED` - バランス型 (デフォルト)
  - `PERFORMANCE` - パフォーマンス重視
  - `REALISM` - リアリズム重視
  - `MINIMAL` - 最小負荷
- [ ] プリセット選択後のカスタマイズ対応

### 2.3 アーキテクチャドキュメントの整備
- [ ] システム設計図 (Mermaid/PlantUML)
  - クラス図
  - データフロー図
  - スレッド間通信図
- [ ] パフォーマンスチューニングガイド (ユーザー向け)

### 2.4 コマンドUXの改善
- [ ] シンプルなコマンド形式の対応
  ```
  /flowing_fluids config set waterFlowDistance 6
  /flowing_fluids preset PERFORMANCE
  /flowing_fluids status
  ```

### 2.5 ホットリロード機構
- [ ] ゲーム中の設定変更を即座に反映するUI
- [ ] `saveConfig()` 後の自動 `refreshFluidRuntime()`

---

## Phase 3: 中期的な改善 (中〜低優先度)

### 3.1 コードの重複削減
- [ ] `FFConfig.java` の encode/decode 自動化
  - リフレクションまたはコード生成で160+行を削減
- [ ] 水位レベル計算の単一実装への統一
- [ ] `ThreadLocalBufferPool` でテンポラリバッファを集約

### 3.2 イベントシステムの体系化
- [ ] `EventCoordinator` の導入
  - `DryingEventSystem`
  - `FloodEventSystem`
  - `RainWaterSystem`
  の相互作用を管理

### 3.3 動的品質スケーリング (DynamicQualityScaler)
- [ ] TPS低下時の自動調整
  - `waterFlowDistance` の縮小
  - `bfsMaxSearchDistance` の縮小
  - BFS予算の動的スケーリング

### 3.4 未追跡ファイルの整理
- [ ] `WaterFlowProfile.java` の統合または削除判定
- [ ] `drying/` ディレクトリの統合または削除判定
- [ ] `docs/` 内の作業ファイルの整理・アーカイブ

### 3.5 デバッグフラグの整理
- [ ] 以下のフラグをロギング機構に統合または削除
  - `debug_killFluidUpdatesUntilTime`
  - `waterPluggedThisSession`
  - `pistonTick`

---

## Phase 4: 長期的な改善 (低優先度)

### 4.1 パフォーマンス最適化
- [ ] BFS予算の動的スケーリング強化
- [ ] `FluidSpatialGrid` の適応的解像度
- [ ] `ChunkLocalSlopeCache` の LRU セカンダリキャッシュ層
- [ ] BFS初期化のオブジェクトプーリング

### 4.2 パフォーマンスモニタリング
- [ ] HUD/GUIでの統計表示
  - BFS実行数
  - 平均探索ノード数
  - tick処理時間
- [ ] 設定アドバイザシステム

### 4.3 APIドキュメントの整備
- [ ] `FlowingFluidsAPI` の javadoc 完備
- [ ] 外部mod連携方法の明示化
- [ ] トラブルシューティングガイドの統一

### 4.4 ハードコード定数の集約
- [ ] `ConfigurableConstants` クラスで一元管理
  - `DEFAULT_DIRECTIONS`
  - `CARDINAL_SHUFFLE_PATTERNS`
  - `MAX_MOMENTUM_BONUS`
  - その他マジックナンバー

---

## 完了済み

- [x] 雨水の即時tick予約 (`ff$wakeRainFluid`)
- [x] 段差落下検出 (`hasNearbyStepDownOutlet`)
- [x] River biome の特別扱い
- [x] Virtual waterlog 統一
- [x] Rain chunk cache key クラッシュ防止
- [x] River inlet zone 特別扱い
- [x] SUBTERRANEAN_POOL の macro scheduling 制限

---

## メモ

### 現在の主要システム構成
```
AdaptiveTickScheduler - tick 遅延と平衡状態の管理
EnhancedFluidBFS - 幅優先探索による流体平衡化
ParallelFluidTickManager - 並列tick処理
FluidSpatialGrid - 空間分割によるクエリ最適化
ChunkLocalSlopeCache - チャンク単位の傾斜キャッシュ
WaterFlowProfile - 流動プロファイル判定
RainWaterSystem - 雨水システム
DryingEventSystem - 乾燥/蒸発システム
```

### 設定項目数
- FFConfig.java: 160+ 項目
- カテゴリ分けが不十分で、関連性が暗黙的
