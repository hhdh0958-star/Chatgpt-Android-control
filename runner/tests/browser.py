"""Run the bundled, offline worker with a real Chromium renderer on the development machine."""
import argparse
import json
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright


class Assets(SimpleHTTPRequestHandler):
    def end_headers(self):
        workers = "'self'" if self.path == "/index.html" else "'none'"
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; "
                         "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src " + workers)
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *_args):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chromium", required=True)
    args = parser.parse_args()
    folder = Path(__file__).resolve().parents[1] / "assets" / "python"
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(Assets, directory=str(folder)))
    origin = f"http://127.0.0.1:{server.server_port}"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=args.chromium,
                                        args=["--disable-background-networking", "--disable-extensions"])

            def check(name, files, entry, expected_exit, predicate):
                page = browser.new_page()
                try:
                    def serve_project(route):
                        path = route.request.url.partition("/project/")[2]
                        if path in files and path.endswith((".js", ".mjs")):
                            route.fulfill(content_type="application/javascript", body=files[path])
                        else:
                            route.fulfill(status=403, body="")

                    page.route(origin + "/project/**", serve_project)
                    page.on("pageerror", lambda e: print("browser error:", str(e)[:500], flush=True))
                    page.goto(origin + "/index.html")
                    page.evaluate("q => runProject(q)", {"files": files, "entry": entry})
                    deadline = time.monotonic() + 26
                    result = None
                    while result is None and time.monotonic() < deadline:
                        result = page.evaluate("completed")
                        time.sleep(0.15)
                    if result is None:
                        raise AssertionError("No result for " + name)
                    if result["exitCode"] != expected_exit or not predicate(result):
                        raise AssertionError(name + ": " + json.dumps(result))
                    print("PASS", name, json.dumps({k: v for k, v in result.items()
                                                  if k not in ("stdout", "stderr")}), flush=True)
                finally:
                    page.close()

            check("multifile unittest", {
                "compute.py": "def add(a, b): return a + b\n",
                "test.py": "import os,unittest\nfrom compute import add\n"
                           "class Math(unittest.TestCase):\n def test_add(self): self.assertEqual(add(3,4),7)\n"
                           "print('directory', os.getcwd())\nunittest.main()\n",
            }, "test.py", 0, lambda r: "Ran 1 test" in r["stderr"] and "/project" in r["stdout"])
            check("failing assertions", {"test.py": "assert False, 'sample failure'\n"},
                  "test.py", 1, lambda r: "AssertionError: sample failure" in r["stderr"])
            check("bounded output", {"log.py": "print('X'*200000)\n"},
                  "log.py", 0, lambda r: r["truncated"] and len(r["stdout"]) == 2048)
            check("Shell pipeline over explicit snapshot", {
                "main.sh": "grep red sample.txt | sort | uniq -c",
                "sample.txt": "red\nblue\nred\n",
            }, "main.sh", 0, lambda r: r["stdout"].strip() == "2 red")
            check("Shell native command denied", {"main.sh": "su"},
                  "main.sh", 127, lambda r: "not found" in r["stderr"])
            check("Shell virtual writes", {
                "main.sh": "echo temporary > scratch.txt; cat scratch.txt",
            }, "main.sh", 0, lambda r: r["stdout"] == "temporary\n")
            check("JavaScript modules from explicit snapshot", {
                "main.mjs": "import {sum} from './calc.mjs'; console.log(sum(2, 5));",
                "calc.mjs": "export const sum = (a,b) => a + b;",
            }, "main.mjs", 0, lambda r: r["stdout"] == "7\n")
            check("JavaScript omitted project input denied", {
                "main.mjs": "await import('./missing.mjs');",
            }, "main.mjs", 1, lambda r: "missing.mjs" in r["stderr"])
            check("foreign origin, host path and nested worker denied", {"policy.py":
                  "import os,js\nfrom js import XMLHttpRequest\n"
                  "assert not os.path.exists('/data/user/0/dev.nativeagent.probe')\n"
                  "req = XMLHttpRequest.new()\nreq.open('GET', 'https://example.invalid/', False)\n"
                  "try: req.send()\nexcept Exception: pass\nelse: raise AssertionError('foreign network access')\n"
                  "try: js.Worker.new('worker.js')\nexcept Exception: pass\nelse: raise AssertionError('nested worker')\n"
                  "print('scoped')\n"}, "policy.py", 0, lambda r: "scoped" in r["stdout"])
            check("timeout", {"hang.py": "while True: pass\n"}, "hang.py", -1, lambda r: r["timedOut"])
            browser.close()
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
