#!/usr/bin/env python3
"""Skill lifecycle tooling for ingest, observation, inspection, amendment, and evaluation."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable


USE_WHEN_PATTERN = re.compile(r"\buse when\b", re.IGNORECASE)
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{1,}")
SKILL_MENTION_PATTERN = re.compile(r"\$([a-z0-9][a-z0-9-]{0,62})")
TOOLCHAIN_ERROR_PATTERN = re.compile(
    r"(not found|no module named|command not found|missing dependency|enoent|is not recognized)",
    re.IGNORECASE,
)
ENVIRONMENT_ERROR_PATTERN = re.compile(
    r"(permission denied|timed out|network|proxy|certificate|authentication|forbidden|unauthorized)",
    re.IGNORECASE,
)
UNCLEAR_FEEDBACK_PATTERN = re.compile(
    r"(unclear|confusing|ambiguous|not sure|didn.t understand|missing step|which one)",
    re.IGNORECASE,
)
TASK_BULLET_HINT_PATTERN = re.compile(
    r"\b(create|update|fix|debug|build|analy[sz]e|inspect|review|generate|convert|summarize|validate|compare|trace)\b",
    re.IGNORECASE,
)


@dataclass
class SkillDoc:
    name: str
    description: str
    body: str
    skill_md_path: Path
    skill_root: Path
    content_hash: str
    purpose: str
    task_patterns: list[str]
    mentioned_skills: list[str]
    tokens: set[str]


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip())


def tokenize(text: str) -> set[str]:
    return {token.lower() for token in TOKEN_PATTERN.findall(text)}


def compute_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    if not text.startswith("---"):
        raise ValueError("SKILL.md must start with YAML frontmatter.")

    lines = text.splitlines()
    if len(lines) < 3:
        raise ValueError("SKILL.md frontmatter is incomplete.")

    end_index = None
    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            end_index = index
            break

    if end_index is None:
        raise ValueError("SKILL.md frontmatter is missing its closing --- line.")

    frontmatter: dict[str, str] = {}
    for line in lines[1:end_index]:
        if not line.strip():
            continue
        if ":" not in line:
            raise ValueError(f"Invalid frontmatter line: {line}")
        key, value = line.split(":", 1)
        frontmatter[key.strip()] = value.strip()

    body = "\n".join(lines[end_index + 1 :]).strip()
    return frontmatter, body


def extract_purpose(description: str) -> str:
    parts = USE_WHEN_PATTERN.split(description, maxsplit=1)
    purpose = normalize_text(parts[0])
    if purpose.endswith("."):
        return purpose
    return purpose.rstrip(",;") + "."


def split_task_phrases(text: str) -> list[str]:
    cleaned = normalize_text(text)
    cleaned = re.sub(r"\((\d+)\)", "|", cleaned)
    phrases = re.split(r"\s*\|\s*|;\s*|,\s+(?=[A-Z0-9(])|\s+or\s+", cleaned)
    results: list[str] = []
    for phrase in phrases:
        phrase = normalize_text(phrase.strip(" .-"))
        phrase = re.sub(r"^(Codex needs to|Codex needs this skill for:?|this skill for:?)\s+", "", phrase, flags=re.IGNORECASE)
        if phrase and len(phrase) > 3:
            results.append(phrase)
    return results


def extract_task_patterns(description: str, body: str) -> list[str]:
    patterns: list[str] = []
    use_when_match = USE_WHEN_PATTERN.search(description)
    if use_when_match:
        patterns.extend(split_task_phrases(description[use_when_match.end() :]))

    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith(("-", "*")):
            stripped = stripped[1:].strip()
            if (
                8 <= len(stripped) <= 120
                and "$" not in stripped
                and TASK_BULLET_HINT_PATTERN.search(stripped)
            ):
                patterns.append(stripped.rstrip("."))

    deduped: list[str] = []
    seen: set[str] = set()
    for pattern in patterns:
        lowered = pattern.lower()
        if lowered not in seen:
            seen.add(lowered)
            deduped.append(pattern)
    return deduped[:12]


def detect_skill_mentions(text: str) -> list[str]:
    mentions = [match.group(1) for match in SKILL_MENTION_PATTERN.finditer(text)]
    deduped: list[str] = []
    seen: set[str] = set()
    for mention in mentions:
        if mention not in seen:
            seen.add(mention)
            deduped.append(mention)
    return deduped


def read_skill(skill_md_path: Path) -> SkillDoc:
    text = skill_md_path.read_text(encoding="utf-8")
    frontmatter, body = parse_frontmatter(text)
    name = frontmatter.get("name", "").strip()
    description = normalize_text(frontmatter.get("description", ""))
    if not name or not description:
        raise ValueError(f"{skill_md_path} is missing required name/description frontmatter.")

    combined_text = f"{description}\n{body}"
    content_hash = compute_hash(text)
    return SkillDoc(
        name=name,
        description=description,
        body=body,
        skill_md_path=skill_md_path,
        skill_root=skill_md_path.parent,
        content_hash=content_hash,
        purpose=extract_purpose(description),
        task_patterns=extract_task_patterns(description, body),
        mentioned_skills=detect_skill_mentions(combined_text),
        tokens=tokenize(combined_text),
    )


def find_skill_docs(skills_roots: Iterable[Path]) -> list[SkillDoc]:
    docs: list[SkillDoc] = []
    for root in skills_roots:
        for skill_md_path in sorted(root.rglob("SKILL.md")):
            docs.append(read_skill(skill_md_path))
    return docs


def relation_entries(skill: SkillDoc, all_skills: list[SkillDoc]) -> list[dict[str, str | float]]:
    related: list[dict[str, str | float]] = []

    explicit_mentions = set(skill.mentioned_skills)
    for mention in explicit_mentions:
        if mention == skill.name:
            continue
        related.append(
            {
                "skill": mention,
                "reason": "Referenced explicitly in SKILL.md.",
                "score": 1.0,
            }
        )

    existing = {entry["skill"] for entry in related}
    for candidate in all_skills:
        if candidate.name == skill.name or candidate.name in existing:
            continue

        union = skill.tokens | candidate.tokens
        if not union:
            continue
        similarity = len(skill.tokens & candidate.tokens) / len(union)
        if similarity >= 0.12:
            related.append(
                {
                    "skill": candidate.name,
                    "reason": f"Description/body overlap suggests adjacent task space (similarity {similarity:.2f}).",
                    "score": round(similarity, 2),
                }
            )

    related.sort(key=lambda item: (-float(item["score"]), str(item["skill"])))
    return related[:6]


def build_catalog(skills_roots: Iterable[Path]) -> dict[str, object]:
    docs = find_skill_docs(skills_roots)
    catalog_skills: list[dict[str, object]] = []
    for doc in docs:
        catalog_skills.append(
            {
                "skill": doc.name,
                "purpose": doc.purpose,
                "description": doc.description,
                "task_patterns": doc.task_patterns,
                "mentioned_skills": doc.mentioned_skills,
                "related_skills": relation_entries(doc, docs),
                "skill_root": str(doc.skill_root),
                "skill_md": str(doc.skill_md_path),
                "content_hash": doc.content_hash,
                "ingested_at": now_iso(),
            }
        )

    return {
        "generated_at": now_iso(),
        "skills": sorted(catalog_skills, key=lambda item: str(item["skill"])),
    }


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def write_json(path: Path, payload: object) -> None:
    ensure_parent(path)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def load_catalog(path: Path | None) -> dict[str, object]:
    if path is None or not path.exists():
        return {"skills": []}
    return json.loads(path.read_text(encoding="utf-8"))


def catalog_index(catalog: dict[str, object]) -> dict[str, dict[str, object]]:
    skills = catalog.get("skills", [])
    if not isinstance(skills, list):
        return {}
    indexed: dict[str, dict[str, object]] = {}
    for entry in skills:
        if isinstance(entry, dict) and isinstance(entry.get("skill"), str):
            indexed[str(entry["skill"])] = entry
    return indexed


def append_jsonl(path: Path, payload: dict[str, object]) -> None:
    ensure_parent(path)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False) + "\n")


def load_jsonl(path: Path) -> list[dict[str, object]]:
    if not path.exists():
        return []
    records: list[dict[str, object]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped:
                continue
            records.append(json.loads(stripped))
    return records


def match_score(task: str, patterns: Iterable[str]) -> float:
    task_tokens = tokenize(task)
    best = 0.0
    for pattern in patterns:
        pattern_tokens = tokenize(pattern)
        union = task_tokens | pattern_tokens
        if not union:
            continue
        score = len(task_tokens & pattern_tokens) / len(union)
        best = max(best, score)
    return best


def summarize_error(error: str) -> str:
    text = normalize_text(error)
    if not text:
        return "no-error-detail"
    return text[:120]


def distinct_ordered(items: Iterable[str], limit: int = 6) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for item in items:
        normalized = normalize_text(item)
        if not normalized:
            continue
        lowered = normalized.lower()
        if lowered in seen:
            continue
        seen.add(lowered)
        ordered.append(normalized)
        if len(ordered) >= limit:
            break
    return ordered


def build_inspect_report(
    events: list[dict[str, object]], skill: str, skill_entry: dict[str, object] | None
) -> dict[str, object]:
    filtered = [event for event in events if event.get("skill") == skill]
    total = len(filtered)
    success = sum(1 for event in filtered if event.get("status") == "success")
    failures = [event for event in filtered if event.get("status") == "failure"]
    partial = sum(1 for event in filtered if event.get("status") == "partial")
    failure_rate = (len(failures) / total) if total else 0.0

    failure_errors = Counter(summarize_error(str(event.get("error", ""))) for event in failures)
    patterns = skill_entry.get("task_patterns", []) if skill_entry else []
    unmatched_failures = [
        normalize_text(str(event.get("task", "")))
        for event in failures
        if match_score(str(event.get("task", "")), patterns) < 0.34
    ]

    root_causes: list[dict[str, object]] = []
    combined_failure_text = "\n".join(
        f"{event.get('error', '')}\n{event.get('feedback', '')}" for event in failures
    )

    if any(TOOLCHAIN_ERROR_PATTERN.search(str(event.get("error", ""))) for event in failures):
        root_causes.append(
            {
                "cause": "外部ツール連携の前提漏れ",
                "evidence": "失敗ログに tool/module 未検出系エラーが含まれています。",
                "confidence": 0.83,
            }
        )

    if len(unmatched_failures) >= 2 and failure_rate >= 0.25:
        root_causes.append(
            {
                "cause": "トリガー条件のずれ",
                "evidence": "失敗タスクが現在の task_patterns とあまり噛み合っていません。",
                "confidence": 0.76,
            }
        )

    unclear_feedback_hits = sum(
        1
        for event in failures
        if UNCLEAR_FEEDBACK_PATTERN.search(str(event.get("feedback", "")))
        or not normalize_text(str(event.get("error", "")))
    )
    if unclear_feedback_hits >= 2:
        root_causes.append(
            {
                "cause": "手順や分岐の説明不足",
                "evidence": "失敗時の詳細が薄い、または feedback に曖昧さの指摘があります。",
                "confidence": 0.69,
            }
        )

    if ENVIRONMENT_ERROR_PATTERN.search(combined_failure_text):
        root_causes.append(
            {
                "cause": "実行環境の差分を吸収できていない",
                "evidence": "権限・認証・ネットワーク系の失敗が見えます。",
                "confidence": 0.62,
            }
        )

    if failure_errors:
        top_error, top_error_count = failure_errors.most_common(1)[0]
        if top_error_count >= 3:
            root_causes.append(
                {
                    "cause": "同型失敗へのガード不足",
                    "evidence": f"同じエラーが {top_error_count} 回繰り返されています: {top_error}",
                    "confidence": 0.65,
                }
            )

    return {
        "generated_at": now_iso(),
        "skill": skill,
        "total_runs": total,
        "success_runs": success,
        "partial_runs": partial,
        "failure_runs": len(failures),
        "success_rate": round((success / total), 3) if total else 0.0,
        "failure_rate": round(failure_rate, 3),
        "top_failure_errors": [
            {"error": error, "count": count} for error, count in failure_errors.most_common(5)
        ],
        "unmatched_failure_tasks": distinct_ordered(unmatched_failures, limit=6),
        "root_causes": root_causes,
    }


def render_inspect_markdown(report: dict[str, object]) -> str:
    lines = [
        f"# Skill Inspect Report: {report['skill']}",
        "",
        f"- Generated: {report['generated_at']}",
        f"- Total runs: {report['total_runs']}",
        f"- Success rate: {report['success_rate']}",
        f"- Failure rate: {report['failure_rate']}",
        "",
        "## Root Causes",
    ]

    root_causes = report.get("root_causes", [])
    if not root_causes:
        lines.append("- No strong recurring failure pattern found yet.")
    else:
        for cause in root_causes:
            lines.append(
                f"- {cause['cause']} (confidence {cause['confidence']}): {cause['evidence']}"
            )

    lines.extend(["", "## Repeated Errors"])
    top_errors = report.get("top_failure_errors", [])
    if not top_errors:
        lines.append("- No failure errors recorded.")
    else:
        for item in top_errors:
            lines.append(f"- {item['count']}x {item['error']}")

    lines.extend(["", "## Unmatched Failure Tasks"])
    unmatched = report.get("unmatched_failure_tasks", [])
    if not unmatched:
        lines.append("- Failure tasks are mostly covered by current task patterns.")
    else:
        for task in unmatched:
            lines.append(f"- {task}")

    return "\n".join(lines) + "\n"


def build_amendment(
    skill_doc: SkillDoc, inspect_report: dict[str, object], all_events: list[dict[str, object]]
) -> dict[str, object]:
    skill_events = [event for event in all_events if event.get("skill") == skill_doc.name]
    failure_events = [event for event in skill_events if event.get("status") == "failure"]
    observed_fail_tasks = distinct_ordered(
        [
            str(event.get("task", ""))
            for event in failure_events
            if match_score(str(event.get("task", "")), skill_doc.task_patterns) < 0.34
        ],
        limit=4,
    )

    expanded_patterns = distinct_ordered(skill_doc.task_patterns + observed_fail_tasks, limit=8)
    purpose = skill_doc.purpose.rstrip(".")
    proposed_description = purpose
    if expanded_patterns:
        pattern_text = ", ".join(expanded_patterns)
        proposed_description = (
            f"{purpose}. Use when Codex needs this skill for: {pattern_text}."
        )

    body_additions: list[dict[str, str]] = []
    for cause in inspect_report.get("root_causes", []):
        cause_name = str(cause.get("cause", ""))
        if "外部ツール連携" in cause_name:
            body_additions.append(
                {
                    "heading": "## Validate Prerequisites Early",
                    "content": (
                        "- Verify required tools or modules before the main workflow.\n"
                        "- If a dependency is missing, stop early and say exactly what is missing.\n"
                        "- Provide the fallback path if the preferred toolchain is unavailable."
                    ),
                }
            )
        elif "トリガー条件" in cause_name:
            body_additions.append(
                {
                    "heading": "## Expand Trigger Examples",
                    "content": (
                        "- Add concrete user request examples that match the newly observed task patterns.\n"
                        "- Keep the frontmatter description broad enough that adjacent requests still trigger the skill."
                    ),
                }
            )
        elif "説明不足" in cause_name:
            body_additions.append(
                {
                    "heading": "## Add Decision Hints",
                    "content": (
                        "- Call out the main branching decisions in the workflow.\n"
                        "- When multiple paths exist, state what signals should choose each path."
                    ),
                }
            )
        elif "同型失敗" in cause_name:
            body_additions.append(
                {
                    "heading": "## Add Fast Validation",
                    "content": (
                        "- Add a lightweight validation step before the fragile action.\n"
                        "- Mention how to detect the known repeated failure before it cascades."
                    ),
                }
            )
        elif "実行環境" in cause_name:
            body_additions.append(
                {
                    "heading": "## Note Environment Assumptions",
                    "content": (
                        "- State the environment assumptions that matter.\n"
                        "- If auth/network/permissions can block the task, tell Codex how to surface that cleanly."
                    ),
                }
            )

    deduped_additions: list[dict[str, str]] = []
    seen_headings: set[str] = set()
    for addition in body_additions:
        if addition["heading"] in seen_headings:
            continue
        seen_headings.add(addition["heading"])
        deduped_additions.append(addition)

    return {
        "generated_at": now_iso(),
        "skill": skill_doc.name,
        "current_description": skill_doc.description,
        "proposed_description": proposed_description,
        "body_additions": deduped_additions,
        "based_on_root_causes": inspect_report.get("root_causes", []),
        "observed_gap_tasks": observed_fail_tasks,
    }


def render_amend_markdown(amendment: dict[str, object]) -> str:
    lines = [
        f"# Skill Amendment Proposal: {amendment['skill']}",
        "",
        f"- Generated: {amendment['generated_at']}",
        "",
        "## Proposed Frontmatter Description",
        amendment["proposed_description"],
        "",
        "## Suggested Body Additions",
    ]

    additions = amendment.get("body_additions", [])
    if not additions:
        lines.append("- No structural body changes suggested yet.")
    else:
        for addition in additions:
            lines.extend([addition["heading"], addition["content"], ""])

    lines.append("## Observed Gap Tasks")
    gap_tasks = amendment.get("observed_gap_tasks", [])
    if not gap_tasks:
        lines.append("- No uncovered task pattern was detected.")
    else:
        for task in gap_tasks:
            lines.append(f"- {task}")

    return "\n".join(lines).rstrip() + "\n"


def replace_description_in_frontmatter(text: str, new_description: str) -> str:
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        raise ValueError("Cannot update description because frontmatter is missing.")
    updated = []
    description_replaced = False
    for line in lines:
        if line.startswith("description:"):
            updated.append(f"description: {new_description}")
            description_replaced = True
        else:
            updated.append(line)
    if not description_replaced:
        raise ValueError("Cannot update description because description: line is missing.")
    return "\n".join(updated) + ("\n" if text.endswith("\n") else "")


def append_generated_sections(text: str, additions: list[dict[str, str]]) -> str:
    if not additions:
        return text
    body = text.rstrip()
    for addition in additions:
        heading = addition["heading"]
        if heading in body:
            continue
        body += f"\n\n{heading}\n\n{addition['content']}\n"
    return body + ("\n" if not body.endswith("\n") else "")


def snapshot_skill(skill_md_path: Path, history_root: Path, version_label: str) -> Path:
    snapshot_dir = history_root / skill_md_path.parent.name / version_label
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    destination = snapshot_dir / "SKILL.md"
    shutil.copy2(skill_md_path, destination)
    return destination


def resolve_skill_doc(skill_root: Path, skill_name: str) -> SkillDoc:
    direct_path = skill_root / skill_name / "SKILL.md"
    if direct_path.exists():
        return read_skill(direct_path)
    for skill_md_path in skill_root.rglob("SKILL.md"):
        doc = read_skill(skill_md_path)
        if doc.name == skill_name:
            return doc
    raise FileNotFoundError(f"Could not find SKILL.md for skill '{skill_name}' under {skill_root}")


def group_runs_by_version(events: list[dict[str, object]]) -> dict[str, list[dict[str, object]]]:
    grouped: dict[str, list[dict[str, object]]] = defaultdict(list)
    for event in events:
        grouped[str(event.get("skill_version", "unknown"))].append(event)
    return dict(grouped)


def version_metrics(events: list[dict[str, object]]) -> dict[str, object]:
    total = len(events)
    success = sum(1 for event in events if event.get("status") == "success")
    failure = sum(1 for event in events if event.get("status") == "failure")
    partial = sum(1 for event in events if event.get("status") == "partial")
    errors = Counter(
        summarize_error(str(event.get("error", "")))
        for event in events
        if event.get("status") == "failure"
    )
    return {
        "total_runs": total,
        "success_rate": round(success / total, 3) if total else 0.0,
        "failure_rate": round(failure / total, 3) if total else 0.0,
        "partial_rate": round(partial / total, 3) if total else 0.0,
        "top_errors": [{"error": error, "count": count} for error, count in errors.most_common(3)],
    }


def build_evaluation_report(
    events: list[dict[str, object]], skill: str, baseline_version: str, candidate_version: str
) -> dict[str, object]:
    filtered = [event for event in events if event.get("skill") == skill]
    grouped = group_runs_by_version(filtered)
    baseline_events = grouped.get(baseline_version, [])
    candidate_events = grouped.get(candidate_version, [])
    baseline_metrics = version_metrics(baseline_events)
    candidate_metrics = version_metrics(candidate_events)
    success_delta = round(
        float(candidate_metrics["success_rate"]) - float(baseline_metrics["success_rate"]),
        3,
    )
    failure_delta = round(
        float(candidate_metrics["failure_rate"]) - float(baseline_metrics["failure_rate"]),
        3,
    )

    if candidate_metrics["total_runs"] == 0 or baseline_metrics["total_runs"] == 0:
        verdict = "insufficient-data"
    elif success_delta > 0 and failure_delta <= 0:
        verdict = "improved"
    elif success_delta < 0:
        verdict = "regressed"
    else:
        verdict = "mixed"

    return {
        "generated_at": now_iso(),
        "skill": skill,
        "baseline_version": baseline_version,
        "candidate_version": candidate_version,
        "baseline": baseline_metrics,
        "candidate": candidate_metrics,
        "success_rate_delta": success_delta,
        "failure_rate_delta": failure_delta,
        "verdict": verdict,
    }


def render_evaluation_markdown(report: dict[str, object]) -> str:
    lines = [
        f"# Skill Evaluation Report: {report['skill']}",
        "",
        f"- Generated: {report['generated_at']}",
        f"- Baseline: {report['baseline_version']}",
        f"- Candidate: {report['candidate_version']}",
        f"- Verdict: {report['verdict']}",
        "",
        "## Metrics",
        (
            f"- Success rate: {report['baseline']['success_rate']} -> "
            f"{report['candidate']['success_rate']} (delta {report['success_rate_delta']})"
        ),
        (
            f"- Failure rate: {report['baseline']['failure_rate']} -> "
            f"{report['candidate']['failure_rate']} (delta {report['failure_rate_delta']})"
        ),
        (
            f"- Sample size: {report['baseline']['total_runs']} -> "
            f"{report['candidate']['total_runs']}"
        ),
        "",
        "## Candidate Top Errors",
    ]

    candidate_errors = report["candidate"].get("top_errors", [])
    if not candidate_errors:
        lines.append("- No candidate failure errors recorded.")
    else:
        for item in candidate_errors:
            lines.append(f"- {item['count']}x {item['error']}")

    return "\n".join(lines) + "\n"


def print_summary(title: str, payload: dict[str, object]) -> None:
    print(title)
    print(json.dumps(payload, indent=2, ensure_ascii=False))


def ingest_command(args: argparse.Namespace) -> int:
    catalog = build_catalog(Path(root) for root in args.skills_root)
    if args.output:
        write_json(Path(args.output), catalog)
    print_summary("Ingest complete", catalog)
    return 0


def observe_command(args: argparse.Namespace) -> int:
    catalog = load_catalog(Path(args.catalog) if args.catalog else None)
    indexed = catalog_index(catalog)
    entry = indexed.get(args.skill, {})
    payload = {
        "observed_at": now_iso(),
        "skill": args.skill,
        "skill_version": args.skill_version or entry.get("content_hash") or "manual",
        "task": args.task,
        "status": args.status,
        "error": args.error or "",
        "feedback": args.feedback or "",
        "tags": [item.strip() for item in (args.tags or "").split(",") if item.strip()],
    }
    append_jsonl(Path(args.run_log), payload)
    print_summary("Observation recorded", payload)
    return 0


def inspect_command(args: argparse.Namespace) -> int:
    catalog = load_catalog(Path(args.catalog) if args.catalog else None)
    indexed = catalog_index(catalog)
    events = load_jsonl(Path(args.run_log))
    report = build_inspect_report(events, args.skill, indexed.get(args.skill))
    if args.output:
        output_path = Path(args.output)
        ensure_parent(output_path)
        if output_path.suffix.lower() == ".json":
            output_path.write_text(
                json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
            )
        else:
            output_path.write_text(render_inspect_markdown(report), encoding="utf-8")
    print_summary("Inspection complete", report)
    return 0


def amend_command(args: argparse.Namespace) -> int:
    skill_root = Path(args.skill_root)
    skill_doc = resolve_skill_doc(skill_root, args.skill)
    events = load_jsonl(Path(args.run_log))
    inspect_report = build_inspect_report(
        events, args.skill, {"task_patterns": skill_doc.task_patterns}
    )
    amendment = build_amendment(skill_doc, inspect_report, events)

    if args.output:
        output_path = Path(args.output)
        ensure_parent(output_path)
        if output_path.suffix.lower() == ".json":
            output_path.write_text(
                json.dumps(amendment, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
            )
        else:
            output_path.write_text(render_amend_markdown(amendment), encoding="utf-8")

    if args.apply:
        history_root = Path(args.history_root or ".skillops/history")
        snapshot_skill(skill_doc.skill_md_path, history_root, skill_doc.content_hash)
        original_text = skill_doc.skill_md_path.read_text(encoding="utf-8")
        updated_text = replace_description_in_frontmatter(
            original_text, str(amendment["proposed_description"])
        )
        updated_text = append_generated_sections(
            updated_text, list(amendment.get("body_additions", []))
        )
        skill_doc.skill_md_path.write_text(updated_text, encoding="utf-8")

    print_summary("Amendment prepared", amendment)
    return 0


def evaluate_command(args: argparse.Namespace) -> int:
    events = load_jsonl(Path(args.run_log))
    skill_events = [event for event in events if event.get("skill") == args.skill]
    grouped = group_runs_by_version(skill_events)

    baseline_version = args.baseline_version
    candidate_version = args.candidate_version
    if not baseline_version or not candidate_version:
        versions = sorted(grouped.keys())
        if len(versions) < 2:
            print("Need at least two skill versions in the run log to evaluate.", file=sys.stderr)
            return 1
        baseline_version = baseline_version or versions[-2]
        candidate_version = candidate_version or versions[-1]

    report = build_evaluation_report(events, args.skill, baseline_version, candidate_version)
    if args.output:
        output_path = Path(args.output)
        ensure_parent(output_path)
        if output_path.suffix.lower() == ".json":
            output_path.write_text(
                json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
            )
        else:
            output_path.write_text(render_evaluation_markdown(report), encoding="utf-8")

    print_summary("Evaluation complete", report)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Operate the skill improvement lifecycle.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ingest = subparsers.add_parser("ingest", help="Catalog skills from SKILL.md files.")
    ingest.add_argument("--skills-root", nargs="+", required=True, help="Root folders containing skills.")
    ingest.add_argument("--output", help="Write the generated catalog JSON here.")
    ingest.set_defaults(func=ingest_command)

    observe = subparsers.add_parser("observe", help="Append one skill execution record.")
    observe.add_argument("--run-log", required=True, help="JSONL file to append observations to.")
    observe.add_argument("--skill", required=True, help="Skill name.")
    observe.add_argument("--task", required=True, help="Task description.")
    observe.add_argument("--status", required=True, choices=["success", "failure", "partial"])
    observe.add_argument("--error", help="Error summary.")
    observe.add_argument("--feedback", help="User feedback.")
    observe.add_argument("--skill-version", help="Skill version or content hash.")
    observe.add_argument("--catalog", help="Catalog JSON used to infer the current content hash.")
    observe.add_argument("--tags", help="Comma-separated tags.")
    observe.set_defaults(func=observe_command)

    inspect = subparsers.add_parser("inspect", help="Analyze recurring failures for a skill.")
    inspect.add_argument("--run-log", required=True, help="JSONL execution log.")
    inspect.add_argument("--skill", required=True, help="Skill name.")
    inspect.add_argument("--catalog", help="Catalog JSON for task pattern context.")
    inspect.add_argument("--output", help="Optional markdown/json report output path.")
    inspect.set_defaults(func=inspect_command)

    amend = subparsers.add_parser("amend", help="Generate or apply a repair proposal for SKILL.md.")
    amend.add_argument("--run-log", required=True, help="JSONL execution log.")
    amend.add_argument("--skill", required=True, help="Skill name.")
    amend.add_argument("--skill-root", required=True, help="Root folder containing the target skill.")
    amend.add_argument("--output", help="Optional markdown/json proposal output path.")
    amend.add_argument("--apply", action="store_true", help="Apply safe additive changes to SKILL.md.")
    amend.add_argument(
        "--history-root",
        help="Snapshot directory used before overwriting SKILL.md. Defaults to .skillops/history.",
    )
    amend.set_defaults(func=amend_command)

    evaluate = subparsers.add_parser("evaluate", help="Compare two observed versions of a skill.")
    evaluate.add_argument("--run-log", required=True, help="JSONL execution log.")
    evaluate.add_argument("--skill", required=True, help="Skill name.")
    evaluate.add_argument("--baseline-version", help="Older skill version/content hash.")
    evaluate.add_argument("--candidate-version", help="Newer skill version/content hash.")
    evaluate.add_argument("--output", help="Optional markdown/json report output path.")
    evaluate.set_defaults(func=evaluate_command)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
