#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import math
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_REPORTS_ROOT = ROOT_DIR / "reports"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def contains_cjk(text: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in text)


def normalize_text_safe(text: Any) -> str:
    if text is None:
        return ""
    lowered = str(text).lower().strip()
    if contains_cjk(lowered):
        lowered = re.sub(r"[，。！？；：、“”‘’（）()【】《》〈〉—…·\s]+", "", lowered)
        return lowered
    lowered = lowered.replace("’", "'")
    lowered = re.sub(r"[^a-z0-9]+", " ", lowered)
    return re.sub(r"\s+", " ", lowered).strip()


def tokenize(text: str) -> list[str]:
    normalized = normalize_text_safe(text)
    if not normalized:
        return []
    if contains_cjk(normalized):
        return [char for char in normalized if not char.isspace()]
    return normalized.split()


def exact_match(prediction: str, references: list[str]) -> float:
    normalized_prediction = normalize_text_safe(prediction)
    if not normalized_prediction:
        return 0.0
    return 1.0 if any(normalized_prediction == normalize_text_safe(reference) for reference in references) else 0.0


def token_f1(prediction: str, references: list[str]) -> float:
    prediction_tokens = tokenize(prediction)
    if not prediction_tokens:
        return 0.0

    best = 0.0
    for reference in references:
        reference_tokens = tokenize(reference)
        if not reference_tokens:
            continue
        overlap = 0
        remaining = list(reference_tokens)
        for token in prediction_tokens:
            if token in remaining:
                overlap += 1
                remaining.remove(token)
        if overlap == 0:
            continue
        precision = overlap / len(prediction_tokens)
        recall = overlap / len(reference_tokens)
        f1 = 2 * precision * recall / (precision + recall)
        best = max(best, f1)
    return best


def lcs_length(tokens_a: list[str], tokens_b: list[str]) -> int:
    if not tokens_a or not tokens_b:
        return 0
    dp = [0] * (len(tokens_b) + 1)
    for token_a in tokens_a:
        prev = 0
        for index, token_b in enumerate(tokens_b, start=1):
            current = dp[index]
            if token_a == token_b:
                dp[index] = prev + 1
            else:
                dp[index] = max(dp[index], dp[index - 1])
            prev = current
    return dp[-1]


def rouge_l_f1(prediction: str, references: list[str]) -> float:
    prediction_tokens = tokenize(prediction)
    if not prediction_tokens:
        return 0.0
    best = 0.0
    for reference in references:
        reference_tokens = tokenize(reference)
        if not reference_tokens:
            continue
        lcs = lcs_length(prediction_tokens, reference_tokens)
        if lcs == 0:
            continue
        precision = lcs / len(prediction_tokens)
        recall = lcs / len(reference_tokens)
        score = 2 * precision * recall / (precision + recall)
        best = max(best, score)
    return best


def safe_mean(values: list[float]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def render_metric(value: float | None) -> str:
    return "-" if value is None else f"{value:.3f}"


def render_markdown_table(rows: list[dict[str, Any]]) -> str:
    if not rows:
        return "| Run | Suite | Cases | Model | Score |\n| --- | --- | ---: | --- | ---: |\n| - | - | - | - | - |\n"
    header = "| Run | Suite | Cases | Model | `qa_em` | `qa_f1` | `ref_f1` | `rouge_l` | `avg_latency_ms` | `avg_role_memories` | `avg_summary_version` |\n"
    separator = "| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"
    lines = [header, separator]
    for row in rows:
        lines.append(
            "| {runId} | {suiteId} | {caseCount} | {model} | {qa_em} | {qa_f1} | {ref_f1} | {rouge_l} | {latency} | {memories} | {summary} |\n".format(
                runId=row["runId"],
                suiteId=row["suiteId"],
                caseCount=row["caseCount"],
                model=row["resolvedModelName"] or "-",
                qa_em=render_metric(row["qa_em"]),
                qa_f1=render_metric(row["qa_f1"]),
                ref_f1=render_metric(row["reference_token_f1"]),
                rouge_l=render_metric(row["rouge_l"]),
                latency=render_metric(row["avgAssistantLatencyMs"]),
                memories=render_metric(row["avgRoleMemoryCount"]),
                summary=render_metric(row["avgSummaryVersion"]),
            )
        )
    return "".join(lines)


def analyze_run(run_dir: Path) -> dict[str, Any]:
    manifest_path = run_dir / "input-manifest.json"
    summary_path = run_dir / "run-summary.json"
    status_path = run_dir / "run-status.json"

    if not manifest_path.exists() or not summary_path.exists() or not status_path.exists():
        raise FileNotFoundError(f"Missing required report artifacts in {run_dir}")

    manifest = load_json(manifest_path)
    summary = load_json(summary_path)
    status = load_json(status_path)

    manifest_cases = {case["caseId"]: case for case in manifest["cases"]}
    turn_metric_records: list[dict[str, Any]] = []
    assertion_failures = 0

    role_memory_counts: list[float] = []
    summary_versions: list[float] = []
    assistant_latencies: list[float] = []

    scorer_buckets: dict[str, list[dict[str, float]]] = defaultdict(list)

    for case_result in summary["caseResults"]:
        role_memory_counts.append(float(case_result["roleMemoryCount"]))
        if case_result.get("summaryVersion") is not None:
            summary_versions.append(float(case_result["summaryVersion"]))
        assertion_failures += sum(1 for assertion in case_result["assertionResults"] if not assertion["passed"])

        manifest_case = manifest_cases.get(case_result["caseId"], {})
        manifest_turns = {turn["turnId"]: turn for turn in manifest_case.get("turns", [])}

        for turn_result in case_result["turnResults"]:
            if turn_result.get("assistantLatencyMs") is not None:
                assistant_latencies.append(float(turn_result["assistantLatencyMs"]))

            manifest_turn = manifest_turns.get(turn_result["turnId"], {})
            expectations = manifest_turn.get("expectations") or {}
            reference_answer = expectations.get("referenceAnswer")
            acceptable_answers = expectations.get("acceptableAnswers") or []
            references = [reference_answer] if reference_answer else []
            references.extend(answer for answer in acceptable_answers if answer)
            scorer = expectations.get("scorer", "")
            prediction = turn_result.get("assistantContent", "")

            metrics = {}
            if references and scorer in {"qa_em_f1", "reference_token_f1"}:
                metrics["qa_em"] = exact_match(prediction, references) if scorer == "qa_em_f1" else None
                metrics["qa_f1"] = token_f1(prediction, references) if scorer == "qa_em_f1" else None
                metrics["reference_token_f1"] = token_f1(prediction, references)
                metrics["rouge_l"] = rouge_l_f1(prediction, references)
                scorer_buckets[scorer].append(
                    {
                        key: value
                        for key, value in metrics.items()
                        if isinstance(value, (int, float)) and not math.isnan(value)
                    }
                )

            turn_metric_records.append(
                {
                    "caseId": case_result["caseId"],
                    "turnId": turn_result["turnId"],
                    "scorer": scorer,
                    "referenceAnswer": reference_answer,
                    "acceptableAnswers": acceptable_answers,
                    "assistantStatus": turn_result["assistantStatus"],
                    "assistantLatencyMs": turn_result.get("assistantLatencyMs"),
                    "metrics": metrics,
                }
            )

    aggregate_metrics: dict[str, float | None] = {}
    for metric_name in ["qa_em", "qa_f1", "reference_token_f1", "rouge_l"]:
        values = [
            record[metric_name]
            for bucket in scorer_buckets.values()
            for record in bucket
            if record.get(metric_name) is not None
        ]
        aggregate_metrics[metric_name] = safe_mean(values)

    analysis = {
        "analysisVersion": 1,
        "generatedAtUtc": utc_now(),
        "runId": summary["runId"],
        "suiteId": summary["suiteId"],
        "statusState": status["state"],
        "statusPhase": status["phase"],
        "resolvedModelName": summary["resolvedModel"]["resolvedModelName"],
        "selectionSource": summary["resolvedModel"]["selectionSource"],
        "caseCount": len(summary["caseResults"]),
        "passedCaseCount": sum(1 for case in summary["caseResults"] if case["passed"]),
        "failedAssertionCount": assertion_failures,
        "avgAssistantLatencyMs": safe_mean(assistant_latencies),
        "avgRoleMemoryCount": safe_mean(role_memory_counts),
        "avgSummaryVersion": safe_mean(summary_versions),
        "metrics": aggregate_metrics,
        "turnMetrics": turn_metric_records,
    }

    (run_dir / "analysis-summary.json").write_text(
        json.dumps(analysis, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    markdown = (
        f"# Roleplay Eval Analysis\n\n"
        f"- runId: `{analysis['runId']}`\n"
        f"- suiteId: `{analysis['suiteId']}`\n"
        f"- model: `{analysis['resolvedModelName']}`\n"
        f"- cases: `{analysis['caseCount']}`\n"
        f"- passedCases: `{analysis['passedCaseCount']}`\n"
        f"- failedAssertions: `{analysis['failedAssertionCount']}`\n"
        f"- qa_em: `{render_metric(aggregate_metrics['qa_em'])}`\n"
        f"- qa_f1: `{render_metric(aggregate_metrics['qa_f1'])}`\n"
        f"- reference_token_f1: `{render_metric(aggregate_metrics['reference_token_f1'])}`\n"
        f"- rouge_l: `{render_metric(aggregate_metrics['rouge_l'])}`\n"
    )
    (run_dir / "analysis-summary.md").write_text(markdown, encoding="utf-8")
    return analysis


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze pulled roleplay evaluation reports.")
    parser.add_argument(
        "--reports-root",
        type=Path,
        default=DEFAULT_REPORTS_ROOT,
        help=f"Root directory containing pulled run folders. Default: {DEFAULT_REPORTS_ROOT}",
    )
    parser.add_argument(
        "--run-dir",
        type=Path,
        action="append",
        dest="run_dirs",
        help="Specific run directory to analyze. Repeat for multiple runs.",
    )
    args = parser.parse_args()

    reports_root = args.reports_root.resolve()
    reports_root.mkdir(parents=True, exist_ok=True)
    run_dirs = [run_dir.resolve() for run_dir in args.run_dirs] if args.run_dirs else sorted(
        path for path in reports_root.iterdir() if path.is_dir() and (path / "run-summary.json").exists()
    )

    analyses = [analyze_run(run_dir) for run_dir in run_dirs]
    comparison_rows = [
        {
            "runId": analysis["runId"],
            "suiteId": analysis["suiteId"],
            "caseCount": analysis["caseCount"],
            "resolvedModelName": analysis["resolvedModelName"],
            "qa_em": analysis["metrics"]["qa_em"],
            "qa_f1": analysis["metrics"]["qa_f1"],
            "reference_token_f1": analysis["metrics"]["reference_token_f1"],
            "rouge_l": analysis["metrics"]["rouge_l"],
            "avgAssistantLatencyMs": analysis["avgAssistantLatencyMs"],
            "avgRoleMemoryCount": analysis["avgRoleMemoryCount"],
            "avgSummaryVersion": analysis["avgSummaryVersion"],
        }
        for analysis in analyses
    ]

    comparison = {
        "comparisonVersion": 1,
        "generatedAtUtc": utc_now(),
        "runCount": len(analyses),
        "runs": analyses,
    }
    (reports_root / "comparison.json").write_text(
        json.dumps(comparison, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (reports_root / "comparison.md").write_text(render_markdown_table(comparison_rows), encoding="utf-8")
    print(f"analyzed {len(analyses)} run(s) under {reports_root}")


if __name__ == "__main__":
    main()
