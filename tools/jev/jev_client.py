"""Jev decisions API client. Standard library only. Never log the API key."""

from __future__ import annotations

import json
import os
import socket
import time
import urllib.error
import urllib.request

API_URL = "https://openrouter.ai/api/alpha/decisions"
MODEL = "typesafe/jev-1.13"
MAX_RETRIES = 3


class JevError(Exception):
    def __init__(self, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


def redact_secrets(text: str) -> str:
    """Strip the live key from any string before print or disk write."""
    if not isinstance(text, str):
        text = str(text)
    key = os.environ.get("OPENROUTER_API_KEY") or ""
    if key:
        text = text.replace(key, "[REDACTED]")
    return text


def _api_key() -> str:
    key = (os.environ.get("OPENROUTER_API_KEY") or "").strip()
    if not key:
        raise JevError(
            "OPENROUTER_API_KEY is not set. Export it in the environment; "
            "do not put the key in a file."
        )
    return key


def _error_body(exc: urllib.error.HTTPError) -> str:
    try:
        raw = exc.read().decode("utf-8", errors="replace")
    except Exception:
        raw = ""
    return redact_secrets(raw)[:800]


def ask(state: dict, questions: dict, timeout: float = 20) -> dict:
    """POST state+questions to Jev. Returns the parsed JSON body.

    Retries HTTP 429 and 529 up to 3 times with exponential backoff.
    Never prints or writes the API key.
    """
    key = _api_key()
    payload = json.dumps(
        {"model": MODEL, "state": state, "questions": questions},
        ensure_ascii=False,
    ).encode("utf-8")

    last_status: int | None = None
    last_body = ""
    for attempt in range(MAX_RETRIES + 1):
        req = urllib.request.Request(
            API_URL,
            data=payload,
            method="POST",
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw)
        except urllib.error.HTTPError as exc:
            last_status = exc.code
            last_body = _error_body(exc)
            if last_status in (429, 529) and attempt < MAX_RETRIES:
                time.sleep(2**attempt)
                continue
            readable = {
                401: "Jev HTTP 401: API key rejected. Check OPENROUTER_API_KEY.",
                422: f"Jev HTTP 422: request body rejected. {last_body}",
                429: f"Jev HTTP 429: rate limited after {MAX_RETRIES} retries. {last_body}",
                529: f"Jev HTTP 529: provider overloaded after {MAX_RETRIES} retries. {last_body}",
            }.get(last_status, f"Jev HTTP {last_status}: {last_body}")
            raise JevError(readable, last_status) from None
        except (TimeoutError, socket.timeout) as exc:
            if attempt < MAX_RETRIES:
                time.sleep(2**attempt)
                continue
            raise JevError(f"Jev request timed out after {timeout}s") from exc
        except urllib.error.URLError as exc:
            reason = redact_secrets(getattr(exc, "reason", exc))
            if attempt < MAX_RETRIES:
                time.sleep(2**attempt)
                continue
            raise JevError(f"Jev request failed: {reason}") from None

    raise JevError(
        f"Jev HTTP {last_status}: exhausted retries. {last_body}", last_status
    )
