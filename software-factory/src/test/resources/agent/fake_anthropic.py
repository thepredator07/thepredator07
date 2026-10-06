"""
A scripted stand-in for the Anthropic Messages API, used by the agent tests so the real Claude Code CLI can run
without a credential or cost. Every request is appended to a log as JSON (method, path, headers, body).

The script is picked by a line `fake-api-script: <name>` anywhere in the prompt. Each reply is the next step of the
script, chosen by how many tool results the conversation already holds; a step is a Bash tool call, and after the
last step the model "answers" with a short summary and stops. Requests without tools (side calls) get plain text.
"""
import json
import re
import sys
import threading
import http.server
import socketserver

PORT = int(sys.argv[1])
LOG = sys.argv[2]
lock = threading.Lock()


def scripts(prompt):
    feedback = "FAIL: test_add_negative" in prompt
    return {
        "edit": ["echo hello > hello.txt"],
        "nothing": [],
        "fix-calc": ["sed -i 's/abs(a) + b/a + b/' calc.py"] if feedback else ["echo '# touched' >> calc.py"],
        "spy": ["(env; cat /proc/[0-9]*/environ 2>/dev/null | tr '\\0' '\\n') > spy.txt; true"],
        # Python rather than `sleep`: the CLI cuts plain long sleeps short by itself.
        "slow": ["(python3 -c 'import time; time.sleep(700)' &); python3 -c 'import time; time.sleep(600)'"],
        "switch-branch": ["git checkout -q -b somewhere-else"],
        "loop": None,
    }


def text_of(content):
    if isinstance(content, str):
        return content
    out = []
    for block in content or []:
        if block.get("type") == "text":
            out.append(block.get("text", ""))
        elif block.get("type") == "tool_result":
            out.append(text_of(block.get("content")))
    return "\n".join(out)


def reply_content(req):
    if not req.get("tools"):
        return [{"type": "text", "text": "ok"}], "end_turn"
    prompt = "\n".join(text_of(m.get("content")) for m in req.get("messages", []) if m.get("role") == "user")
    found = re.search(r"fake-api-script:\s*([\w-]+)", prompt)
    name = found.group(1) if found else "nothing"
    steps = scripts(prompt).get(name, [])
    done = sum(1 for m in req.get("messages", []) if m.get("role") == "user" and isinstance(m.get("content"), list)
               for b in m["content"] if b.get("type") == "tool_result")
    if steps is None or done < len(steps):
        command = "echo turn %d" % done if steps is None else steps[done]
        return [{"type": "tool_use", "id": "toolu_fake_%d" % done, "name": "Bash",
                 "input": {"command": command, "description": "scripted step %d" % done}}], "tool_use"
    return [{"type": "text", "text": "Scripted run '%s' finished after %d step(s)." % (name, done)}], "end_turn"


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def record(self, body):
        with lock, open(LOG, "a") as f:
            f.write(json.dumps({"method": self.command, "path": self.path, "headers": dict(self.headers),
                                "body": body.decode("utf-8", "replace")}) + "\n")

    def send_json(self, obj):
        data = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.record(b"")
        self.send_json({"data": [], "has_more": False})

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("content-length", 0)))
        self.record(body)
        if not self.path.startswith("/v1/messages") or "count_tokens" in self.path:
            self.send_json({"input_tokens": 100})
            return
        req = json.loads(body or b"{}")
        content, stop = reply_content(req)
        usage = {"input_tokens": 1000, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 500,
                 "output_tokens": 50}
        message = {"id": "msg_fake_%d" % id(body), "type": "message", "role": "assistant",
                   "model": req.get("model", "fake"), "content": content, "stop_reason": stop,
                   "stop_sequence": None, "usage": usage}
        if not req.get("stream"):
            self.send_json(message)
            return
        start = dict(message, content=[], stop_reason=None, usage=dict(usage, output_tokens=1))
        events = [("message_start", {"type": "message_start", "message": start})]
        for i, block in enumerate(content):
            if block["type"] == "text":
                events += [("content_block_start", {"type": "content_block_start", "index": i,
                                                    "content_block": {"type": "text", "text": ""}}),
                           ("content_block_delta", {"type": "content_block_delta", "index": i,
                                                    "delta": {"type": "text_delta", "text": block["text"]}})]
            else:
                events += [("content_block_start", {"type": "content_block_start", "index": i,
                                                    "content_block": dict(block, input={})}),
                           ("content_block_delta", {"type": "content_block_delta", "index": i,
                                                    "delta": {"type": "input_json_delta",
                                                              "partial_json": json.dumps(block["input"])}})]
            events.append(("content_block_stop", {"type": "content_block_stop", "index": i}))
        events += [("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None},
                                      "usage": {"output_tokens": 50}}),
                   ("message_stop", {"type": "message_stop"})]
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("transfer-encoding", "chunked")
        self.end_headers()
        for name, event in events:
            chunk = ("event: %s\ndata: %s\n\n" % (name, json.dumps(event))).encode()
            self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
            self.wfile.flush()
        self.wfile.write(b"0\r\n\r\n")


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


Server(("0.0.0.0", PORT), Handler).serve_forever()
