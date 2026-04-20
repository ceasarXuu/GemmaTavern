#!/usr/bin/env python
from __future__ import annotations

import argparse
import html
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROLEPLAY_ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROLEPLAY_ROOT.parent.parent
DEFAULT_REPORTS_ROOT = ROLEPLAY_ROOT / "reports"
DEFAULT_CATALOG_PATH = ROLEPLAY_ROOT / "scenarios" / "public" / "catalog.json"
DEFAULT_BATCH_SUMMARY_PATH = DEFAULT_REPORTS_ROOT / "batch-summary.json"
DEFAULT_OUTPUT_PATH = DEFAULT_REPORTS_ROOT / "roleplay-benchmark-report.html"
TZ_CN = timezone(timedelta(hours=8))


SUITE_NOTE_ZH = {
    "characterbench_memory_consistency_public_mini": "基于 CharacterBench memory consistency 原始数据构建的公开人格记忆套件。",
    "characterbench_fact_accuracy_public_mini": "基于 CharacterBench fact accuracy 原始数据构建的公开设定事实问答套件。",
    "longmemeval_s_public_mini": "从 LongMemEval 单模型清洗子集构建的长程记忆问答套件，使用证据锚定的 session window 控制端侧可运行性。",
    "locomo_qa_public_mini": "覆盖多个问题类别的 LoCoMo 长对话问答套件。",
}

STATUS_ZH = {
    "SUCCEEDED": "成功",
    "FAILED": "失败",
    "COMPLETED": "完成",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def safe_mean(values: list[float]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def esc(value: Any) -> str:
    return html.escape("" if value is None else str(value))


def bi(en: str, zh: str) -> str:
    return (
        f'<span class="i18n i18n-en" lang="en">{esc(en)}</span>'
        f'<span class="i18n i18n-zh" lang="zh-CN">{esc(zh)}</span>'
    )


def fmt_time(value: str | None) -> str:
    dt = parse_time(value)
    if dt is None:
        return "-"
    return dt.astimezone(TZ_CN).strftime("%Y-%m-%d %H:%M:%S UTC+08")


def fmt_num(value: float | int | None, digits: int = 1) -> str:
    if value is None:
        return "-"
    if isinstance(value, int):
        return str(value)
    return f"{value:.{digits}f}"


def fmt_pct(value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value * 100:.1f}% ({value:.3f})"


def fmt_score_5(value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value * 5:.2f}/5"


def fmt_ms_pair(value: float | None) -> tuple[str, str]:
    if value is None:
        return "-", "-"
    if value >= 1000:
        seconds = value / 1000
        return f"{seconds:.1f} s", f"{seconds:.1f} 秒"
    return f"{value:.0f} ms", f"{value:.0f} 毫秒"


def fmt_duration_pair(start: str | None, end: str | None) -> tuple[str, str]:
    start_dt = parse_time(start)
    end_dt = parse_time(end)
    if start_dt is None or end_dt is None:
        return "-", "-"
    seconds = max(0.0, (end_dt - start_dt).total_seconds())
    if seconds >= 60:
        minutes = seconds / 60
        return f"{minutes:.1f} min", f"{minutes:.1f} 分钟"
    return f"{seconds:.1f} s", f"{seconds:.1f} 秒"


def rel_link(base_dir: Path, target: Path) -> str:
    return html.escape(os.path.relpath(target, base_dir).replace("\\", "/"))


def suite_metric_key(spec: dict[str, Any]) -> str:
    scorers = spec.get("scorers") or []
    return "reference_token_f1" if "reference_token_f1" in scorers else "qa_f1"


def suite_metric_label_pair(key: str) -> tuple[str, str]:
    if key == "reference_token_f1":
        return "Reference Token F1", "参考 Token F1"
    return "QA F1", "问答 F1"


def suite_note_zh(suite_id: str, fallback: str) -> str:
    return SUITE_NOTE_ZH.get(suite_id, fallback)


def status_label_pair(status: str) -> tuple[str, str]:
    return status, STATUS_ZH.get(status, status)


def bar(value: float | None) -> str:
    width = 0.0 if value is None else max(0.0, min(100.0, value * 100.0))
    return f'<div class="bar-shell"><div class="bar" style="width:{width:.1f}%"></div></div>'


def spark(values: list[float | None], color: str) -> str:
    valid = [value for value in values if value is not None]
    if not valid:
        return '<svg class="spark" viewBox="0 0 180 48"><rect width="180" height="48" rx="12" fill="rgba(15,24,35,.05)"></rect></svg>'
    lo, hi = min(valid), max(valid)
    spread = hi - lo or 1.0
    count = max(2, len(values))
    pts = []
    for idx, value in enumerate(values):
        if value is None:
            continue
        x = 10 + idx * (160 / (count - 1))
        y = 8 + 32 * (1 - ((value - lo) / spread))
        pts.append((x, y))
    if len(pts) == 1:
        pts.append((170, pts[0][1]))
    line = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    area = " ".join(["10,40"] + [f"{x:.1f},{y:.1f}" for x, y in pts] + ["170,40"])
    return (
        f'<svg class="spark" viewBox="0 0 180 48">'
        '<rect width="180" height="48" rx="12" fill="rgba(15,24,35,.05)"></rect>'
        f'<polygon points="{area}" fill="{color}" opacity=".12"></polygon>'
        f'<polyline points="{line}" fill="none" stroke="{color}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"></polyline>'
        f'<circle cx="{pts[-1][0]:.1f}" cy="{pts[-1][1]:.1f}" r="3.2" fill="{color}"></circle>'
        '</svg>'
    )


def best_or_worst_label(item: tuple[str, float] | None) -> str:
    if item is None:
        return "-"
    return f"{item[0]} / {item[1]:.3f}"


def build_runs(reports_root: Path, comparison: dict[str, Any], catalog_map: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    runs: list[dict[str, Any]] = []
    for analysis in comparison.get("runs", []):
        run_id = analysis["runId"]
        run_dir = reports_root / run_id
        host = load_json(run_dir / "host-run.json") if (run_dir / "host-run.json").exists() else {}
        summary = load_json(run_dir / "run-summary.json") if (run_dir / "run-summary.json").exists() else {}
        judge_summary = load_json(run_dir / "judge-summary.json") if (run_dir / "judge-summary.json").exists() else None
        spec = catalog_map.get(analysis["suiteId"], {})
        metric_key = suite_metric_key(spec)
        turn_scores: list[tuple[str, float]] = []
        for turn in analysis.get("turnMetrics") or []:
            metrics = turn.get("metrics") or {}
            score = metrics.get(metric_key)
            if score is None and metric_key != "reference_token_f1":
                score = metrics.get("reference_token_f1")
            if score is not None:
                turn_scores.append((turn.get("caseId") or "-", float(score)))
        turn_scores.sort(key=lambda item: item[1])
        runs.append(
            {
                **analysis,
                "dataset": spec.get("dataset", "Custom"),
                "language": spec.get("language", "-"),
                "notes": spec.get("notes", ""),
                "metricKey": metric_key,
                "metricValue": (analysis.get("metrics") or {}).get(metric_key),
                "startedAtUtc": host.get("startedAtUtc"),
                "finishedAtUtc": host.get("finishedAtUtc"),
                "deviceSerial": host.get("deviceSerial"),
                "appVersionName": summary.get("appVersionName"),
                "appVersionCode": summary.get("appVersionCode"),
                "deviceModel": summary.get("deviceModel"),
                "bestCase": turn_scores[-1] if turn_scores else None,
                "worstCase": turn_scores[0] if turn_scores else None,
                "judgeSummary": judge_summary,
                "judgeAggregate": (judge_summary or {}).get("aggregate") or {},
                "judgeModel": (judge_summary or {}).get("model"),
                "runPath": run_dir,
                "analysisPath": run_dir / "analysis-summary.json",
            }
        )
    return runs


def render(output_path: Path, reports_root: Path, catalog: dict[str, Any], batch_summary: dict[str, Any] | None, runs: list[dict[str, Any]]) -> str:
    suites = catalog.get("suites", [])
    ordered_runs = sorted(
        runs,
        key=lambda run: (
            parse_time(run.get("startedAtUtc")) or parse_time(run.get("generatedAtUtc")) or datetime.min.replace(tzinfo=timezone.utc),
            run["runId"],
        ),
    )
    latest_by_suite: dict[str, dict[str, Any]] = {}
    history_by_suite: dict[str, list[dict[str, Any]]] = {}
    for run in ordered_runs:
        latest_by_suite[run["suiteId"]] = run
        history_by_suite.setdefault(run["suiteId"], []).append(run)

    latest_runs = [latest_by_suite[suite["suiteId"]] for suite in suites if suite["suiteId"] in latest_by_suite]
    status_map = {
        item.get("suiteId"): item.get("status")
        for item in (batch_summary or {}).get("suiteResults", [])
        if isinstance(item, dict)
    }
    total_cases = sum(int(run.get("caseCount") or 0) for run in latest_runs)
    total_passed = sum(int(run.get("passedCaseCount") or 0) for run in latest_runs)
    total_failed_assertions = sum(int(run.get("failedAssertionCount") or 0) for run in latest_runs)
    succeeded = sum(1 for run in latest_runs if status_map.get(run["suiteId"], "SUCCEEDED") == "SUCCEEDED")
    batch_start = min((parse_time(run.get("startedAtUtc")) for run in latest_runs if parse_time(run.get("startedAtUtc"))), default=None)
    batch_finish = max((parse_time(run.get("finishedAtUtc")) for run in latest_runs if parse_time(run.get("finishedAtUtc"))), default=None)
    env_run = max(
        latest_runs,
        key=lambda run: parse_time(run.get("finishedAtUtc")) or datetime.min.replace(tzinfo=timezone.utc),
    ) if latest_runs else None
    best_run = max((run for run in latest_runs if run.get("metricValue") is not None), key=lambda run: run["metricValue"], default=None)
    worst_run = min((run for run in latest_runs if run.get("metricValue") is not None), key=lambda run: run["metricValue"], default=None)
    zero_mem = [run for run in latest_runs if (run.get("avgRoleMemoryCount") or 0) <= 0]
    judge_runs = [run for run in latest_runs if run.get("judgeAggregate")]
    judge_enabled = len(judge_runs) > 0
    judge_answer = safe_mean(
        [float(run["judgeAggregate"].get("answerCorrectness")) for run in judge_runs if run["judgeAggregate"].get("answerCorrectness") is not None]
    )
    judge_grounded = safe_mean(
        [float(run["judgeAggregate"].get("memoryGroundedness")) for run in judge_runs if run["judgeAggregate"].get("memoryGroundedness") is not None]
    )
    judge_persona = safe_mean(
        [float(run["judgeAggregate"].get("personaPreservation")) for run in judge_runs if run["judgeAggregate"].get("personaPreservation") is not None]
    )
    judge_natural = safe_mean(
        [float(run["judgeAggregate"].get("naturalness")) for run in judge_runs if run["judgeAggregate"].get("naturalness") is not None]
    )
    judge_ref_facts = safe_mean(
        [float(run["judgeAggregate"].get("usedReferenceFactsRate")) for run in judge_runs if run["judgeAggregate"].get("usedReferenceFactsRate") is not None]
    )
    judge_contradiction_free = safe_mean(
        [float(run["judgeAggregate"].get("contradictionFreeRate")) for run in judge_runs if run["judgeAggregate"].get("contradictionFreeRate") is not None]
    )
    judge_model = judge_runs[0].get("judgeModel") if judge_enabled else None

    best_metric_en, best_metric_zh = suite_metric_label_pair(best_run["metricKey"]) if best_run else ("Primary Metric", "主指标")
    worst_metric_en, worst_metric_zh = suite_metric_label_pair(worst_run["metricKey"]) if worst_run else ("Primary Metric", "主指标")
    findings = [
        (
            f"This batch covers {len(latest_runs)} public mini suites, with {succeeded}/{len(latest_runs)} suite-level success.",
            f"本轮批次覆盖 {len(latest_runs)} 个公开 mini suite，其中 {succeeded}/{len(latest_runs)} 个 suite 执行成功。",
        ),
        (
            f"The batch executed {total_cases} cases in total, passed {total_passed}, and recorded {total_failed_assertions} failed assertions.",
            f"本轮共执行 {total_cases} 个 case，通过 {total_passed} 个，失败断言 {total_failed_assertions} 个。",
        ),
        (
            f"The highest primary score is on {best_run['suiteId']}: {best_metric_en} = {fmt_pct(best_run['metricValue'])}."
            if best_run
            else "No primary metric is currently available.",
            f"当前主指标最高的套件是 {best_run['suiteId']}：{best_metric_zh} = {fmt_pct(best_run['metricValue'])}。"
            if best_run
            else "当前还没有可用的主指标结果。",
        ),
        (
            f"The weakest suite is {worst_run['suiteId']} at {fmt_pct(worst_run['metricValue'])}, which confirms that long-horizon recall remains the main bottleneck."
            if worst_run
            else "No weak suite could be identified yet.",
            f"当前最弱项是 {worst_run['suiteId']}，得分 {fmt_pct(worst_run['metricValue'])}，说明长程回忆仍是主要瓶颈。"
            if worst_run
            else "当前还没有识别出明确的弱项套件。",
        ),
        (
            f"{len(zero_mem)} suites have zero average role memories while summary versions still advance, which suggests the product is leaning more on summaries than on structured long-term role memory."
            if zero_mem
            else "All suites produced visible role memories in this batch.",
            f"有 {len(zero_mem)} 个 suite 的平均 role memory 为 0，但 summary version 仍在推进，说明当前产品更依赖 summary，而不是结构化长期 role memory。"
            if zero_mem
            else "本轮所有 suite 都产生了可见的 role memory。",
        ),
    ]

    if judge_enabled:
        findings.append(
            (
                f"An auxiliary LLM judge ({judge_model}) was applied to the latest suites: answer={fmt_score_5(judge_answer)}, groundedness={fmt_score_5(judge_grounded)}, persona={fmt_score_5(judge_persona)}, reference-fact usage={fmt_pct(judge_ref_facts)}, contradiction-free={fmt_pct(judge_contradiction_free)}.",
                f"最新 suite 已附加 LLM judge（{judge_model}）：answer={fmt_score_5(judge_answer)}，groundedness={fmt_score_5(judge_grounded)}，persona={fmt_score_5(judge_persona)}，reference-fact usage={fmt_pct(judge_ref_facts)}，contradiction-free={fmt_pct(judge_contradiction_free)}。",
            )
        )

    process_steps = [
        (
            "Install the benchmark build, resolve a single adb device, and create a run-specific manifest copy.",
            "安装 benchmark 构建、锁定单个 adb 真机，并生成 run 专属 manifest 副本。",
        ),
        (
            "Push the manifest into /sdcard/Android/media/selfgemma.talk/roleplay_eval/input/.",
            "将 manifest 推送到 /sdcard/Android/media/selfgemma.talk/roleplay_eval/input/。",
        ),
        (
            "Launch RoleplayEvalActivity and execute messages through the real product use case.",
            "拉起 RoleplayEvalActivity，并通过真实产品 use case 执行消息发送。",
        ),
        (
            "Export run-status.json, run-summary.json, messages, memories, summaries, and events.",
            "导出 run-status.json、run-summary.json、messages、memories、summaries 和 events。",
        ),
        (
            "Pull artifacts back to the host, refresh comparison.json, and render this static report page.",
            "将 artifacts 拉回主机端，刷新 comparison.json，并生成这份静态报告页。",
        ),
    ]

    if judge_enabled:
        process_steps.insert(
            4,
            (
                "Run a host-side DeepSeek judge pass over pulled artifacts and save judge-summary.json for each run.",
                "对拉回的 artifacts 执行主机侧 DeepSeek judge 二次分析，并为每个 run 生成 judge-summary.json。",
            ),
        )

    obs_rows = [
        ("run-status.json", "Live progress polling", "实时轮询进度", "state / phase / completedCases / resolvedModelName"),
        ("run-summary.json", "Final machine-readable run summary", "最终机器可读 run 摘要", "app version / device / resolved model / case results"),
        ("messages.json", "Raw conversation trace", "原始对话轨迹", "pinpoint drift, omissions, and wrong answers"),
        ("role-memories.json", "Role memory persistence", "角色记忆持久化结果", "verify whether long-term memory actually writes"),
        ("summary.json", "Summary output", "摘要输出结果", "verify compression and version progression"),
        ("events.json", "Event stream", "事件流", "check SUMMARY_UPDATE / MEMORY_UPSERT"),
        ("comparison.json", "Cross-run aggregate", "跨 run 聚合结果", "trend analysis and report rendering"),
    ]

    if judge_enabled:
        obs_rows.append(("judge-summary.json", "Auxiliary semantic judge output", "Judge 输出", "judge scores / rationale / evidence"))

    conclusions = [
        (
            "The real-device batch runner, per-run analyzer, cross-run aggregate, and formal HTML report are now connected end to end.",
            "真机批跑、单次 run 分析、跨 run 聚合和正式 HTML 报告，现已打通为一条完整链路。",
        ),
        (
            "The summary path is stable across the current public suites, which indicates the benchmark is exercising the real product loop.",
            "当前公开 suite 上的 summary 链路是稳定触发的，说明 benchmark 走的是产品真实路径。",
        ),
        (
            "Low scores on LongMemEval, LoCoMo, and CharacterBench fact accuracy point to long-horizon recall and factual retention as the main weaknesses.",
            "LongMemEval、LoCoMo 和 CharacterBench fact accuracy 的低分，说明长程回忆和事实保持仍是主弱项。",
        ),
        (
            "CharacterBench memory consistency still shows weak role-memory usage, which implies that persona retention currently depends more on summaries and nearby context than on explicit long-term memory retrieval.",
            "CharacterBench memory consistency 仍表现出较弱的 role memory 使用，这说明当前人格保持更依赖 summary 和近邻上下文，而不是显式长期记忆检索。",
        ),
    ]

    if judge_enabled:
        conclusions.append(
            (
                "The auxiliary judge agrees that answer correctness and memory groundedness remain materially weaker than operational pass rate, so the current bottleneck is quality under recall pressure rather than execution stability.",
                "辅助 judge 也表明：answer correctness 和 memory groundedness 明显弱于运行通过率，说明当前瓶颈是回忆压力下的质量，而不是执行稳定性。",
            )
        )

    next_steps = [
        (
            "Inspect whether summaries are fully reinjected into the final inference prompt.",
            "检查 summary 是否被完整回注到最终推理 prompt 中。",
        ),
        (
            "Add metrics for memory write rate, recall hit rate, and final-prompt citation rate.",
            "补充 memory 写入率、召回命中率和最终 prompt 引用率等指标。",
        ),
        (
            "Cluster failed long-horizon cases by time loss, entity-relation loss, and stance/persona drift.",
            "对长程失败 case 做聚类，区分时间丢失、实体关系丢失和立场或人格漂移。",
        ),
        (
            "Extend the adapter layer with RoleBench or CharacterEval-style suites for stronger persona coverage.",
            "继续接入 RoleBench 或 CharacterEval 风格的适配套件，增强人格保持覆盖面。",
        ),
    ]

    if judge_enabled:
        next_steps.append(
            (
                "Compare deterministic failures and judge rationales on the same cases, then use only the disagreements for manual review or stricter memory instrumentation work.",
                "把 deterministic 失败和 judge 原因对到同一批 case 上，只对不一致部分做人审或更严格的 memory instrumentation 排查。",
            )
        )

    judge_rows: list[str] = []
    if judge_enabled:
        for run in sorted(judge_runs, key=lambda item: item["suiteId"]):
            aggregate = run.get("judgeAggregate") or {}
            judge_rows.append(
                f"<tr><td>{esc(run['suiteId'])}</td><td>{esc(run.get('judgeModel') or '-')}</td><td>{esc(run.get('caseCount'))}</td><td>{esc(fmt_score_5(aggregate.get('answerCorrectness')))}</td><td>{esc(fmt_score_5(aggregate.get('memoryGroundedness')))}</td><td>{esc(fmt_score_5(aggregate.get('personaPreservation')))}</td><td>{esc(fmt_score_5(aggregate.get('naturalness')))}</td><td>{esc(fmt_pct(aggregate.get('usedReferenceFactsRate')))}</td><td>{esc(fmt_pct(aggregate.get('contradictionFreeRate')))}</td><td><a href=\"{rel_link(output_path.parent, run['runPath'] / 'judge-summary.json')}\">judge-summary.json</a></td></tr>"
            )
    judge_section = ""
    if judge_enabled:
        judge_section = f"""
    <section class="section">
      <div class="section-head">
        <h2>{bi('LLM Judge Overlay', 'LLM Judge 叠加评审')}</h2>
        <p>{bi('This judge layer is auxiliary. Deterministic metrics remain the primary regression signal, while the judge adds semantic views on correctness, groundedness, persona, and naturalness.', '这一层 judge 只是辅助评审。deterministic 指标仍然是主回归信号，judge 主要补充 correctness、groundedness、persona 和 naturalness 的语义视角。')}</p>
      </div>
      <div class="grid4">
        <article class="card stat"><div class="k">{bi('Judge Model', 'Judge 模型')}</div><div class="v">{esc(judge_model or '-')}</div><div class="muted">{bi('Host-side semantic overlay', '主机侧语义补充评审')}</div></article>
        <article class="card stat"><div class="k">{bi('Answer', '答案正确性')}</div><div class="v">{esc(fmt_score_5(judge_answer))}</div><div class="muted">{bi('Average answer correctness', '平均答案正确性')}</div></article>
        <article class="card stat"><div class="k">{bi('Grounded', '记忆落地性')}</div><div class="v">{esc(fmt_score_5(judge_grounded))}</div><div class="muted">{bi('Average memory groundedness', '平均记忆落地性')}</div></article>
        <article class="card stat"><div class="k">{bi('Contradiction Free', '无矛盾率')}</div><div class="v">{esc(fmt_pct(judge_contradiction_free))}</div><div class="muted">{bi('Higher is better', '越高越好')}</div></article>
      </div>
      <div class="table" style="margin-top:16px"><table><thead><tr><th>{bi('Suite', '套件')}</th><th>{bi('Judge', 'Judge')}</th><th>{bi('Cases', '用例数')}</th><th>{bi('Answer', '答案')}</th><th>{bi('Grounded', 'Grounded')}</th><th>{bi('Persona', '人格保持')}</th><th>{bi('Natural', '自然度')}</th><th>{bi('Ref Facts', '参考事实命中')}</th><th>{bi('Contradiction Free', '无矛盾率')}</th><th>{bi('Artifacts', '产物')}</th></tr></thead><tbody>{"".join(judge_rows)}</tbody></table></div>
    </section>"""

    suite_cards: list[str] = []
    for suite in suites:
        suite_id = suite["suiteId"]
        run = latest_by_suite.get(suite_id)
        if run is None:
            continue
        color = "#b64a31" if run["metricKey"] == "reference_token_f1" else "#1d6f6a"
        metric_label_en, metric_label_zh = suite_metric_label_pair(run["metricKey"])
        latency_en, latency_zh = fmt_ms_pair(run.get("avgAssistantLatencyMs"))
        status_en, status_zh = status_label_pair(status_map.get(suite_id, "SUCCEEDED"))
        suite_cards.append(
            f"""
            <article class="card">
              <div class="pill-row">
                <span class="pill {'ok' if status_en == 'SUCCEEDED' else 'warn'}">{bi(status_en, status_zh)}</span>
                <span class="pill">{esc(run['dataset'])}</span>
                <span class="pill">{esc(run['language'])}</span>
              </div>
              <h3>{esc(suite_id)}</h3>
              <p class="muted">{bi(run['notes'], suite_note_zh(suite_id, run['notes']))}</p>
              <div class="headline">{bi(metric_label_en, metric_label_zh)}: {esc(fmt_pct(run['metricValue']))}</div>
              {bar(run['metricValue'])}
              {spark([item.get('metricValue') for item in history_by_suite.get(suite_id, [])], color)}
              <div class="kv">
                <div><span>{bi('Cases', '用例数')}</span><strong>{esc(run['caseCount'])}</strong></div>
                <div><span>{bi('Passed', '通过')}</span><strong>{esc(run['passedCaseCount'])}</strong></div>
                <div><span>{bi('Latency', '延迟')}</span><strong>{bi(latency_en, latency_zh)}</strong></div>
                <div><span>{bi('Summary', '摘要版本')}</span><strong>{esc(fmt_num(run.get('avgSummaryVersion')))}</strong></div>
                <div><span>{bi('Role Memory', '角色记忆')}</span><strong>{esc(fmt_num(run.get('avgRoleMemoryCount')))}</strong></div>
                <div><span>{bi('Worst Case', '最弱用例')}</span><strong>{esc(best_or_worst_label(run['worstCase']))}</strong></div>
              </div>
              <div class="links">
                <a href="{rel_link(output_path.parent, run['analysisPath'])}">{bi('Analysis JSON', '分析 JSON')}</a>
                <a href="{rel_link(output_path.parent, run['runPath'])}">{bi('Run Folder', 'Run 目录')}</a>
              </div>
            </article>
            """
        )

    latest_rows: list[str] = []
    for run in sorted(latest_runs, key=lambda item: item["suiteId"]):
        metric_label_en, metric_label_zh = suite_metric_label_pair(run["metricKey"])
        latency_en, latency_zh = fmt_ms_pair(run.get("avgAssistantLatencyMs"))
        latest_rows.append(
            f"""
            <tr>
              <td>{esc(run['suiteId'])}</td>
              <td>{esc(run['dataset'])}</td>
              <td>{esc(run['caseCount'])}</td>
              <td>{esc(run['passedCaseCount'])}</td>
              <td>{esc(run['failedAssertionCount'])}</td>
              <td>{bi(metric_label_en, metric_label_zh)}</td>
              <td>{esc(fmt_pct(run['metricValue']))}</td>
              <td>{esc(fmt_num((run.get('metrics') or {}).get('rouge_l'), 3))}</td>
              <td>{bi(latency_en, latency_zh)}</td>
              <td>{esc(fmt_num(run.get('avgRoleMemoryCount')))}</td>
              <td>{esc(fmt_num(run.get('avgSummaryVersion')))}</td>
              <td>{esc(run['runId'])}</td>
            </tr>
            """
        )

    history_rows: list[str] = []
    for run in reversed(ordered_runs):
        metric_label_en, metric_label_zh = suite_metric_label_pair(run["metricKey"])
        latency_en, latency_zh = fmt_ms_pair(run.get("avgAssistantLatencyMs"))
        history_rows.append(
            f"""
            <tr>
              <td>{esc(run['runId'])}</td>
              <td>{esc(run['suiteId'])}</td>
              <td>{esc(fmt_time(run.get('startedAtUtc')))}</td>
              <td>{esc(run.get('resolvedModelName') or '-')}</td>
              <td>{bi(metric_label_en, metric_label_zh)}</td>
              <td>{esc(fmt_pct(run['metricValue']))}</td>
              <td>{bi(latency_en, latency_zh)}</td>
            </tr>
            """
        )

    source_links = [
        f'<a href="{rel_link(output_path.parent, reports_root / "comparison.json")}">comparison.json</a>',
        f'<a href="{rel_link(output_path.parent, reports_root / "batch-summary.json")}">batch-summary.json</a>',
    ]
    if judge_enabled and (reports_root / "judge-comparison.json").exists():
        source_links.append(
            f'<a href="{rel_link(output_path.parent, reports_root / "judge-comparison.json")}">judge-comparison.json</a>'
        )
    source_links.append(f'<a href="{rel_link(output_path.parent, DEFAULT_CATALOG_PATH)}">catalog.json</a>')

    output_time = datetime.now(timezone.utc).isoformat()
    batch_duration_en, batch_duration_zh = fmt_duration_pair(batch_start.isoformat() if batch_start else None, batch_finish.isoformat() if batch_finish else None)
    return f"""<!doctype html>
<html lang="en" data-lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GemmaTavern Roleplay Benchmark Report</title>
  <style>
    :root{{--bg:#f5efe3;--ink:#0f1823;--muted:#5b6470;--panel:rgba(255,255,255,.82);--border:rgba(15,24,35,.10);--a:#b64a31;--b:#1d6f6a;--shadow:0 18px 48px rgba(59,38,18,.12)}}
    *{{box-sizing:border-box}}
    html[data-lang="en"] .i18n-zh{{display:none!important}}
    html[data-lang="zh"] .i18n-en{{display:none!important}}
    body{{margin:0;background:radial-gradient(circle at 0 0,rgba(182,74,49,.12),transparent 25%),radial-gradient(circle at 100% 0,rgba(29,111,106,.13),transparent 24%),linear-gradient(180deg,#f7f2e8,#eee2d0);color:var(--ink);font:15px/1.65 "Segoe UI","Noto Sans","Microsoft YaHei",sans-serif}}
    h1,h2,h3{{margin:0;font-family:Georgia,"Times New Roman","Noto Serif SC",serif}}
    a{{color:var(--ink);text-decoration:none;border-bottom:1px solid rgba(15,24,35,.24)}}
    a:hover{{color:var(--a);border-color:var(--a)}}
    .page{{max-width:1180px;margin:0 auto;padding:28px 18px 64px}}
    .hero,.card,.table{{background:var(--panel);border:1px solid rgba(255,255,255,.85);box-shadow:var(--shadow);border-radius:26px}}
    .hero{{padding:28px;position:relative;overflow:hidden}}
    .hero:after{{content:"";position:absolute;right:-50px;bottom:-60px;width:220px;height:220px;border-radius:50%;background:radial-gradient(circle,rgba(182,74,49,.18),transparent 68%)}}
    .topbar{{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}}
    .eyebrow,.pill,.chip{{display:inline-flex;align-items:center;border-radius:999px}}
    .eyebrow{{padding:7px 12px;background:rgba(15,24,35,.06);color:var(--muted);font-size:12px;letter-spacing:.12em;text-transform:uppercase}}
    .lang-switch{{display:inline-flex;gap:6px;padding:6px;border-radius:999px;background:rgba(255,255,255,.72);border:1px solid var(--border)}}
    .lang-btn{{border:none;background:transparent;color:var(--muted);padding:8px 12px;border-radius:999px;font:600 13px/1 inherit;cursor:pointer}}
    .lang-btn.active{{background:var(--ink);color:#fff}}
    .hero h1{{font-size:clamp(32px,4vw,54px);line-height:1.08;margin:16px 0 10px}}
    .hero p{{max-width:860px;color:var(--muted)}}
    .chips,.pill-row,.links{{display:flex;flex-wrap:wrap;gap:10px}}
    .chips{{margin-top:18px}}
    .chip{{padding:9px 12px;background:rgba(255,255,255,.82);border:1px solid var(--border);font-size:13px}}
    .section{{margin-top:22px}}
    .section-head{{display:flex;justify-content:space-between;gap:16px;align-items:end;margin-bottom:12px}}
    .section-head p{{margin:0;color:var(--muted);max-width:700px}}
    .grid4,.grid3,.grid2{{display:grid;gap:16px}}
    .grid4{{grid-template-columns:repeat(4,minmax(0,1fr))}}
    .grid3{{grid-template-columns:repeat(3,minmax(0,1fr))}}
    .grid2{{grid-template-columns:repeat(2,minmax(0,1fr))}}
    .card{{padding:20px}}
    .stat .k,.kv span,.table th{{color:var(--muted);font-size:12px;letter-spacing:.08em;text-transform:uppercase}}
    .stat .v{{font-size:34px;line-height:1.05;margin:14px 0 6px}}
    ul,ol{{margin:0;padding-left:18px;display:grid;gap:8px}}
    .muted{{color:var(--muted);margin:8px 0 0}}
    .pill{{padding:6px 10px;background:rgba(15,24,35,.06);color:var(--muted);font-size:12px}}
    .pill.ok{{background:rgba(32,95,74,.10);color:#205f4a}}
    .pill.warn{{background:rgba(133,87,35,.10);color:#855723}}
    .headline{{font:600 24px/1.2 Georgia,"Times New Roman","Noto Serif SC",serif;margin:12px 0 10px}}
    .bar-shell{{height:12px;border-radius:999px;background:rgba(15,24,35,.08);overflow:hidden;margin-bottom:12px}}
    .bar{{height:100%;border-radius:inherit;background:linear-gradient(90deg,var(--a),var(--b))}}
    .spark{{width:100%;height:auto;display:block;margin:2px 0 10px}}
    .kv{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px 14px}}
    .kv div span{{display:block;margin-bottom:3px}}
    .kv div strong{{font-size:14px;word-break:break-word}}
    .table{{overflow:auto}}
    table{{width:100%;border-collapse:collapse;min-width:780px}}
    th,td{{padding:13px 14px;border-bottom:1px solid var(--border);text-align:left;vertical-align:top}}
    tr:last-child td{{border-bottom:none}}
    .note{{padding:16px 18px;border-left:4px solid var(--a);background:rgba(255,255,255,.62);border-radius:0 18px 18px 0}}
    .footer{{color:var(--muted);font-size:13px;margin-top:12px}}
    code{{font-family:"Cascadia Code","Consolas",monospace}}
    @media (max-width:1024px){{.grid4,.grid3{{grid-template-columns:repeat(2,minmax(0,1fr))}}}}
    @media (max-width:720px){{.page{{padding:18px 12px 42px}}.grid4,.grid3,.grid2{{grid-template-columns:1fr}}.kv{{grid-template-columns:1fr}}.topbar{{flex-direction:column;align-items:flex-start}}}}
  </style>
</head>
<body>
  <main class="page">
    <section class="hero">
      <div class="topbar">
        <div class="eyebrow">{bi('GemmaTavern / Android / On-device Roleplay Benchmark', 'GemmaTavern / Android / 端侧角色扮演 Benchmark')}</div>
        <div class="lang-switch" role="group" aria-label="Language">
          <button class="lang-btn" data-lang-select="zh">中文</button>
          <button class="lang-btn" data-lang-select="en">EN</button>
        </div>
      </div>
      <h1>{bi('Formal Report: Android Roleplay Product Benchmark', '正式报告：Android 端侧角色扮演产品 Benchmark')}</h1>
      <p>{bi('This report evaluates the full Android product loop instead of an isolated model endpoint: model resolution and initialization, prompt assembly, context handling, summary and memory management, persistence, event emission, and final response quality. The benchmark is built from public datasets adapted into fixed product-level mini suites.', '本报告评估的不是孤立模型接口，而是 Android 真机上的完整产品链路：模型解析与初始化、提示拼装、上下文管理、summary 与 memory 机制、持久化、事件发射，以及最终回复质量。该基线基于公开数据集适配出的固定产品级 mini suite，可用于版本回归、模型回归和系统回归。')}</p>
      <div class="chips">
        <span class="chip">{bi('Generated', '生成时间')}: {esc(fmt_time(output_time))}</span>
        <span class="chip">{bi('Batch Window', '批次时间窗口')}: {esc(fmt_time(batch_start.isoformat() if batch_start else None))} - {esc(fmt_time(batch_finish.isoformat() if batch_finish else None))}</span>
        <span class="chip">{bi('Batch Duration', '批次耗时')}: {bi(batch_duration_en, batch_duration_zh)}</span>
        <span class="chip">{bi('Device', '设备')}: {esc(env_run.get('deviceModel') if env_run else '-')} / {esc(env_run.get('deviceSerial') if env_run else '-')}</span>
        <span class="chip">{bi('App', '应用版本')}: {esc(env_run.get('appVersionName') if env_run else '-')} ({esc(env_run.get('appVersionCode') if env_run else '-')})</span>
        <span class="chip">{bi('Model', '模型')}: {esc(env_run.get('resolvedModelName') if env_run else '-')}</span>
      </div>
    </section>

    {judge_section}

    <section class="section">
      <div class="section-head">
        <h2>{bi('Executive Summary', '执行摘要')}</h2>
        <p>{bi('Read the high-level call first, then drill into design, process, and detailed data.', '先看高层结论，再展开实验设计、自动化过程和详细数据。')}</p>
      </div>
      <div class="grid4">
        <article class="card stat"><div class="k">{bi('Suite Success', '套件成功率')}</div><div class="v">{esc(f"{succeeded}/{len(latest_runs)}")}</div><div class="muted">{bi('Public mini suites in this batch', '本轮公开 mini suite 执行结果')}</div></article>
        <article class="card stat"><div class="k">{bi('Case Passes', '用例通过')}</div><div class="v">{esc(f"{total_passed}/{total_cases}")}</div><div class="muted">{bi('Case-level assertion results', '用例级断言结果')}</div></article>
        <article class="card stat"><div class="k">{bi('Failed Assertions', '失败断言')}</div><div class="v">{esc(total_failed_assertions)}</div><div class="muted">{bi('Lower is better', '越低越好')}</div></article>
        <article class="card stat"><div class="k">{bi('Run Window', '执行时间窗')}</div><div class="v">{bi(batch_duration_en, batch_duration_zh)}</div><div class="muted">{bi('Single-device batch execution span', '单设备批量执行时长')}</div></article>
      </div>
      <div class="grid2" style="margin-top:16px">
        <article class="card"><div class="section-head"><h2 style="font-size:22px">{bi('Key Findings', '关键发现')}</h2></div><ul>{"".join(f"<li>{bi(en, zh)}</li>" for en, zh in findings)}</ul></article>
        <article class="card"><div class="section-head"><h2 style="font-size:22px">{bi('Overall Read', '总体判断')}</h2></div><div class="note">{bi('Infrastructure-wise, the benchmark is now replayable, traceable, and comparable. Effect-wise, summary triggering is stable, but long-horizon factual recall, ultra-long context retention, and structured long-term role memory remain the weakest parts of the current product loop.', '从基础设施角度看，这套 benchmark 已经具备可复跑、可追溯和可比较的能力。从效果角度看，summary 触发是稳定的，但长程事实回忆、超长上下文保留，以及结构化长期 role memory 仍是当前产品链路的弱项。')}</div></article>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>{bi('Experimental Design', '实验设计')}</h2>
        <p>{bi('The objective is to measure product behavior, not just whether the model can answer one prompt in isolation.', '目标是衡量产品行为，而不是只看模型对单个 prompt 的回答能力。')}</p>
      </div>
      <div class="grid3">
        <article class="card"><h3>{bi('Scope', '评估范围')}</h3><ul><li>{bi('Real model resolution, initialization, and on-device inference.', '真实模型解析、初始化和端侧推理。')}</li><li>{bi('Real prompt assembly, context budgeting, and history stitching.', '真实 prompt 拼装、上下文预算和历史拼接。')}</li><li>{bi('Real summary extraction, memory writes, and repository persistence.', '真实 summary 提取、memory 写入和仓储持久化。')}</li><li>{bi('Real event emission and run-level artifact export.', '真实事件发射和 run 级 artifacts 导出。')}</li></ul></article>
        <article class="card"><h3>{bi('Target Dimensions', '目标维度')}</h3><ul><li>{bi('Persona consistency and role stability.', '人格一致性和角色稳定性。')}</li><li>{bi('Fact recall about character settings.', '角色设定相关的事实回忆。')}</li><li>{bi('Long-horizon summary quality and detail retention.', '长程上下文中的摘要质量和细节保留。')}</li><li>{bi('Whether memory writes actually happen and matter later.', 'memory 是否真实写入，并在后续轮次发挥作用。')}</li></ul></article>
        <article class="card"><h3>{bi('Comparability', '可对比性')}</h3><ul><li>{bi('Data sources come from public benchmarks.', '数据源来自公开 benchmark。')}</li><li>{bi('Execution uses fixed product-level mini suites, not each original paper protocol in full.', '执行单元是固定的产品级 mini suite，而不是逐篇论文的完整协议。')}</li><li>{bi('Scoring is local and reproducible, which makes this strong for regression tracking but not a paper leaderboard substitute.', '评分是本地可复现的，因此非常适合回归跟踪，但不应直接替代论文 leaderboard。')}</li></ul></article>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>{bi('Process And Observability', '执行过程与可观测性')}</h2>
        <p>{bi('The pipeline is automated and explainable. It does not stop at a single opaque score.', '整条链路已自动化且可解释，不会停留在一个不可解释的总分上。')}</p>
      </div>
      <div class="grid2">
        <article class="card"><h3>{bi('Execution Flow', '执行流程')}</h3><ol>{"".join(f"<li>{bi(en, zh)}</li>" for en, zh in process_steps)}</ol></article>
        <article class="card"><h3>{bi('Anti-Black-Box Artifacts', '反黑盒 Artifacts')}</h3><div class="table"><table><thead><tr><th>{bi('Artifact', '产物')}</th><th>{bi('Purpose', '用途')}</th><th>{bi('Signals', '关键信号')}</th></tr></thead><tbody>{"".join(f"<tr><td>{esc(name)}</td><td>{bi(en_purpose, zh_purpose)}</td><td>{esc(signals)}</td></tr>" for name, en_purpose, zh_purpose, signals in obs_rows)}</tbody></table></div></article>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>{bi('Experimental Data', '实验数据')}</h2>
        <p>{bi('The cards below show the latest baseline for each public suite together with a historical sparkline.', '下面的卡片展示每个公开 suite 的最新基线，并附带历史趋势火花线。')}</p>
      </div>
      <div class="grid2">{"".join(suite_cards)}</div>
      <div class="table" style="margin-top:16px"><table><thead><tr><th>{bi('Suite', '套件')}</th><th>{bi('Dataset', '数据集')}</th><th>{bi('Cases', '用例数')}</th><th>{bi('Passed', '通过')}</th><th>{bi('Failed Assertions', '失败断言')}</th><th>{bi('Primary Metric', '主指标')}</th><th>{bi('Primary Score', '主分数')}</th><th>{bi('ROUGE-L', 'ROUGE-L')}</th><th>{bi('Avg Latency', '平均延迟')}</th><th>{bi('Avg Role Memories', '平均角色记忆')}</th><th>{bi('Avg Summary Version', '平均摘要版本')}</th><th>{bi('Run ID', 'Run ID')}</th></tr></thead><tbody>{"".join(latest_rows)}</tbody></table></div>
    </section>
    <section class="section">
      <div class="section-head">
        <h2>{bi('Historical Runs', '历史运行记录')}</h2>
        <p>{bi('Every analyzed run enters this table, which makes future regression tracking straightforward.', '所有已分析 run 都会进入这张表，便于后续直接做回归对比。')}</p>
      </div>
      <div class="table"><table><thead><tr><th>{bi('Run ID', 'Run ID')}</th><th>{bi('Suite', '套件')}</th><th>{bi('Started', '开始时间')}</th><th>{bi('Model', '模型')}</th><th>{bi('Primary Metric', '主指标')}</th><th>{bi('Primary Score', '主分数')}</th><th>{bi('Avg Latency', '平均延迟')}</th></tr></thead><tbody>{"".join(history_rows)}</tbody></table></div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>{bi('Conclusions And Next Steps', '结论与下一步')}</h2>
        <p>{bi('The report should lead to concrete engineering action instead of ending at score presentation.', '这份报告应该导向具体工程动作，而不是停留在分数展示。')}</p>
      </div>
      <div class="grid2">
        <article class="card"><h3>{bi('Conclusions', '结论')}</h3><ul>{"".join(f"<li>{bi(en, zh)}</li>" for en, zh in conclusions)}</ul></article>
        <article class="card"><h3>{bi('Next Iteration', '下一轮建议')}</h3><ul>{"".join(f"<li>{bi(en, zh)}</li>" for en, zh in next_steps)}</ul></article>
      </div>
      <div class="footer">
        <p>{bi('Source files', '来源文件')}: {" / ".join(source_links)}</p>
                <p><code>python benchmarks/roleplay_eval/scripts/render-roleplay-benchmark-report.py --reports-root benchmarks/roleplay_eval/reports --catalog benchmarks/roleplay_eval/scenarios/public/catalog.json --output benchmarks/roleplay_eval/reports/roleplay-benchmark-report.html</code></p>
      </div>
    </section>
  </main>
  <script>
    (() => {{
      const root = document.documentElement;
      const buttons = Array.from(document.querySelectorAll('[data-lang-select]'));
      const storageKey = 'roleplay-report-lang';
      const applyLang = (lang) => {{
        const next = lang === 'zh' ? 'zh' : 'en';
        root.dataset.lang = next;
        root.lang = next === 'zh' ? 'zh-CN' : 'en';
        buttons.forEach((button) => button.classList.toggle('active', button.dataset.langSelect === next));
        try {{
          localStorage.setItem(storageKey, next);
        }} catch (_error) {{
        }}
      }};
      const preferred = (() => {{
        try {{
          const saved = localStorage.getItem(storageKey);
          if (saved === 'zh' || saved === 'en') {{
            return saved;
          }}
        }} catch (_error) {{
        }}
        const browser = (navigator.language || 'en').toLowerCase();
        return browser.startsWith('zh') ? 'zh' : 'en';
      }})();
      buttons.forEach((button) => {{
        button.addEventListener('click', () => applyLang(button.dataset.langSelect));
      }});
      applyLang(preferred);
    }})();
  </script>
</body>
</html>
"""


def main() -> None:
    parser = argparse.ArgumentParser(description="Render a static HTML report for Android roleplay benchmark runs.")
    parser.add_argument("--reports-root", type=Path, default=DEFAULT_REPORTS_ROOT)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG_PATH)
    parser.add_argument("--batch-summary", type=Path, default=DEFAULT_BATCH_SUMMARY_PATH)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH)
    args = parser.parse_args()

    reports_root = args.reports_root.resolve()
    comparison = load_json(reports_root / "comparison.json")
    catalog = load_json(args.catalog.resolve())
    batch_summary_path = args.batch_summary.resolve()
    batch_summary = load_json(batch_summary_path) if batch_summary_path.exists() else None
    catalog_map = {suite["suiteId"]: suite for suite in catalog.get("suites", [])}
    runs = build_runs(reports_root, comparison, catalog_map)
    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(render(output_path, reports_root, catalog, batch_summary, runs), encoding="utf-8-sig")
    print(f"rendered report -> {output_path}")


if __name__ == "__main__":
    main()
