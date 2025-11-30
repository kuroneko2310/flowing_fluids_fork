# Flowing Fluids 現行仕様サマリー

本ドキュメントは、Flowing Fluids モッドの主要な機能や処理の仕様を簡潔に把握できるようにまとめたものです。設定コマンドの例も併記しているので、詳細は `/flowing_fluids help` を参照してください。

## 基本挙動
- **有限流体**: 水・溶岩などの流体は量が有限で、流し続けると枯渇します。オーシャン・リバー・スワンプは緩やかに補充される特殊な無限水源です。
- **流れの拡散**: 流体は周囲や斜面に広がり、プール状に平衡化しようとします。`max_water_flow_distance` や `bfs_max_search_distance` で流れの距離・探査範囲を調整できます。
- **低レベル水の蒸発/補充**: 雨やバイオーム条件で部分的な水ブロックが再補充され、ネザーや地上では小さな水たまりが蒸発します。
- **ブロック置換**: 可能な限り流体を失わないよう、ブロック設置時は流体が周囲に押し出されます。

## 流量・距離の高度設定
- `advanced_flow_distances` グループで地形に応じた距離を細かく制御できます。
  - 例: 川だけ距離を伸ばす場合 `/flowing_fluids settings behaviour advanced_flow_distances river_flow_distance 64`
  - 探索負荷を抑えるための距離別最適化（`enable_distance_based_optimization`）や性能ログ（`performance_monitoring`）も提供。
    - 0–4 ブロックは毎tick、5–16 ブロックは 2tick 毎、17–32 ブロックは 4tick 毎、33–64 ブロックは 8tick 毎、65+ ブロックは 10tick 毎に更新頻度を落として負荷を平準化。プレイヤーが 32 ブロック以内にいれば常に毎tickで再計算されます。

### 水路・排水路で水位が残りやすい場合の対処
- 遠い部分の水位が 2–3 で停滞する場合は、平衡化探索の射程と水路距離を広げると解消しやすくなります。
- 推奨例（運河・排水路向け）:
  ```
  /flowing_fluids settings behaviour advanced_flow_distances canal_flow_distance 48
  /flowing_fluids settings behaviour advanced_flow_distances bfs_max_search_distance 32
  /flowing_fluids settings behaviour advanced_flow_distances enable_distance_based_optimization on
  ```
- ポイント:
  - `bfs_max_search_distance` を伸ばすと、出口から離れたブロックまで平均化が届きやすくなります。
  - `canal_flow_distance` を伸ばすと、流れが途切れず出口へ水量が分配されます。
  - パフォーマンスが気になる場合は `enable_distance_based_optimization` をオンのままにし、数値を段階的に調整してください。

## 装置・ゲームプレイ要素
- **ピストンポンプ**: ピストンを使って流体を上方へ自動搬送できます。
- **バケツ・瓶**: バケツは1–8レベルの流体を吸い上げ/配置でき、瓶は2–3レベル消費して充填します。
- **水管理要素**: 耕地の水分補給や動物の繁殖で水を消費する等、周辺の流体残量に依存する挙動があります。

## コマンドと設定
- 主要コマンド: `/flowing_fluids help`、`/flowing_fluids settings`。全機能はサーバーコマンド経由でオン/オフや数値調整が可能。
- 無効化: `/flowing_fluids settings enable_mod on|off` でいつでもバニラ動作に戻せます。停止時は満水ブロックがバニラソースとして保存され、未満ブロックはバニラの流れに戻るためワールド破損はありません。
- 対応流体の選択: `/flowing_fluids settings ignored_fluids add <fluid_name>` で物理対象から除外可能。

## 対応・互換性
- **モッド流体**: 特別な性質を持つ場合を除き、自動で有限流体の挙動が適用されます。必要ならブラックリスト化が可能。
- **Create Mod 連携**: パイプやホースプーリーで有限流体を搬送し、水車は流量やバイオーム条件に応じて回転要件を変えられます（`create_compat` 設定）。
- **クライアント/サーバー構成**: サーバーのみ必須。クライアント導入で視覚同期が改善されます。

## パフォーマンス配慮
- チャンク生成後に露出流体を塞ぐ処理で初期負荷を軽減します。
- サーバー負荷が高い場合は `waterTickDelay` を 4–8 付近まで延ばすことで流体更新頻度を抑えられます。
- 侵食的な流れ（大量排水など）は不可避の負荷があるため、距離設定や最適化オプションで調整してください。

## ビジュアル
- バニラ準拠の流体レンダリングでシェーダーと互換。流れテクスチャの非表示や流体高さの描画変更、最小レベルの段差挙動など、見た目をコマンドで切り替えられます。
