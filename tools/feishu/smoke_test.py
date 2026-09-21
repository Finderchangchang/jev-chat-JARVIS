"""飞书开放平台连通性冒烟测试（PC 上跑，标准库 only）。

用途：App 真机验证前，先在 PC 上确认飞书自建应用的凭据、权限、机器人入群都配好。

用法（密钥只放环境变量，绝不写进文件）：
    set FEISHU_APP_ID=cli_xxx
    set FEISHU_APP_SECRET=xxxxxxxx
    set FEISHU_MY_OPEN_ID=ou_xxx        (可选，用于区分「我」发的消息)
    python tools/feishu/smoke_test.py

只做只读调用：token → 群列表 → 各群最近消息。不含任何写接口。
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = os.environ.get("FEISHU_BASE_URL", "https://open.feishu.cn")
TIMEOUT = 20

TYPE_PLACEHOLDER = {
    "image": "[图片]", "audio": "[语音]", "media": "[视频]", "file": "[文件]",
    "sticker": "[表情包]", "interactive": "[卡片消息]", "share_chat": "[群名片]",
    "share_user": "[个人名片]", "system": "[系统消息]",
}


def redact(text: str) -> str:
    """把 app_secret 从任何输出里抹掉。"""
    secret = os.environ.get("FEISHU_APP_SECRET") or ""
    return text.replace(secret, "[REDACTED]") if secret else text


def _request(
    path: str,
    *,
    query: dict | None = None,
    body: dict | None = None,
    token: str | None = None,
    method: str = "GET",
) -> dict:
    url: str = BASE_URL + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(redact(f"HTTP {exc.code}: {raw[:500]}"))
    except urllib.error.URLError as exc:
        raise SystemExit(f"网络错误：{exc.reason}")


def get_token() -> str:
    """[1/3] 换取 tenant_access_token。"""
    app_id = (os.environ.get("FEISHU_APP_ID") or "").strip()
    app_secret = (os.environ.get("FEISHU_APP_SECRET") or "").strip()
    if not app_id or not app_secret:
        raise SystemExit(
            "请先设置环境变量 FEISHU_APP_ID / FEISHU_APP_SECRET（不要写进任何文件）"
        )
    resp = _request(
        "/open-apis/auth/v3/tenant_access_token/internal",
        body={"app_id": app_id, "app_secret": app_secret},
        method="POST",
    )
    if resp.get("code") != 0:
        raise SystemExit(
            f"获取 tenant_access_token 失败：code={resp.get('code')} msg={resp.get('msg')}"
        )
    print(f"[1/3] token OK，有效期 {resp.get('expire')}s")
    token: str = resp["tenant_access_token"]
    return token


def list_chats(token: str) -> list[dict]:
    """[2/3] 机器人所在的群列表（飞书限制：不含单聊）。"""
    resp = _request("/open-apis/im/v1/chats", query={"page_size": 100}, token=token)
    if resp.get("code") != 0:
        raise SystemExit(
            f"获取群列表失败：code={resp.get('code')} msg={resp.get('msg')}"
            "（检查是否开通机器人能力、授予 im:chat:readonly 并发布应用版本）"
        )
    items: list[dict] = (resp.get("data") or {}).get("items") or []
    print(f"[2/3] 机器人所在群 {len(items)} 个：")
    for it in items:
        print(f"      - {it.get('name')}  ({it.get('chat_id')})")
    return items


def extract_text(msg: dict) -> str:
    """把消息 body.content 解析成可读文本（与 App 内 FeishuMapper 同一套规则）。"""
    msg_type: str = msg.get("msg_type", "")
    raw = (msg.get("body") or {}).get("content") or ""
    try:
        content = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return "<content 解析失败>"
    if msg_type == "text":
        text: str = content.get("text", "")
        for mention in msg.get("mentions") or []:
            text = text.replace(mention.get("key", ""), "@" + mention.get("name", ""))
        return text or TYPE_PLACEHOLDER.get(msg_type, f"[{msg_type}]")
    if msg_type == "post":
        parts: list[str] = []

        def walk(node: object) -> None:
            if isinstance(node, dict):
                if node.get("tag") in ("text", "a"):
                    parts.append(str(node.get("text", "")))
                elif node.get("tag") == "img":
                    parts.append("[图片]")
                else:
                    for v in node.values():
                        walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)

        walk(content.get("content"))
        title = content.get("title") or ""
        joined = (title + "：" if title else "") + " ".join(parts)
        return joined.strip() or "[富文本]"
    return TYPE_PLACEHOLDER.get(msg_type, f"[{msg_type}]")


def peek_messages(token: str, chats: list[dict], my_open_id: str) -> None:
    """[3/3] 各群最近 10 条消息，标记谁发的、当前是否会触发分析。"""
    print("[3/3] 各群最新 10 条消息（[me]=我/机器人，[other]=对方）：")
    triggers = 0
    for chat in chats:
        resp = _request(
            "/open-apis/im/v1/messages",
            query={
                "container_id_type": "chat",
                "container_id": chat["chat_id"],
                "sort_type": "ByCreateTimeDesc",
                "page_size": 10,
            },
            token=token,
        )
        if resp.get("code") != 0:
            print(f"      ! {chat.get('name')}: code={resp.get('code')} msg={resp.get('msg')}")
            continue
        items: list[dict] = (resp.get("data") or {}).get("items") or []
        items.sort(key=lambda m: int(m.get("create_time", "0")))
        print(f"      # {chat.get('name')}")
        latest_side: str | None = None
        for m in items:
            if m.get("deleted"):
                continue
            sender = m.get("sender") or {}
            sender_id = sender.get("id", "")
            side = "me" if sender.get("sender_type") == "app" or sender_id == my_open_id else "other"
            latest_side = side
            print(f"        [{side}] {extract_text(m)[:60]}")
        if latest_side == "other":
            triggers += 1
    print(f"结论：{triggers} 个群当前最新一条来自对方（App 内会对这些群自动触发分析）")


def main() -> int:
    token = get_token()
    chats = list_chats(token)
    if not chats:
        print("机器人不在任何群里：把应用机器人加入目标群后再跑。")
        return 0
    my = (os.environ.get("FEISHU_MY_OPEN_ID") or "").strip()
    peek_messages(token, chats, my)
    return 0


if __name__ == "__main__":
    sys.exit(main())
