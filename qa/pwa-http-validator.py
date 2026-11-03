#!/usr/bin/env python3
"""Local packaged-JAR regression for reproducible-build HTTP validators.

Build with -Pweb -Dproject.build.outputTimestamp=1980-02-01T00:00:00Z.
No external requests, guest identities, or game mutations are made.
"""
import argparse
import hashlib
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--jar", required=True)
parser.add_argument("--java", default="java")
parser.add_argument("--output", required=True)
args = parser.parse_args()
jar = os.path.abspath(args.jar)
results = []

def request(base, path, headers=None, method="GET"):
    req = urllib.request.Request(base + path, headers=headers or {}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, dict(response.headers), response.read()

def check(label, condition):
    assert condition, label
    results.append(label)

with zipfile.ZipFile(jar) as archive:
    expected = {path: archive.read("BOOT-INF/classes/static/" + path)
                for path in ("sw.js", "index.html", "manifest.webmanifest")}
    for path in expected:
        info = archive.getinfo("BOOT-INF/classes/static/" + path)
        check(path + " uses fixed 1980 archive timestamp", info.date_time[:3] == (1980, 2, 1))

def run_server(baseline):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    command = [args.java, "-jar", jar, "--server.address=127.0.0.1",
               f"--server.port={port}", "--spring.profiles.active=default"]
    if baseline:
        command.append("--spring.web.resources.cache.use-last-modified=true")
    with tempfile.TemporaryFile() as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(150):
                if process.poll() is not None:
                    log.seek(0)
                    raise RuntimeError(log.read().decode(errors="replace"))
                try:
                    if request(base, "/health/ready")[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError("Local server did not become ready")

            if baseline:
                status, headers, body = request(base, "/sw.js")
                check("baseline serves current worker bytes", status == 200 and body == expected["sw.js"])
                old = headers.get("Last-Modified", "")
                check("baseline exposes fixed 1980 validator", "1980" in old)
                status, _, body = request(base, "/sw.js", {"If-Modified-Since": old})
                check("baseline reproduces 304 with no new body", status == 304 and body == b"")
            else:
                validators = [
                    {"If-Modified-Since": "Fri, 01 Feb 1980 00:00:00 GMT"},
                    {"If-Modified-Since": "Thu, 31 Dec 2099 23:59:59 GMT"},
                    {"If-None-Match": '"previous-release"',
                     "If-Modified-Since": "Fri, 01 Feb 1980 00:00:00 GMT"},
                ]
                for path in ("/", "/index.html", "/sw.js", "/manifest.webmanifest"):
                    name = "index.html" if path == "/" else path[1:]
                    for index, validator in enumerate(validators):
                        status, headers, body = request(base, path, validator)
                        check(f"{path} old validator {index}: fresh exact body/no date validator",
                              status == 200 and body == expected[name]
                              and "Last-Modified" not in headers
                              and headers.get("Cache-Control") == "no-cache")
                status, headers, body = request(base, "/sw.js", validators[0], "HEAD")
                check("HEAD cannot falsely validate old worker", status == 200 and body == b""
                      and "Last-Modified" not in headers)
                status, headers, _ = request(base, "/api/me")
                check("private API stays unauthorized/no-store", status == 401
                      and "no-store" in headers.get("Cache-Control", ""))
                status, headers, _ = request(base, "/health/ready")
                check("health remains ready/no-store", status == 200
                      and "no-store" in headers.get("Cache-Control", ""))
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

run_server(True)
run_server(False)
record = {"status": "PASS", "checks": results, "count": len(results),
          "jar_sha256": hashlib.sha256(open(jar, "rb").read()).hexdigest(),
          "scope": "loopback packaged JAR only; no real-device PWA claim"}
with open(args.output, "w") as output:
    json.dump(record, output, indent=2)
print(json.dumps(record, indent=2))
