#!/usr/bin/env python3
"""Verify subtitle switching and HTTP byte counts with the bundled macOS mpv.

Requires ffmpeg and Xcode command-line tools. Generated media/cache files live in
a temporary directory. No installed application bundle is changed.
"""

import http.server
import os
import itertools
import platform
from pathlib import Path
import subprocess
import tempfile
import threading
import time


def main():
    tests = Path(__file__).resolve().parent
    arch = os.environ.get("NUVIO_TEST_ARCH", platform.machine())
    runtime = Path(os.environ.get("NUVIO_MPV_RUNTIME", tests.parent / "runtime" / arch))
    with tempfile.TemporaryDirectory(prefix="nuvio-subtitle-ram-") as directory:
        root = Path(directory)
        for track in (1, 2):
            (root / f"{track}.srt").write_text("\n\n".join(
                f"{i + 1}\n00:00:{i:02},000 --> 00:00:{i:02},900\nTrack {track} cue {i}"
                for i in range(40)
            ))
        subprocess.run([
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
            "testsrc2=size=640x360:rate=30:duration=40", "-f", "lavfi", "-i",
            "sine=frequency=440:duration=40", "-i", str(root / "1.srt"),
            "-i", str(root / "2.srt"), "-map", "0", "-map", "1", "-map", "2", "-map", "3",
            "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "2M", "-c:a", "aac",
            "-c:s", "srt", str(root / "test.mkv"),
        ], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(root / "test.mkv"),
                        "-map", "0", "-c:v", "copy", "-c:a", "copy", "-c:s", "mov_text",
                        "-movflags", "+faststart", str(root / "test.mp4")], check=True)
        files = {name: (root / name).read_bytes() for name in ("test.mkv", "test.mp4")}
        files["streaming.mkv"] = files["test.mkv"]
        files["external.srt"] = (root / "1.srt").read_bytes().replace(b"Track 1 cue", b"External cue")
        counters = {"bytes": 0, "requests": 0, "denied": 0}
        lock = threading.Lock()

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *unused):
                pass

            def do_GET(self):
                if self.headers.get("X-Nuvio-Test") != "subtitles":
                    with lock:
                        counters["denied"] += 1
                    self.send_error(403)
                    return
                name = self.path.rsplit("/", 1)[-1]
                if name not in files:
                    self.send_error(404)
                    return
                media = files[name]
                is_movie = name != "external.srt"
                with lock:
                    counters["requests"] += int(is_movie)
                start = int(self.headers.get("Range", "bytes=0-").split("=")[1].split("-")[0])
                time.sleep(0.15)
                try:
                    self.send_response(206 if "Range" in self.headers else 200)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Length", str(len(media) - start))
                    self.send_header("Content-Range", f"bytes {start}-{len(media) - 1}/{len(media)}")
                    self.end_headers()
                    for offset in range(start, len(media), 32768):
                        chunk = media[offset:offset + 32768]
                        self.wfile.write(chunk)
                        self.wfile.flush()
                        with lock:
                            counters["bytes"] += len(chunk) if is_movie else 0
                        time.sleep(0.05 if name == "streaming.mkv" else 0.015)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        executable = root / "cache-test"
        subprocess.run([
            "clang++", "-arch", arch, "-std=c++17", "-fobjc-arc", "-framework", "Foundation",
            str(tests / "subtitle_ram_cache_test.mm"), "-o", str(executable),
            f"-I{tests.parent / 'include'}", str(runtime / "libmpv.2.dylib"),
            f"-Wl,-rpath,{runtime}",
        ], check=True)
        environment = os.environ.copy()
        environment["DYLD_LIBRARY_PATH"] = str(runtime)
        environment["TMPDIR"] = str(root)
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            for filename, mode in [*itertools.product(("test.mkv", "test.mp4"), ("baseline", "cached", "capped")), ("streaming.mkv", "streaming")]:
                with lock:
                    counters.update(bytes=0, requests=0, denied=0)
                process = subprocess.Popen([
                    str(executable), f"http://127.0.0.1:{server.server_port}/{filename}", mode,
                ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, env=environment)
                try:
                    ready = process.stdout.readline().strip()
                    if ready != "READY":
                        raise AssertionError(f"Player did not become ready: {ready}")
                    with lock:
                        before = counters.copy()
                    output, _ = process.communicate("\n", timeout=20)
                    if process.returncode:
                        raise AssertionError(f"Player failed: {output}")
                    with lock:
                        after = counters.copy()
                    extra_bytes = after["bytes"] - before["bytes"]
                    extra_requests = after["requests"] - before["requests"]
                    print(f"{filename} {mode}: {output.strip()} additional_bytes={extra_bytes} additional_requests={extra_requests}")
                    if mode == "streaming":
                        assert before["bytes"] < len(files[filename]), "Exercise a partially downloaded movie"
                        assert after["bytes"] <= len(files[filename]), "Only the original movie download"
                    assert not after["denied"], "HTTP headers must reach the original server"
                    if mode != "baseline":
                        assert mode == "streaming" or extra_bytes == 0, "Cached switching/seeking must not download media again"
                        assert extra_requests == 0, "Cached switching/seeking must not reopen the HTTP source"
                    else:
                        assert extra_bytes > 0 and extra_requests > 0, "Reproduce the original range reread"
                    assert not list(root.glob("ffcache*")), "Temporary cache files must not be left behind"
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
        finally:
            server.shutdown()


if __name__ == "__main__":
    main()
