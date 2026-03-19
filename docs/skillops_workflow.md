# SkillOps Workflow

`tools/skillops/skill_ops.py` で、スキルを「作って終わり」じゃなくて、観察しながら育てる流れを回せるようにしたメモです。

## できること

### 1. Ingest

`SKILL.md` を走査して、次をカタログ化します。

- 目的
- タスクパターン
- 明示参照された関連スキル
- 説明文や本文の近さから見た関連スキル
- 現在の内容ハッシュ

例:

```powershell
python tools/skillops/skill_ops.py ingest `
  --skills-root C:\Users\ineko\.codex\skills `
  --output docs\skill_catalog.json
```

### 2. Observe

スキルを使うたびに 1 行ずつ JSONL に追記します。

```powershell
python tools/skillops/skill_ops.py observe `
  --run-log docs\skill_runs.jsonl `
  --catalog docs\skill_catalog.json `
  --skill skill-creator `
  --task "observe skill executions for analytics" `
  --status failure `
  --error "command not found: uv" `
  --feedback "fallback が分かりにくい"
```

`--catalog` を渡すと、その時点の `content_hash` を `skill_version` として自動で埋めます。

### 3. Inspect

失敗ログをまとめて、「どこがずれていそうか」をレポート化します。

```powershell
python tools/skillops/skill_ops.py inspect `
  --run-log docs\skill_runs.jsonl `
  --catalog docs\skill_catalog.json `
  --skill skill-creator `
  --output docs\skill_creator_inspect.md
```

主に見ます。

- 同じ失敗の繰り返し
- タスクパターンから外れた依頼の混入
- ツール前提漏れ
- 説明不足っぽい feedback

### 4. Amend

検査結果から、`description` の広げ方や本文への追記案を出します。

```powershell
python tools/skillops/skill_ops.py amend `
  --run-log docs\skill_runs.jsonl `
  --skill skill-creator `
  --skill-root C:\Users\ineko\.codex\skills `
  --output docs\skill_creator_amend.md
```

自動反映したいとき:

```powershell
python tools/skillops/skill_ops.py amend `
  --run-log docs\skill_runs.jsonl `
  --skill skill-creator `
  --skill-root C:\Users\ineko\.codex\skills `
  --apply `
  --history-root docs\skill_history
```

`--apply` を使うと、変更前の `SKILL.md` を `history-root` に退避してから、
安全寄りの加筆だけを反映します。

### 5. Evaluate

修正前後のバージョンを、成功率と失敗率で見比べます。

```powershell
python tools/skillops/skill_ops.py evaluate `
  --run-log docs\skill_runs.jsonl `
  --skill skill-creator `
  --baseline-version old123 `
  --candidate-version new456 `
  --output docs\skill_creator_evaluate.md
```

## 置き方の考え

今回は mod 本体じゃなく、独立ツールとして `tools/skillops/` に分けています。
だから流体挙動や mixin に触れずに、スキル運用だけ育てられます。

## 小さな運用ループ

1. `ingest` で現状カタログを作る
2. 実運用のたびに `observe` する
3. 失敗がたまったら `inspect`
4. `amend` で修正案を出す
5. 修正後に `evaluate` で改善したか見る
