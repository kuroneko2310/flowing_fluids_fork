# Create Basin + Pipez bulk fill memo

## Symptoms

- Pipez の fluid pipe で Basin へ水や溶岩を入れると、`1000mB + 500mB` のように同じ流体が 2 枠へ割れて見えることがあった。
- その状態だと Basin / Mechanical Mixer の処理開始が不安定になり、バケツ入力より挙動が崩れて見えた。

## Cause

- Pipez は `fluid_pipe.amount` 分を 1 回の `fill(...)` でまとめて送り込む。
- このインスタンスの `pipez-server.toml` では `no_upgrade = 1500` なので、無強化でも 1500mB を一括で送る。
- Create の `CombinedTankWrapper` は `enforceVariety` が有効でも、同じ `fill` 呼び出しの途中で最初のタンクが埋まったあと、そのまま次の空タンクへ同じ流体を続けて入れてしまう。
- Basin の入力タンクは「同じ流体を 2 枠へ分けない」前提の作りなので、Pipez の大口転送でこの前提が崩れていた。

## Fix

- Forge の Create 連携で、`enforceVariety` 付きタンクへの `fill(...)` を補正した。
- 既に同じ流体が入っているタンクがあるときは、そのタンクだけへ詰める。
- 同じ流体がまだ無いときも、1 回の `fill(...)` では最初に受けられる 1 タンク分だけを受ける。
- これで Pipez の 1500mB 一括転送でも Basin が `1000 + 500` に割れず、残りは次回転送へ残る。

## Avoid next time

- Create の `enforceVariety` は「既に同じ流体があるか」だけでなく、「同一 fill 呼び出しで同じ流体を増殖させない」まで見ないと、高スループット配管で崩れる。
- Pipez のように 1000mB 超を 1 回で投げる配管互換では、1 バケツ前提の handler をそのまま信用しない。
