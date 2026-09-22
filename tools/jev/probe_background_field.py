# -*- coding: utf-8 -*-
"""Does Jev's alpha/decisions ignore an unknown top-level `background` field,
or reject the request?

D stage wants to put the relationship / contact notes / knowledge-base hits into
`state.background`. Before building on that, confirm the live endpoint tolerates
it. This sends the SAME fixture twice -- once without `background`, once with --
and prints both statuses plus the parsed answer, so a 400 or a silently-dropped
field is obvious.

Run:
    $env:OPENROUTER_API_KEY = "<your key>"      # PowerShell 7
    python tools/jev/probe_background_field.py

The key is read from the environment only; it is never printed or written.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

URL = "https://openrouter.ai/api/alpha/decisions"
MODEL = "typesafe/jev-1.13"

BACKGROUND_NOTE = " Facts given in background are provided context, not off-topic."

# One fixture, deliberately tiny: this probe is about the wire format, not calibration.
CHAT = {
    "relationship": "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的",
    "messages": [
        {"from": "other", "text": "你还记得我下周要去干嘛吗"},
        {"from": "me", "text": "记得啊"},
        {"from": "other", "text": "那你说说"},
    ],
    "latest_from": "other",
}

BACKGROUND = (
    "关系：伴侣，在一起 3 年。"
    "联系人备注：她下周三要去上海面试一家设计公司，很紧张。"
    "知识库命中：她对迟到零容忍；她不喝咖啡。"
)

QUESTION = {
    "true_intent": {
        "type": "choice",
        "instructions": (
            "What is the other person's true intent in the latest message, "
            "given the full conversation?" + BACKGROUND_NOTE
        ),
        "criteria": {
            "confirm_you_care": "They are testing whether you remember or still care.",
            "request_action": "They want a concrete action, time, or commitment now.",
            "vent_anger": "They are angry or hurt and want the feeling acknowledged.",
        },
    }
}


def api_key() -> str:
    key = (os.environ.get("OPENROUTER_API_KEY") or "").strip()
    if not key:
        sys.exit(
            "OPENROUTER_API_KEY is not set. Export it in this shell first; "
            "this script never stores or prints it."
        )
    return key


def post(state: dict, key: str) -> tuple[int, str, float]:
    body = json.dumps(
        {"model": MODEL, "state": state, "questions": QUESTION},
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        URL,
        data=body,
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json; charset=utf-8",
            "HTTP-Referer": "https://jev-assistant.local",
            "X-Title": "Jev Assistant",
        },
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode("utf-8"), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), time.time() - t0
    except Exception as e:  # network-level
        return -1, f"{type(e).__name__}: {e}", time.time() - t0


def summarize(tag: str, status: int, text: str, secs: float) -> dict | None:
    print(f"\n=== {tag} ===")
    print(f"HTTP {status}   {secs * 1000:.0f} ms")
    if status != 200:
        print(text[:600])
        return None
    data = json.loads(text)
    ans = (data.get("answers") or {}).get("true_intent") or {}
    print("choice     :", ans.get("choice"))
    print("confidence :", ans.get("confidence"))
    print("probs      :", json.dumps(ans.get("probabilities") or {}, ensure_ascii=False))
    return ans


def main() -> None:
    key = api_key()

    base_state = {"chat": CHAT}
    with_bg = {"chat": CHAT, "background": BACKGROUND}

    s1, t1, d1 = post(base_state, key)
    a1 = summarize("A. state without `background` (current A-stage shape)", s1, t1, d1)

    s2, t2, d2 = post(with_bg, key)
    a2 = summarize("B. state WITH unknown `background` field (D-stage shape)", s2, t2, d2)

    print("\n=== VERDICT ===")
    if s2 == 200 and s1 == 200:
        if a1 and a2 and a1.get("probabilities") == a2.get("probabilities"):
            print("ACCEPTED BUT IGNORED: 200 both ways, identical probabilities ->")
            print("  the extra field does not reach the model. D stage must fold")
            print("  background into an existing field (e.g. chat.relationship).")
        else:
            print("ACCEPTED AND READ: 200 both ways, probabilities differ ->")
            print("  `state.background` reaches the model. D stage can use it as planned.")
    elif s2 in (400, 422):
        print(f"REJECTED: HTTP {s2} on the unknown field ->")
        print("  D stage must NOT add a top-level `background`; nest it under `chat`.")
    else:
        print(f"INCONCLUSIVE: without={s1} with={s2}. Re-run; check network/key/quota.")


if __name__ == "__main__":
    main()
