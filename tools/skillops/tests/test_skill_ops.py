from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.skillops import skill_ops


SKILL_TEMPLATE = """---
name: {name}
description: {description}
---

# {title}

- Support deterministic workflows
- Mention ${related}
"""


class SkillOpsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.skills_root = self.root / "skills"
        self.skills_root.mkdir()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_skill(self, name: str, description: str, related: str = "other-skill") -> Path:
        skill_dir = self.skills_root / name
        skill_dir.mkdir(parents=True)
        skill_md = skill_dir / "SKILL.md"
        skill_md.write_text(
            SKILL_TEMPLATE.format(
                name=name,
                description=description,
                title=name.replace("-", " ").title(),
                related=related,
            ),
            encoding="utf-8",
        )
        return skill_md

    def test_ingest_collects_patterns_and_relations(self) -> None:
        self.write_skill(
            "skill-creator",
            "Create skills. Use when Codex needs to create a new skill, update an existing skill, or validate a skill package.",
            related="quick-validator",
        )
        self.write_skill(
            "quick-validator",
            "Validate skill packages. Use when Codex needs to lint skill frontmatter or check resource layout.",
            related="skill-creator",
        )

        catalog = skill_ops.build_catalog([self.skills_root])
        indexed = {entry["skill"]: entry for entry in catalog["skills"]}
        creator = indexed["skill-creator"]

        self.assertIn("create a new skill", " ".join(creator["task_patterns"]).lower())
        related_names = [entry["skill"] for entry in creator["related_skills"]]
        self.assertIn("quick-validator", related_names)

    def test_inspect_finds_toolchain_and_trigger_drift(self) -> None:
        self.write_skill(
            "skill-creator",
            "Create skills. Use when Codex needs to create a skill or validate a skill package.",
        )
        catalog = skill_ops.build_catalog([self.skills_root])
        run_log = self.root / "run_log.jsonl"

        events = [
            {
                "observed_at": "2026-03-17T12:00:00+09:00",
                "skill": "skill-creator",
                "skill_version": "v1",
                "task": "create a skill package",
                "status": "success",
                "error": "",
                "feedback": "",
            },
            {
                "observed_at": "2026-03-17T12:01:00+09:00",
                "skill": "skill-creator",
                "skill_version": "v1",
                "task": "instrument remote API integration",
                "status": "failure",
                "error": "command not found: poetry",
                "feedback": "confusing fallback",
            },
            {
                "observed_at": "2026-03-17T12:02:00+09:00",
                "skill": "skill-creator",
                "skill_version": "v1",
                "task": "observe skill executions for analytics",
                "status": "failure",
                "error": "",
                "feedback": "unclear next step",
            },
        ]
        run_log.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")

        indexed = skill_ops.catalog_index(catalog)
        report = skill_ops.build_inspect_report(
            skill_ops.load_jsonl(run_log), "skill-creator", indexed["skill-creator"]
        )
        cause_names = [item["cause"] for item in report["root_causes"]]

        self.assertIn("外部ツール連携の前提漏れ", cause_names)
        self.assertIn("トリガー条件のずれ", cause_names)
        self.assertGreaterEqual(len(report["unmatched_failure_tasks"]), 1)

    def test_amend_apply_snapshots_and_evaluate_versions(self) -> None:
        skill_md = self.write_skill(
            "skill-creator",
            "Create skills. Use when Codex needs to create a skill or validate a skill package.",
        )
        run_log = self.root / "run_log.jsonl"
        events = [
            {
                "observed_at": "2026-03-17T12:00:00+09:00",
                "skill": "skill-creator",
                "skill_version": "old123",
                "task": "create a skill",
                "status": "success",
                "error": "",
                "feedback": "",
            },
            {
                "observed_at": "2026-03-17T12:01:00+09:00",
                "skill": "skill-creator",
                "skill_version": "old123",
                "task": "observe skill executions",
                "status": "failure",
                "error": "command not found: uv",
                "feedback": "unclear fallback",
            },
            {
                "observed_at": "2026-03-17T12:02:00+09:00",
                "skill": "skill-creator",
                "skill_version": "new456",
                "task": "observe skill executions",
                "status": "success",
                "error": "",
                "feedback": "much better",
            },
        ]
        run_log.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")

        amend_args = skill_ops.build_parser().parse_args(
            [
                "amend",
                "--run-log",
                str(run_log),
                "--skill",
                "skill-creator",
                "--skill-root",
                str(self.skills_root),
                "--apply",
                "--history-root",
                str(self.root / "history"),
            ]
        )
        result = skill_ops.amend_command(amend_args)

        self.assertEqual(result, 0)
        self.assertTrue(any((self.root / "history").rglob("SKILL.md")))
        updated_text = skill_md.read_text(encoding="utf-8")
        self.assertIn("Use when Codex needs this skill for", updated_text)
        self.assertIn("## Validate Prerequisites Early", updated_text)

        evaluation = skill_ops.build_evaluation_report(
            skill_ops.load_jsonl(run_log), "skill-creator", "old123", "new456"
        )
        self.assertEqual(evaluation["verdict"], "improved")


if __name__ == "__main__":
    unittest.main()
