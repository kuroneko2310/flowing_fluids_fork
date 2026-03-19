## Rain chunk cache key crash

- 症状:
  - ワールド終了時に `RainWaterSystem.onLevelUnload` から `NoClassDefFoundError: traben.flowing_fluids.rain.RainWaterSystem$ChunkCacheKey` が発生することがあった。
- 原因:
  - 雨システムの chunk キャッシュが `RainWaterSystem$ChunkCacheKey` という内側 record をキーにしていた。
  - 通常 tick では雨キャッシュに触れない条件もあるため、問題がレベルアンロード時の `removeIf` まで潜みやすかった。
  - 実行環境や jar の差し替え状況によっては、内側クラス解決が遅延して shutdown 時にだけ落ちる経路が残っていた。
- 修正:
  - chunk キャッシュキーを `dimension|packedChunkPos` の文字列へ平坦化した。
  - レベルごとの削除と統計取得も prefix 判定に統一して、アンロード時に内側型へ依存しないようにした。
- 今後の方針:
  - アンロードやメンテナンスで触る共有キャッシュは、なるべく単純な key 型を使う。
  - `NoClassDefFoundError` が shutdown 時だけ出るケースは、その場の例外処理ではなく「遅延解決される型依存」が残っていないかを先に疑う。
