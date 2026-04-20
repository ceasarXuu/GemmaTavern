#!/usr/bin/env python
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import error, request


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_REPORTS_ROOT = ROOT_DIR / "reports"
DEFAULT_CONFIG_PATH = ROOT_DIR / "config" / "judges.local.json"
PROMPT_VERSION = "deepseek-reasoner-v1"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def safe_mean(values: list[float]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def render_metric(value: float | None) -> str:
    return "-" if value is None else f"{value:.3f}"


def truncate_text(value: Any, limit: int) -> str:
    text = "" if value is None else str(value).strip()
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 3)].rstrip() + "..."


def normalize_bool(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"true", "yes", "1"}:
            return True
        if lowered in {"false", "no", "0"}:
            return False
    if isinstance(value, (int, float)):
        return bool(value)
    return default


def normalize_score(value: Any) -> int:
    if isinstance(value, bool):
        return 1
    if isinstance(value, (int, float)):
        return max(1, min(5, int(round(float(value)))))
    if isinstance(value, str):
        match = re.search(r"-?\d+(?:\.\d+)?", value)
        if match:
            return normalize_score(float(match.group(0)))
    return 1


def normalize_reason(value: Any) -> str:
    text = truncate_text(value, 420)
    return text or "No rationale provided."


def normalize_evidence(value: Any) -> list[str]:
    if isinstance(value, list):
        cleaned = [truncate_text(item, 160) for item in value if truncate_text(item, 160)]
        return cleaned[:3]
    if isinstance(value, str):
        text = truncate_text(value, 160)
        return [text] if text else []
    return []


def extract_json_object(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if not stripped:
        raise ValueError("Judge returned empty content.")
    try:
        payload = json.loads(stripped)
        if isinstance(payload, dict):
            return payload
    except json.JSONDecodeError:
        pass

    start = stripped.find("{")
    end = stripped.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("Judge did not return a JSON object.")
    payload = json.loads(stripped[start : end + 1])
    if not isinstance(payload, dict):
        raise ValueError("Judge JSON payload is not an object.")
    return payload


def resolve_api_url(base_url: str) -> str:
    normalized = base_url.rstrip("/")
    return f"{normalized}/chat/completions"


@dataclass(frozen=True)
class JudgeConfig:
    provider: str
    base_url: str
    model: str
    api_key: str
    temperature: float
    max_output_tokens: int
    timeout_seconds: int
    retry_count: int
    request_interval_ms: int


def load_config(path: Path) -> JudgeConfig:
    raw = load_json(path)
    provider = str(raw.get("provider") or "").strip().lower()
    if provider != "deepseek":
        raise ValueError(f"Unsupported judge provider: {provider or 'missing'}")

    api_key = str(raw.get("apiKey") or "").strip()
    api_key_env = str(raw.get("apiKeyEnv") or "").strip()
    if not api_key and api_key_env:
        api_key = str(os.environ.get(api_key_env) or "").strip()
    if not api_key:
        raise ValueError(f"Judge API key is missing. Config path: {path}")

    return JudgeConfig(
        provider=provider,
        base_url=str(raw.get("baseUrl") or "https://api.deepseek.com").strip(),
        model=str(raw.get("model") or "deepseek-reasoner").strip(),
        api_key=api_key,
        temperature=float(raw.get("temperature", 0.0)),
        max_output_tokens=int(raw.get("maxOutputTokens", 4096)),
        timeout_seconds=int(raw.get("timeoutSeconds", 120)),
        retry_count=max(0, int(raw.get("retryCount", 2))),
        request_interval_ms=max(0, int(raw.get("requestIntervalMs", 0))),
    )


def config_fingerprint(config: JudgeConfig) -> str:
    digest = hashlib.sha256()
    digest.update(
        json.dumps(
            {
                "provider": config.provider,
                "baseUrl": config.base_url,
                "model": config.model,
                "temperature": config.temperature,
                "maxOutputTokens": config.max_output_tokens,
                "timeoutSeconds": config.timeout_seconds,
                "retryCount": config.retry_count,
                "promptVersion": PROMPT_VERSION,
            },
            sort_keys=True,
        ).encode("utf-8")
    )
    return digest.hexdigest()


def should_skip_run(run_dir: Path, analysis: dict[str, Any], config: JudgeConfig, force: bool) -> bool:
    if force:
        return False
    summary_path = run_dir / "judge-summary.json"
    if not summary_path.exists():
        return False
    try:
        current = load_json(summary_path)
    except Exception:
        return False
    return (
        current.get("sourceAnalysisGeneratedAtUtc") == analysis.get("generatedAtUtc")
        and current.get("configFingerprint") == config_fingerprint(config)
        and current.get("promptVersion") == PROMPT_VERSION
    )


def summarize_recent_messages(messages: list[dict[str, Any]], limit: int = 6) -> list[dict[str, str]]:
    recent = messages[-limit:]
    return [
        {
            "side": str(message.get("side") or "").lower(),
            "content": truncate_text(message.get("content"), 260),
        }
        for message in recent
    ]


def summarize_memories(memories: list[dict[str, Any]], limit: int = 10) -> list[dict[str, Any]]:
    active = [item for item in memories if normalize_bool(item.get("active"), default=True)]
    ordered = sorted(
        active,
        key=lambda item: (
            float(item.get("confidence") or 0.0),
            int(item.get("updatedAt") or 0),
        ),
        reverse=True,
    )
    return [
        {
            "id": str(item.get("id") or ""),
            "category": str(item.get("category") or ""),
            "confidence": float(item.get("confidence") or 0.0),
            "content": truncate_text(item.get("content"), 200),
        }
        for item in ordered[:limit]
    ]


def build_case_packet(
    *,
    analysis: dict[str, Any],
    manifest_case: dict[str, Any],
    case_result: dict[str, Any],
    case_dir: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    turn_result = ((case_result.get("turnResults") or [])[-1]) if (case_result.get("turnResults") or []) else {}
    manifest_turn = ((manifest_case.get("turns") or [])[-1]) if (manifest_case.get("turns") or []) else {}
    expectations = manifest_turn.get("expectations") or {}
    references = [expectations.get("referenceAnswer")] if expectations.get("referenceAnswer") else []
    references.extend(answer for answer in (expectations.get("acceptableAnswers") or []) if answer)

    messages = load_json(case_dir / "messages.json") if (case_dir / "messages.json").exists() else []
    role_memories = load_json(case_dir / "role-memories.json") if (case_dir / "role-memories.json").exists() else []
    session_memories = load_json(case_dir / "session-memories.json") if (case_dir / "session-memories.json").exists() else []
    summary_payload = load_json(case_dir / "summary.json") if (case_dir / "summary.json").exists() else {}
    events = load_json(case_dir / "events.json") if (case_dir / "events.json").exists() else []

    deterministic_turn = {}
    for turn_metric in analysis.get("turnMetrics") or []:
        if turn_metric.get("caseId") == case_result.get("caseId") and turn_metric.get("turnId") == turn_result.get("turnId"):
            deterministic_turn = turn_metric
            break

    packet = {
        "suiteId": analysis.get("suiteId"),
        "runId": analysis.get("runId"),
        "caseId": case_result.get("caseId"),
        "caseDescription": manifest_case.get("description") or case_result.get("description"),
        "role": {
            "name": truncate_text((manifest_case.get("role") or {}).get("name"), 100),
            "summary": truncate_text((manifest_case.get("role") or {}).get("summary"), 320),
            "personaDescription": truncate_text((manifest_case.get("role") or {}).get("personaDescription"), 400),
            "systemPrompt": truncate_text((manifest_case.get("role") or {}).get("systemPrompt"), 360),
        },
        "probeTurn": {
            "turnId": turn_result.get("turnId"),
            "userInput": truncate_text(turn_result.get("userInput"), 360),
            "assistantAnswer": truncate_text(turn_result.get("assistantContent"), 500),
            "assistantStatus": turn_result.get("assistantStatus"),
            "assistantLatencyMs": turn_result.get("assistantLatencyMs"),
        },
        "referenceAnswers": [truncate_text(reference, 220) for reference in references[:3]],
        "deterministicMetrics": deterministic_turn.get("metrics") or {},
        "summary": {
            "version": summary_payload.get("version"),
            "text": truncate_text(summary_payload.get("summaryText"), 1400),
        },
        "roleMemories": summarize_memories(role_memories, limit=10),
        "sessionMemories": summarize_memories(session_memories, limit=8),
        "recentMessages": summarize_recent_messages(messages, limit=6),
        "eventTypesTail": [str(item.get("eventType") or "") for item in events[-12:]],
    }
    packet_hash = hashlib.sha256(json.dumps(packet, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
    metadata = {
        "referenceCount": len(references),
        "packetHash": packet_hash,
        "roleMemoryCount": len(role_memories),
        "sessionMemoryCount": len(session_memories),
        "eventCount": len(events),
    }
    return packet, metadata


def build_messages(packet: dict[str, Any]) -> list[dict[str, str]]:
    system_prompt = f"""
You are evaluating a roleplay benchmark case and must return valid json only.

Task:
- Judge the final assistant answer in the context of memory-focused product benchmarking.
- Treat deterministic reference answers as primary correctness anchors.
- Use the provided summary, recent messages, and memory artifacts as evidence.
- Be strict about missing recall, contradiction, and stale or irrelevant memory use.
- Keep reasons concise and evidence concrete.

Return a json object with exactly these keys:
{{
  "answer_correctness_score": 1-5,
  "memory_groundedness_score": 1-5,
  "persona_preservation_score": 1-5,
  "naturalness_score": 1-5,
  "used_reference_facts": true,
  "uses_stale_or_irrelevant_memory": false,
  "contradiction_detected": false,
  "reason": "short explanation",
  "evidence": ["short item 1", "short item 2"]
}}

Scoring guide:
- answer_correctness_score: whether the final answer matches the reference answers and user-visible task.
- memory_groundedness_score: whether the answer is supported by the supplied conversation, summary, and memory artifacts.
- persona_preservation_score: whether the answer still sounds like the assigned role.
- naturalness_score: whether the answer is coherent and natural instead of awkwardly stuffed with memory.
""".strip()

    user_prompt = (
        "Judge this benchmark case and return json.\n\n"
        + json.dumps(packet, ensure_ascii=False, indent=2)
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def call_deepseek(config: JudgeConfig, packet: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    url = resolve_api_url(config.base_url)
    messages = build_messages(packet)

    attempts: list[dict[str, Any]] = []
    current_max_tokens = max(1024, config.max_output_tokens)
    for attempt_index in range(config.retry_count + 1):
        started = time.time()
        payload = {
            "model": config.model,
            "messages": messages,
            "temperature": config.temperature,
            "max_tokens": current_max_tokens,
            "response_format": {"type": "json_object"},
        }
        request_body = json.dumps(payload).encode("utf-8")
        try:
            http_request = request.Request(
                url=url,
                data=request_body,
                headers={
                    "Authorization": f"Bearer {config.api_key}",
                    "Content-Type": "application/json",
                },
                method="POST",
            )
            with request.urlopen(http_request, timeout=config.timeout_seconds) as response:
                raw_text = response.read().decode("utf-8")
            response_json = json.loads(raw_text)
            choice = (((response_json.get("choices") or [{}])[0]).get("message") or {})
            content = str(choice.get("content") or "").strip()
            parsed = extract_json_object(content)
            return parsed, {
                "latencyMs": round((time.time() - started) * 1000, 1),
                "attemptIndex": attempt_index,
                "rawResponseLength": len(raw_text),
                "usage": response_json.get("usage") or {},
                "emptyContentRetries": sum(1 for item in attempts if item.get("reason") == "empty_content"),
            }
        except Exception as exc:
            reason = "error"
            if isinstance(exc, ValueError) and "empty content" in str(exc).lower():
                reason = "empty_content"
            if isinstance(exc, error.HTTPError):
                try:
                    error_body = exc.read().decode("utf-8")
                except Exception:
                    error_body = ""
                attempts.append(
                    {
                        "attemptIndex": attempt_index,
                        "reason": "http_error",
                        "status": exc.code,
                        "error": truncate_text(error_body or str(exc), 400),
                    }
                )
            else:
                attempts.append(
                    {
                        "attemptIndex": attempt_index,
                        "reason": reason,
                        "maxTokens": current_max_tokens,
                        "error": truncate_text(str(exc), 400),
                    }
                )
            if attempt_index >= config.retry_count:
                raise RuntimeError(
                    f"DeepSeek judge request failed after {attempt_index + 1} attempt(s): "
                    f"{'; '.join(item.get('error') or str(item) for item in attempts)}"
                ) from exc
            if reason == "empty_content":
                current_max_tokens = min(16384, current_max_tokens * 2)
            time.sleep(1.0)
    raise RuntimeError("DeepSeek judge request failed unexpectedly.")


def normalize_judgment(raw: dict[str, Any]) -> dict[str, Any]:
    normalized = {
        "answerCorrectnessScore": normalize_score(raw.get("answer_correctness_score")),
        "memoryGroundednessScore": normalize_score(raw.get("memory_groundedness_score")),
        "personaPreservationScore": normalize_score(raw.get("persona_preservation_score")),
        "naturalnessScore": normalize_score(raw.get("naturalness_score")),
        "usedReferenceFacts": normalize_bool(raw.get("used_reference_facts")),
        "usesStaleOrIrrelevantMemory": normalize_bool(raw.get("uses_stale_or_irrelevant_memory")),
        "contradictionDetected": normalize_bool(raw.get("contradiction_detected")),
        "reason": normalize_reason(raw.get("reason")),
        "evidence": normalize_evidence(raw.get("evidence")),
    }
    normalized["answerCorrectnessNormalized"] = round(normalized["answerCorrectnessScore"] / 5.0, 4)
    normalized["memoryGroundednessNormalized"] = round(normalized["memoryGroundednessScore"] / 5.0, 4)
    normalized["personaPreservationNormalized"] = round(normalized["personaPreservationScore"] / 5.0, 4)
    normalized["naturalnessNormalized"] = round(normalized["naturalnessScore"] / 5.0, 4)
    return normalized


def analyze_run(run_dir: Path, config: JudgeConfig, force: bool) -> dict[str, Any]:
    analysis = load_json(run_dir / "analysis-summary.json")
    if should_skip_run(run_dir, analysis, config, force=force):
        return load_json(run_dir / "judge-summary.json")

    manifest = load_json(run_dir / "input-manifest.json")
    summary = load_json(run_dir / "run-summary.json")
    manifest_cases = {case["caseId"]: case for case in manifest.get("cases", [])}
    case_results = summary.get("caseResults") or []

    case_judgments: list[dict[str, Any]] = []
    answer_correctness_scores: list[float] = []
    memory_groundedness_scores: list[float] = []
    persona_scores: list[float] = []
    naturalness_scores: list[float] = []
    used_reference_facts: list[float] = []
    stale_memory_flags: list[float] = []
    contradiction_free: list[float] = []
    total_input_tokens = 0
    total_output_tokens = 0
    total_empty_retries = 0

    for case_result in case_results:
        case_id = str(case_result.get("caseId") or "")
        manifest_case = manifest_cases.get(case_id, {})
        case_dir = run_dir / "cases" / case_id
        packet, packet_meta = build_case_packet(
            analysis=analysis,
            manifest_case=manifest_case,
            case_result=case_result,
            case_dir=case_dir,
        )
        raw_judgment, response_meta = call_deepseek(config, packet)
        judgment = normalize_judgment(raw_judgment)
        usage = response_meta.get("usage") or {}
        total_input_tokens += int(usage.get("prompt_tokens") or 0)
        total_output_tokens += int(usage.get("completion_tokens") or 0)
        total_empty_retries += int(response_meta.get("emptyContentRetries") or 0)

        answer_correctness_scores.append(float(judgment["answerCorrectnessNormalized"]))
        memory_groundedness_scores.append(float(judgment["memoryGroundednessNormalized"]))
        persona_scores.append(float(judgment["personaPreservationNormalized"]))
        naturalness_scores.append(float(judgment["naturalnessNormalized"]))
        used_reference_facts.append(1.0 if judgment["usedReferenceFacts"] else 0.0)
        stale_memory_flags.append(1.0 if judgment["usesStaleOrIrrelevantMemory"] else 0.0)
        contradiction_free.append(0.0 if judgment["contradictionDetected"] else 1.0)

        case_judgments.append(
            {
                "caseId": case_id,
                "turnId": packet["probeTurn"]["turnId"],
                "packetHash": packet_meta["packetHash"],
                "packetMeta": packet_meta,
                "deterministicMetrics": packet.get("deterministicMetrics") or {},
                "probeTurn": packet.get("probeTurn") or {},
                "judgment": judgment,
                "responseMeta": response_meta,
            }
        )
        if config.request_interval_ms > 0:
            time.sleep(config.request_interval_ms / 1000.0)

    aggregate = {
        "answerCorrectness": safe_mean(answer_correctness_scores),
        "memoryGroundedness": safe_mean(memory_groundedness_scores),
        "personaPreservation": safe_mean(persona_scores),
        "naturalness": safe_mean(naturalness_scores),
        "usedReferenceFactsRate": safe_mean(used_reference_facts),
        "staleOrIrrelevantMemoryRate": safe_mean(stale_memory_flags),
        "contradictionFreeRate": safe_mean(contradiction_free),
        "totalPromptTokens": total_input_tokens,
        "totalCompletionTokens": total_output_tokens,
        "emptyContentRetryCount": total_empty_retries,
    }

    summary_payload = {
        "judgeVersion": 1,
        "generatedAtUtc": utc_now(),
        "promptVersion": PROMPT_VERSION,
        "sourceAnalysisGeneratedAtUtc": analysis.get("generatedAtUtc"),
        "configFingerprint": config_fingerprint(config),
        "provider": config.provider,
        "model": config.model,
        "baseUrl": config.base_url,
        "runId": analysis.get("runId"),
        "suiteId": analysis.get("suiteId"),
        "caseCount": len(case_results),
        "judgedCaseCount": len(case_judgments),
        "aggregate": aggregate,
        "caseJudgments": case_judgments,
    }

    (run_dir / "judge-summary.json").write_text(
        json.dumps(summary_payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    markdown = (
        "# Roleplay Eval Judge Summary\n\n"
        f"- runId: `{summary_payload['runId']}`\n"
        f"- suiteId: `{summary_payload['suiteId']}`\n"
        f"- provider: `{config.provider}`\n"
        f"- model: `{config.model}`\n"
        f"- judgedCases: `{summary_payload['judgedCaseCount']}`\n"
        f"- answer_correctness: `{render_metric(aggregate['answerCorrectness'])}`\n"
        f"- memory_groundedness: `{render_metric(aggregate['memoryGroundedness'])}`\n"
        f"- persona_preservation: `{render_metric(aggregate['personaPreservation'])}`\n"
        f"- naturalness: `{render_metric(aggregate['naturalness'])}`\n"
        f"- used_reference_facts_rate: `{render_metric(aggregate['usedReferenceFactsRate'])}`\n"
        f"- contradiction_free_rate: `{render_metric(aggregate['contradictionFreeRate'])}`\n"
    )
    (run_dir / "judge-summary.md").write_text(markdown, encoding="utf-8")
    return summary_payload


def render_comparison_markdown(rows: list[dict[str, Any]]) -> str:
    if not rows:
        return "| Run | Suite | Judge | Answer | Grounded | Persona | Natural | Ref Facts | Contradiction Free |\n| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n| - | - | - | - | - | - | - | - | - |\n"
    header = "| Run | Suite | Judge | Answer | Grounded | Persona | Natural | Ref Facts | Contradiction Free |\n"
    separator = "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n"
    lines = [header, separator]
    for row in rows:
        lines.append(
            "| {runId} | {suiteId} | {model} | {answer} | {grounded} | {persona} | {natural} | {facts} | {contradiction_free} |\n".format(
                runId=row["runId"],
                suiteId=row["suiteId"],
                model=row["model"],
                answer=render_metric(row["answerCorrectness"]),
                grounded=render_metric(row["memoryGroundedness"]),
                persona=render_metric(row["personaPreservation"]),
                natural=render_metric(row["naturalness"]),
                facts=render_metric(row["usedReferenceFactsRate"]),
                contradiction_free=render_metric(row["contradictionFreeRate"]),
            )
        )
    return "".join(lines)


def write_comparison(reports_root: Path) -> None:
    run_dirs = sorted(
        path for path in reports_root.iterdir() if path.is_dir() and (path / "judge-summary.json").exists()
    )
    summaries = [load_json(path / "judge-summary.json") for path in run_dirs]
    rows = [
        {
            "runId": summary.get("runId"),
            "suiteId": summary.get("suiteId"),
            "model": summary.get("model"),
            "provider": summary.get("provider"),
            "answerCorrectness": (summary.get("aggregate") or {}).get("answerCorrectness"),
            "memoryGroundedness": (summary.get("aggregate") or {}).get("memoryGroundedness"),
            "personaPreservation": (summary.get("aggregate") or {}).get("personaPreservation"),
            "naturalness": (summary.get("aggregate") or {}).get("naturalness"),
            "usedReferenceFactsRate": (summary.get("aggregate") or {}).get("usedReferenceFactsRate"),
            "contradictionFreeRate": (summary.get("aggregate") or {}).get("contradictionFreeRate"),
        }
        for summary in summaries
    ]
    payload = {
        "comparisonVersion": 1,
        "generatedAtUtc": utc_now(),
        "runCount": len(summaries),
        "runs": summaries,
    }
    (reports_root / "judge-comparison.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (reports_root / "judge-comparison.md").write_text(render_comparison_markdown(rows), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run an optional LLM judge pass over pulled roleplay evaluation reports.")
    parser.add_argument("--reports-root", type=Path, default=DEFAULT_REPORTS_ROOT)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    parser.add_argument("--run-dir", type=Path, action="append", dest="run_dirs")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    reports_root = args.reports_root.resolve()
    config_path = args.config.resolve()
    if not config_path.exists():
        raise FileNotFoundError(f"Judge config not found: {config_path}")

    config = load_config(config_path)
    run_dirs = [path.resolve() for path in args.run_dirs] if args.run_dirs else sorted(
        path
        for path in reports_root.iterdir()
        if path.is_dir() and (path / "analysis-summary.json").exists()
    )
    if not run_dirs:
        raise FileNotFoundError(f"No analyzed run directories found under {reports_root}")

    for run_dir in run_dirs:
        analyze_run(run_dir, config=config, force=args.force)
    write_comparison(reports_root)
    print(f"judged {len(run_dirs)} run(s) under {reports_root}")


if __name__ == "__main__":
    main()
