#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


ROOT_DIR = Path(__file__).resolve().parents[1]
SCENARIOS_PUBLIC_DIR = ROOT_DIR / "scenarios" / "public"
SCHEMA_REF = "../roleplay-eval.schema.json"
DEFAULT_CASES_PER_SUITE = 12
LONGMEMEVAL_MAX_SESSIONS = 12
LONGMEMEVAL_NEIGHBOR_RADIUS = 1
LONGMEMEVAL_RECENT_SESSION_TAIL = 2


@dataclass(frozen=True)
class SuiteSpec:
    suite_id: str
    dataset: str
    source_label: str
    language: str
    scorer_names: list[str]
    build_cases: Callable[[int], list[dict[str, Any]]]
    notes: str


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sanitize_slug(value: str) -> str:
    normalized = re.sub(r"[^a-zA-Z0-9]+", "_", value.strip())
    normalized = normalized.strip("_").lower()
    return normalized or "item"


def assistant_role_template(
    *,
    name: str,
    summary: str,
    system_prompt: str,
    persona_description: str = "",
    world_settings: str = "",
    opening_line: str = "",
    tags: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "name": name,
        "summary": summary,
        "systemPrompt": system_prompt,
        "personaDescription": persona_description,
        "worldSettings": world_settings,
        "openingLine": opening_line,
        "exampleDialogues": [],
        "safetyPolicy": "",
        "memoryEnabled": True,
        "memoryMaxItems": 8,
        "summaryTurnThreshold": 1,
        "tags": tags or [],
    }


def default_case_expectations() -> dict[str, Any]:
    return {
        "summaryText": None,
        "memoryText": None,
        "requiredEventTypes": ["SUMMARY_UPDATE"],
        "minMemoryCount": None,
        "minCompletedAssistantMessages": 1,
        "minSummaryVersion": 1,
    }


def turn_expectations(
    *,
    reference_answer: str | None,
    scorer: str,
    acceptable_answers: list[str] | None = None,
    min_chars: int = 1,
) -> dict[str, Any]:
    return {
        "assistantText": None,
        "expectedAssistantStatus": "COMPLETED",
        "minAssistantChars": min_chars,
        "maxAssistantChars": None,
        "referenceAnswer": reference_answer,
        "acceptableAnswers": acceptable_answers or [],
        "scorer": scorer,
    }


def flatten_longmemeval_seed_turns(haystack_sessions: list[list[dict[str, str]]]) -> list[dict[str, str]]:
    seed_turns: list[dict[str, str]] = []
    for session in haystack_sessions:
        for message in session:
            side = "assistant" if message["role"] == "assistant" else "user"
            seed_turns.append({"side": side, "content": message["content"]})
    return seed_turns


def select_longmemeval_sessions(
    record: dict[str, Any],
    *,
    max_sessions: int = LONGMEMEVAL_MAX_SESSIONS,
    neighbor_radius: int = LONGMEMEVAL_NEIGHBOR_RADIUS,
    recent_tail: int = LONGMEMEVAL_RECENT_SESSION_TAIL,
) -> list[list[dict[str, str]]]:
    sessions = record["haystack_sessions"]
    session_ids = record["haystack_session_ids"]
    if len(sessions) <= max_sessions:
        return sessions

    selected_indexes: set[int] = set()
    answer_session_ids = set(record.get("answer_session_ids", []))
    answer_indexes = [index for index, session_id in enumerate(session_ids) if session_id in answer_session_ids]

    for answer_index in answer_indexes:
        start = max(0, answer_index - neighbor_radius)
        end = min(len(sessions), answer_index + neighbor_radius + 1)
        selected_indexes.update(range(start, end))

    selected_indexes.update(range(max(0, len(sessions) - recent_tail), len(sessions)))

    if not selected_indexes and sessions:
        selected_indexes.add(len(sessions) - 1)

    remaining_indexes = [index for index in range(len(sessions)) if index not in selected_indexes]
    target_count = min(max_sessions, len(sessions))
    while len(selected_indexes) < target_count and remaining_indexes:
        slots = target_count - len(selected_indexes)
        if slots >= len(remaining_indexes):
            selected_indexes.update(remaining_indexes)
            break

        step = len(remaining_indexes) / slots
        sampled_indexes = {
            remaining_indexes[min(len(remaining_indexes) - 1, int(round(step * slot_index)))]
            for slot_index in range(slots)
        }
        if not sampled_indexes:
            break

        selected_indexes.update(sampled_indexes)
        remaining_indexes = [index for index in remaining_indexes if index not in selected_indexes]

    return [sessions[index] for index in sorted(selected_indexes)]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def build_longmemeval_cases(max_cases: int) -> list[dict[str, Any]]:
    dataset_path = ROOT_DIR / "cache" / "huggingface" / "LongMemEval-cleaned" / "longmemeval_s_cleaned.json"
    records = load_json(dataset_path)
    buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        buckets[record["question_type"]].append(record)

    ordered_keys = sorted(buckets)
    selected: list[dict[str, Any]] = []
    index = 0
    while len(selected) < max_cases and any(buckets.values()):
        bucket = buckets[ordered_keys[index % len(ordered_keys)]]
        if bucket:
            selected.append(bucket.pop(0))
        index += 1

    cases: list[dict[str, Any]] = []
    for record in selected:
        case_id = f"longmemeval_{sanitize_slug(record['question_type'])}_{record['question_id']}"
        selected_sessions = select_longmemeval_sessions(record)
        cases.append(
            {
                "caseId": case_id,
                "description": (
                    f"LongMemEval {record['question_type']} question_id={record['question_id']} "
                    f"selected_sessions={len(selected_sessions)}/{len(record['haystack_sessions'])}"
                ),
                "role": assistant_role_template(
                    name="Long Memory Companion",
                    summary="A continuity-focused assistant that answers follow-up questions from the full conversation history.",
                    system_prompt=(
                        "Stay consistent with the prior conversation, answer the user's follow-up question directly, "
                        "and rely on preserved conversation details rather than inventing new facts."
                    ),
                    persona_description="Reliable, concise, and focused on faithful recall from earlier dialogue.",
                    world_settings=f"Question type: {record['question_type']}. Question date: {record['question_date']}.",
                    tags=["public", "longmemeval", "memory", sanitize_slug(record["question_type"])],
                ),
                "userProfile": {
                    "userName": "User",
                    "personaDescription": "An existing chat partner continuing a long-running conversation.",
                    "personaTitle": "Conversation Partner",
                    "personaDescriptionPositionRaw": 0,
                    "personaDescriptionDepth": 2,
                    "personaDescriptionRole": 0,
                },
                "bootstrapSummaryFromSeed": True,
                "bootstrapMemoriesFromSeed": True,
                "seedTurns": flatten_longmemeval_seed_turns(selected_sessions),
                "turns": [
                    {
                        "turnId": "qa_turn",
                        "userInput": record["question"],
                        "expectations": turn_expectations(
                            reference_answer=record["answer"],
                            acceptable_answers=[],
                            scorer="qa_em_f1",
                            min_chars=1,
                        ),
                    }
                ],
                "expectations": default_case_expectations(),
            }
        )
    return cases


def flatten_locomo_conversation(sample: dict[str, Any]) -> tuple[str, str, list[dict[str, str]]]:
    conversation = sample["conversation"]
    speaker_a = conversation["speaker_a"]
    speaker_b = conversation["speaker_b"]
    seed_turns: list[dict[str, str]] = []
    session_keys = sorted(
        [key for key in conversation if re.fullmatch(r"session_\d+", key)],
        key=lambda value: int(value.split("_")[1]),
    )
    for session_key in session_keys:
        for turn in conversation[session_key]:
            side = "assistant" if turn["speaker"] == speaker_a else "user"
            seed_turns.append({"side": side, "content": turn["text"]})
    return speaker_a, speaker_b, seed_turns


def build_locomo_cases(max_cases: int) -> list[dict[str, Any]]:
    dataset_path = ROOT_DIR / "external" / "LoCoMo" / "data" / "locomo10.json"
    samples = load_json(dataset_path)
    qa_pool: dict[int, list[tuple[dict[str, Any], dict[str, Any]]]] = defaultdict(list)
    for sample in samples:
        for qa in sample["qa"]:
            if "answer" not in qa or not str(qa["answer"]).strip():
                continue
            qa_pool[int(qa["category"])].append((sample, qa))

    ordered_categories = sorted(qa_pool)
    selected: list[tuple[dict[str, Any], dict[str, Any]]] = []
    index = 0
    while len(selected) < max_cases and any(qa_pool.values()):
        bucket = qa_pool[ordered_categories[index % len(ordered_categories)]]
        if bucket:
            selected.append(bucket.pop(0))
        index += 1

    cases: list[dict[str, Any]] = []
    for sample, qa in selected:
        speaker_a, speaker_b, seed_turns = flatten_locomo_conversation(sample)
        evidence = ",".join(qa.get("evidence", []))
        case_id = f"locomo_{sample['sample_id']}_cat{qa['category']}_{sanitize_slug(qa['question'])[:24]}"
        cases.append(
            {
                "caseId": case_id,
                "description": (
                    f"LoCoMo sample={sample['sample_id']} category={qa['category']} evidence={evidence or 'none'}"
                ),
                "role": assistant_role_template(
                    name=speaker_a,
                    summary=f"{speaker_a} continuing a very long-term conversation with {speaker_b}.",
                    system_prompt=(
                        f"You are {speaker_a}. Continue the long-running conversation with {speaker_b}, "
                        "answer follow-up questions faithfully from what happened before, and stay consistent with prior facts."
                    ),
                    persona_description="Grounded in prior dialogue and event continuity.",
                    world_settings=f"Conversation participants: {speaker_a} and {speaker_b}.",
                    tags=["public", "locomo", "memory", f"category_{qa['category']}"],
                ),
                "userProfile": {
                    "userName": speaker_b,
                    "personaDescription": f"{speaker_b} is the ongoing conversation partner.",
                    "personaTitle": "Conversation Partner",
                    "personaDescriptionPositionRaw": 0,
                    "personaDescriptionDepth": 2,
                    "personaDescriptionRole": 0,
                },
                "bootstrapSummaryFromSeed": True,
                "bootstrapMemoriesFromSeed": True,
                "seedTurns": seed_turns,
                "turns": [
                    {
                        "turnId": "qa_turn",
                        "userInput": qa["question"],
                        "expectations": turn_expectations(
                            reference_answer=qa["answer"],
                            acceptable_answers=[],
                            scorer="qa_em_f1",
                            min_chars=1,
                        ),
                    }
                ],
                "expectations": default_case_expectations(),
            }
        )
    return cases


def build_characterbench_case(
    record: dict[str, Any],
    *,
    suite_tag: str,
    reference_answer: str,
    scorer: str,
    case_suffix: str,
) -> dict[str, Any]:
    personality = record.get("性格")
    if isinstance(personality, list):
        persona_description = "；".join(personality)
    else:
        persona_description = record.get("character_profile", "")

    works = record.get("登场作品")
    if isinstance(works, list):
        world_settings = "登场作品：" + "、".join(works[:3])
    else:
        world_settings = ""

    user_name = record.get("user_name") or "用户"
    if user_name in {"None", "N/A"}:
        user_name = "用户"

    seed_turns = [
        {
            "side": "assistant" if turn["speaker"] == "character" else "user",
            "content": turn["utterance"],
        }
        for turn in record["dialogue"]
    ]

    final_question = record["messages"]["response"]
    return {
        "caseId": f"characterbench_{suite_tag}_{record['id']}_{case_suffix}",
        "description": f"CharacterBench {suite_tag} character={record['character_name']} id={record['id']}",
        "role": assistant_role_template(
            name=record["character_name"],
            summary=record.get("character_profile", "").strip(),
            system_prompt=(
                f"你现在扮演{record['character_name']}。保持人物设定、语气与此前对话连续性，"
                "只根据角色资料和之前的对话回答，不要跳出角色。"
            ),
            persona_description=persona_description,
            world_settings=world_settings,
            opening_line=record.get("greeting", ""),
            tags=["public", "characterbench", suite_tag],
        ),
        "userProfile": {
            "userName": user_name,
            "personaDescription": record.get("user_profile", "") if isinstance(record.get("user_profile"), str) else "",
            "personaTitle": record.get("played_relation", "") or record.get("social_relation", ""),
            "personaDescriptionPositionRaw": 0,
            "personaDescriptionDepth": 2,
            "personaDescriptionRole": 0,
        },
        "bootstrapSummaryFromSeed": True,
        "bootstrapMemoriesFromSeed": True,
        "seedTurns": seed_turns,
        "turns": [
            {
                "turnId": "qa_turn",
                "userInput": final_question,
                "expectations": turn_expectations(
                    reference_answer=reference_answer,
                    acceptable_answers=[],
                    scorer=scorer,
                    min_chars=1,
                ),
            }
        ],
        "expectations": default_case_expectations(),
    }


def build_characterbench_memory_cases(max_cases: int) -> list[dict[str, Any]]:
    dataset_path = ROOT_DIR / "external" / "CharacterBench" / "eval_data" / "raw_data" / "memory_consistency_test.json"
    records = load_json(dataset_path)
    selected = records[:max_cases]
    cases = []
    for record in selected:
        segments = record["messages"]["output"].get("dialogue_segments", [])
        reference = " ".join(segment.strip() for segment in segments if segment.strip())
        if not reference:
            reference = record.get("character_profile", "")
        cases.append(
            build_characterbench_case(
                record,
                suite_tag="memory_consistency",
                reference_answer=reference,
                scorer="reference_token_f1",
                case_suffix="memory",
            )
        )
    return cases


def build_characterbench_fact_cases(max_cases: int) -> list[dict[str, Any]]:
    dataset_path = ROOT_DIR / "external" / "CharacterBench" / "eval_data" / "raw_data" / "fact_accuracy_test.json"
    records = load_json(dataset_path)
    selected = records[:max_cases]
    cases = []
    for record in selected:
        reference = record["messages"]["output"].get("answer", "").strip()
        if not reference:
            continue
        cases.append(
            build_characterbench_case(
                record,
                suite_tag="fact_accuracy",
                reference_answer=reference,
                scorer="qa_em_f1",
                case_suffix="fact",
            )
        )
    return cases[:max_cases]


def suite_manifest(*, suite_id: str, run_label: str, cases: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "$schema": SCHEMA_REF,
        "manifestVersion": 1,
        "suiteId": suite_id,
        "runLabel": run_label,
        "modelName": "",
        "cleanupAfterRun": False,
        "cases": cases,
    }


def specs() -> list[SuiteSpec]:
    return [
        SuiteSpec(
            suite_id="characterbench_memory_consistency_public_mini",
            dataset="CharacterBench",
            source_label="eval_data/raw_data/memory_consistency_test.json",
            language="zh",
            scorer_names=["reference_token_f1"],
            build_cases=build_characterbench_memory_cases,
            notes="Public persona-memory suite derived from CharacterBench memory consistency raw data.",
        ),
        SuiteSpec(
            suite_id="characterbench_fact_accuracy_public_mini",
            dataset="CharacterBench",
            source_label="eval_data/raw_data/fact_accuracy_test.json",
            language="zh",
            scorer_names=["qa_em_f1"],
            build_cases=build_characterbench_fact_cases,
            notes="Public persona factual QA suite derived from CharacterBench fact accuracy raw data.",
        ),
        SuiteSpec(
            suite_id="longmemeval_s_public_mini",
            dataset="LongMemEval",
            source_label="cache/huggingface/LongMemEval-cleaned/longmemeval_s_cleaned.json",
            language="en",
            scorer_names=["qa_em_f1"],
            build_cases=build_longmemeval_cases,
            notes="Balanced long-memory QA suite from LongMemEval single-model cleaned split using evidence-anchored session windows for on-device feasibility.",
        ),
        SuiteSpec(
            suite_id="locomo_qa_public_mini",
            dataset="LoCoMo",
            source_label="external/LoCoMo/data/locomo10.json",
            language="en",
            scorer_names=["qa_em_f1"],
            build_cases=build_locomo_cases,
            notes="Balanced LoCoMo long-conversation QA suite across categories.",
        ),
    ]


def write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build tracked public roleplay benchmark manifests.")
    parser.add_argument(
        "--suite",
        action="append",
        dest="suite_ids",
        help="Suite id to build. Repeat for multiple suites. Defaults to all.",
    )
    parser.add_argument(
        "--max-cases",
        type=int,
        default=DEFAULT_CASES_PER_SUITE,
        help=f"Maximum cases per suite. Default: {DEFAULT_CASES_PER_SUITE}",
    )
    args = parser.parse_args()

    selected_specs = [spec for spec in specs() if not args.suite_ids or spec.suite_id in args.suite_ids]
    if not selected_specs:
        raise SystemExit("No matching suite ids were selected.")

    SCENARIOS_PUBLIC_DIR.mkdir(parents=True, exist_ok=True)

    catalog = {
        "catalogVersion": 1,
        "generatedAtUtc": utc_now(),
        "suites": [],
    }

    for spec in selected_specs:
        cases = spec.build_cases(args.max_cases)
        manifest = suite_manifest(
            suite_id=spec.suite_id,
            run_label=f"{spec.dataset} public mini suite",
            cases=cases,
        )
        manifest_path = SCENARIOS_PUBLIC_DIR / f"{spec.suite_id}.json"
        write_manifest(manifest_path, manifest)
        catalog["suites"].append(
            {
                "suiteId": spec.suite_id,
                "dataset": spec.dataset,
                "source": spec.source_label,
                "language": spec.language,
                "manifestPath": str(manifest_path.relative_to(ROOT_DIR.parent.parent).as_posix()),
                "caseCount": len(cases),
                "scorers": spec.scorer_names,
                "notes": spec.notes,
            }
        )
        print(f"wrote {manifest_path} ({len(cases)} cases)")

    catalog_path = SCENARIOS_PUBLIC_DIR / "catalog.json"
    write_manifest(catalog_path, catalog)
    print(f"wrote {catalog_path}")


if __name__ == "__main__":
    main()
