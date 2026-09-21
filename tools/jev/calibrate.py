"""Run the labeled set against Jev and write a calibration report."""

from __future__ import annotations

import argparse
import io
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from jev_client import JevError, ask, redact_secrets  # noqa: E402
from questions import JUDGE_QUESTIONS, build_state  # noqa: E402

FIXTURE = ROOT / "fixtures" / "labeled_set.json"
REPORT_DIR = ROOT / "report"
NOUL_KEYS = ("literal_question", "should_reply_now", "tension_resolved")
CHOICE_KEYS = ("true_intent", "best_action", "she_needs")
SCORE_KEYS = ("danger_level",)
SLEEP_BETWEEN = 0.3


def load_cases(limit: int | None) -> list[dict]:
    with FIXTURE.open("r", encoding="utf-8") as fh:
        cases = json.load(fh)
    if not isinstance(cases, list):
        raise SystemExit("labeled_set.json must be a JSON array")
    if limit is not None:
        cases = cases[:limit]
    return cases


def answers_of(result: dict) -> dict:
    if "answers" in result and isinstance(result["answers"], dict):
        return result["answers"]
    data = result.get("data")
    if isinstance(data, dict) and isinstance(data.get("answers"), dict):
        return data["answers"]
    raise JevError("Jev response missing answers object")


def noul_value(ans: dict) -> float:
    if "noul" in ans:
        return float(ans["noul"])
    raise KeyError("noul")


def choice_value(ans: dict) -> str:
    if "choice" in ans:
        return str(ans["choice"])
    raise KeyError("choice")


def score_value(ans: dict, n_bins: int = 10) -> float:
    """Return danger_level on a 0..n_bins-1 scale.

    Prefer the probability-weighted bin index. Fall back to `score` as-is
    when it already looks like an index, otherwise stretch a 0-1 value.
    """
    if "score" not in ans:
        raise KeyError("score")
    probs = ans.get("probabilities") or {}
    if probs:
        try:
            return sum(int(k) * float(p) for k, p in probs.items())
        except (TypeError, ValueError):
            pass
    score = float(ans["score"])
    legend = ans.get("legend") or {}
    max_idx = n_bins - 1
    if legend:
        try:
            max_idx = max(int(k) for k in legend.keys())
        except (TypeError, ValueError):
            max_idx = n_bins - 1
    if 0.0 <= score <= 1.0 + 1e-9 and max_idx > 1:
        # Ambiguous only when probabilities are missing; do not treat an
        # exact index of 0.0/1.0 as a normalized 0-1 score.
        return score
    return score


def confidence_of(ans: dict) -> float | None:
    if not isinstance(ans, dict):
        return None
    if "confidence" in ans:
        return float(ans["confidence"])
    if "noul" in ans:
        return abs(float(ans["noul"]) - 0.5) * 2.0
    return None


def predicted_for(key: str, ans: dict):
    if key in NOUL_KEYS:
        return noul_value(ans)
    if key in CHOICE_KEYS:
        return choice_value(ans)
    if key in SCORE_KEYS:
        return score_value(ans)
    raise KeyError(key)


def hit_for(key: str, predicted, expect) -> bool | None:
    if expect is None:
        return None
    if key in NOUL_KEYS:
        return (float(predicted) >= 0.5) == bool(expect)
    if key in CHOICE_KEYS:
        return str(predicted) == str(expect)
    if key in SCORE_KEYS:
        return abs(float(predicted) - float(expect)) < 1.0
    return None


def usage_of(result: dict) -> dict:
    usage = result.get("usage") or {}
    return {
        "input_tokens": usage.get("input_tokens"),
        "output_tokens": usage.get("output_tokens"),
        "cost": usage.get("cost"),
    }


def run_case(case: dict) -> dict:
    state = build_state(case["messages"], case["relationship"])
    t0 = time.perf_counter()
    result = ask(state, JUDGE_QUESTIONS, timeout=20)
    latency = time.perf_counter() - t0
    ans = answers_of(result)
    expect = case.get("expect") or {}
    row = {
        "id": case["id"],
        "ok": True,
        "http_status": 200,
        "latency_s": round(latency, 3),
        "usage": usage_of(result),
        "provider": result.get("provider"),
        "expect": expect,
        "predicted": {},
        "confidence": {},
        "hit": {},
        "diff": {},
    }
    for key in list(JUDGE_QUESTIONS.keys()):
        if key not in ans:
            row["predicted"][key] = None
            row["hit"][key] = False
            row["diff"][key] = "missing answer"
            continue
        pred = predicted_for(key, ans[key])
        conf = confidence_of(ans[key])
        row["predicted"][key] = pred
        row["confidence"][key] = conf
        exp = expect.get(key, None)
        hit = hit_for(key, pred, exp)
        row["hit"][key] = hit
        if hit is False:
            row["diff"][key] = {"expect": exp, "predicted": pred}
        extra = {}
        if "probabilities" in ans[key]:
            extra["probabilities"] = ans[key]["probabilities"]
        if extra:
            row.setdefault("raw_extra", {})[key] = extra
    return row


def summarize(rows: list[dict]) -> dict:
    keys = list(JUDGE_QUESTIONS.keys())
    n_ok = sum(1 for r in rows if r.get("ok"))
    hits = defaultdict(list)
    confs = defaultdict(list)
    abs_err = []
    for row in rows:
        if not row.get("ok"):
            continue
        for key in keys:
            hit = row.get("hit", {}).get(key)
            if hit is not None:
                hits[key].append(bool(hit))
            conf = row.get("confidence", {}).get(key)
            if conf is not None:
                confs[key].append(float(conf))
        pred = row.get("predicted", {}).get("danger_level")
        exp = (row.get("expect") or {}).get("danger_level")
        if pred is not None and exp is not None:
            abs_err.append(abs(float(pred) - float(exp)))

    per_q = {}
    for key in keys:
        h = hits.get(key) or []
        c = confs.get(key) or []
        item = {
            "n": len(h),
            "hit_rate": (sum(h) / len(h)) if h else None,
            "avg_confidence": (sum(c) / len(c)) if c else None,
        }
        if key == "danger_level":
            item["mae"] = (sum(abs_err) / len(abs_err)) if abs_err else None
        per_q[key] = item

    costs = []
    lats = []
    in_tok = []
    out_tok = []
    for row in rows:
        if row.get("latency_s") is not None:
            lats.append(float(row["latency_s"]))
        usage = row.get("usage") or {}
        if usage.get("cost") is not None:
            costs.append(float(usage["cost"]))
        if usage.get("input_tokens") is not None:
            in_tok.append(int(usage["input_tokens"]))
        if usage.get("output_tokens") is not None:
            out_tok.append(int(usage["output_tokens"]))

    return {
        "n_cases": len(rows),
        "n_http_200": n_ok,
        "n_errors": len(rows) - n_ok,
        "per_question": per_q,
        "danger_level_mae": (sum(abs_err) / len(abs_err)) if abs_err else None,
        "avg_latency_s": (sum(lats) / len(lats)) if lats else None,
        "total_cost": sum(costs) if costs else 0.0,
        "total_input_tokens": sum(in_tok) if in_tok else 0,
        "total_output_tokens": sum(out_tok) if out_tok else 0,
        "gates": {
            "danger_level_mae_lt_1": bool(
                abs_err and (sum(abs_err) / len(abs_err)) < 1.0
            ),
            "true_intent_hit_ge_60": bool(
                (per_q.get("true_intent") or {}).get("hit_rate") is not None
                and per_q["true_intent"]["hit_rate"] >= 0.60
            ),
            "she_needs_hit_ge_60": bool(
                (per_q.get("she_needs") or {}).get("hit_rate") is not None
                and per_q["she_needs"]["hit_rate"] >= 0.60
            ),
        },
    }


def pct(v) -> str:
    if v is None:
        return "-"
    return f"{v * 100:5.1f}%"


def num(v, digits=3) -> str:
    if v is None:
        return "-"
    return f"{v:.{digits}f}"


def render_table(summary: dict) -> str:
    lines = []
    lines.append(
        f"{'question':<20} {'hit':>8} {'mae':>8} {'avg_conf':>10} {'n':>4}"
    )
    lines.append("-" * 54)
    for key, item in summary["per_question"].items():
        mae = num(item.get("mae"), 3) if "mae" in item else "-"
        lines.append(
            f"{key:<20} {pct(item.get('hit_rate')):>8} {mae:>8} "
            f"{num(item.get('avg_confidence'), 3):>10} {item.get('n') or 0:>4}"
        )
    lines.append("-" * 54)
    lines.append(
        f"n={summary['n_cases']}  http_200={summary['n_http_200']}  "
        f"errors={summary['n_errors']}  avg_latency={num(summary['avg_latency_s'], 3)}s  "
        f"total_cost={summary['total_cost']:.6f}  "
        f"tokens_in={summary['total_input_tokens']}  tokens_out={summary['total_output_tokens']}"
    )
    gates = summary["gates"]
    lines.append(
        "gates: "
        f"danger_mae<1.0={gates['danger_level_mae_lt_1']}  "
        f"true_intent>=60%={gates['true_intent_hit_ge_60']}  "
        f"she_needs>=60%={gates['she_needs_hit_ge_60']}"
    )
    return "\n".join(lines)


def render_md(summary: dict, rows: list[dict]) -> str:
    lines = ["# Jev calibration", "", "## Summary", "", "```", render_table(summary), "```", "", "## Per-case diffs", ""]
    for row in rows:
        if not row.get("ok"):
            lines.append(f"- `{row.get('id')}` ERROR: {row.get('error')}")
            continue
        diffs = row.get("diff") or {}
        if not diffs:
            lines.append(f"- `{row['id']}` all labeled questions hit")
            continue
        bits = []
        for key, diff in diffs.items():
            if isinstance(diff, dict):
                bits.append(f"{key}: expect={diff.get('expect')!r} got={diff.get('predicted')!r}")
            else:
                bits.append(f"{key}: {diff}")
        lines.append(f"- `{row['id']}` " + "; ".join(bits))
    lines.append("")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Calibrate Jev questions on the labeled set")
    parser.add_argument("--limit", type=int, default=None, help="only run the first N cases")
    args = parser.parse_args()

    cases = load_cases(args.limit)
    rows: list[dict] = []
    for i, case in enumerate(cases):
        try:
            row = run_case(case)
        except JevError as exc:
            row = {
                "id": case.get("id"),
                "ok": False,
                "http_status": exc.status,
                "error": str(exc),
                "expect": case.get("expect"),
            }
        rows.append(row)
        status = "200" if row.get("ok") else f"ERR {row.get('http_status')}"
        pred = row.get("predicted") or {}
        print(
            f"[{i + 1}/{len(cases)}] {case.get('id')} {status} "
            f"intent={pred.get('true_intent')} danger={pred.get('danger_level')} "
            f"needs={pred.get('she_needs')} hit={row.get('hit')}",
            flush=True,
        )
        if i < len(cases) - 1:
            time.sleep(SLEEP_BETWEEN)

    summary = summarize(rows)
    table = render_table(summary)
    print()
    print(table)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"summary": summary, "cases": rows}
    text = redact_secrets(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    (REPORT_DIR / "calibration.json").write_text(text, encoding="utf-8")
    (REPORT_DIR / "calibration.md").write_text(
        redact_secrets(render_md(summary, rows)), encoding="utf-8"
    )
    print()
    print(f"wrote {REPORT_DIR / 'calibration.json'}")
    print(f"wrote {REPORT_DIR / 'calibration.md'}")

    gates = summary["gates"]
    if summary["n_errors"]:
        return 1
    if not (
        gates["danger_level_mae_lt_1"]
        and gates["true_intent_hit_ge_60"]
        and gates["she_needs_hit_ge_60"]
    ):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
