"""Local-only deterministic API fixture. Does not log or persist request bodies.

Use with adb reverse tcp:18765 tcp:18765 and the isolated debug package.
Judge URL: http://127.0.0.1:18765/decisions (custom provider)
Reply base: http://127.0.0.1:18765/v1
Any non-secret placeholder key is accepted; never use a real API key here.
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        questions = body.get("questions", {})
        if "best_reply" in questions:
            route = "rank"
            response = {"answers": {"best_reply": {"probabilities": {
                "reply_a": 0.8, "reply_b": 0.15, "reply_c": 0.05}}}}
        elif questions:
            route = "judge"
            time.sleep(self.server.judge_delay)
            answers = {}
            for name, question in questions.items():
                kind = question.get("type")
                if kind == "score":
                    answers[name] = {"score": 1, "confidence": 1}
                elif kind == "noul":
                    answers[name] = {"noul": 1}
                else:
                    choice = next(iter(question.get("criteria", {})), "casual_chat")
                    answers[name] = {"choice": choice, "confidence": 1, "probabilities": {choice: 1}}
            response = {"answers": answers}
        else:
            route = "reply"
            time.sleep(self.server.reply_delay)
            response = {"choices": [{"message": {"content": json.dumps([
                "Jev device test - do not send", "Device test candidate B", "Device test candidate C"
            ])}}]}
        payload = json.dumps(response).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        print(f"{route}: HTTP 200", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--judge-delay", type=float, default=4)
    parser.add_argument("--reply-delay", type=float, default=1)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.judge_delay = args.judge_delay
    server.reply_delay = args.reply_delay
    print(f"Local device fixture listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()
