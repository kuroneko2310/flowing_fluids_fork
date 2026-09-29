# 作業ルール

## ブランチと master への統合
- 作業ブランチで変更をコミット・プッシュしたら、必ず `master` にも統合する。作業ブランチに置いたままで終わらせない。
- 統合前に `git fetch origin master` で最新の master を取り込む。
  - 作業ブランチが master の先にあるだけなら fast-forward（`git push origin HEAD:master`）。
  - master が先に進んでいれば master を作業ブランチへマージし、ビルドとテストを通してから master へ反映する。
- master への反映前に、少なくとも `./gradlew :common:test`（Forge 1.20.1）が通ることを確認する。
  NeoForge 1.21.1 に影響する変更は `neoforge` ディレクトリで `./gradlew compileJava` も通す。
