"""yt-dlp bridge used by the Kotlin DownloadEngine.

Contract with Kotlin (see YtDlpEngine.kt):
  * every public function returns a JSON string;
  * progress and cancellation are Java objects passed in and called back from here.

No ffmpeg is assumed. When ffmpeg is missing, yt-dlp automatically uses its native HLS
downloader, which produces one file whose real container may still be MPEG-TS. Kotlin sniffs the
container afterwards and remuxes to MP4 with MediaExtractor/MediaMuxer.
"""

import json
import os
import time


def _json(payload):
    return json.dumps(payload, ensure_ascii=False)


class _Logger(object):
    def __init__(self, sink):
        self._sink = sink

    def debug(self, msg):
        if msg.startswith("[debug] "):
            return
        self._sink(str(msg))

    def info(self, msg):
        self._sink(str(msg))

    def warning(self, msg):
        self._sink("warn: " + str(msg))

    def error(self, msg):
        self._sink("error: " + str(msg))


class _Progress(object):
    """Adapts yt-dlp progress hooks to the Kotlin callback, throttled to avoid JNI storms."""

    def __init__(self, callback, cancel):
        self.callback = callback
        self.cancel = cancel
        self.last_emit = 0.0

    def check_cancel(self):
        if self.cancel is not None and self.cancel.isCancelled():
            raise RuntimeError("cancelled-by-user")

    def hook(self, d):
        self.check_cancel()
        if self.callback is None:
            return
        status = d.get("status") or "downloading"
        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
        downloaded = d.get("downloaded_bytes") or 0
        speed = d.get("speed") or 0.0
        now = time.time()
        if status != "finished" and now - self.last_emit < 0.5:
            return
        self.last_emit = now
        percent = float(downloaded) * 100.0 / float(total) if total else -1.0
        try:
            self.callback.onProgress(status, percent, int(downloaded), int(total), float(speed))
        except Exception:
            # A Kotlin-side callback failure must never abort a 700 MB download.
            pass


def _options(out_dir, out_name, referer, user_agent, cookie, max_height, logger):
    headers = {}
    if referer:
        headers["Referer"] = referer
        # Many CDNs require a matching Origin; deriving it from the Referer is the best guess.
        try:
            scheme, rest = referer.split("//", 1)
            host = rest.split("/", 1)[0]
            headers["Origin"] = scheme + "//" + host
        except Exception:
            pass
    if user_agent:
        headers["User-Agent"] = user_agent
    if cookie:
        headers["Cookie"] = cookie

    if max_height:
        # Prefer a single pre-muxed stream: there is no ffmpeg on the device, so "video+audio"
        # cannot be merged and would fail with "ffmpeg not installed".
        fmt = "best[height<=%d]/bestvideo[height<=%d]+bestaudio/best" % (max_height, max_height)
    else:
        fmt = "best[ext=mp4]/best"

    template = os.path.join(out_dir, "%(display)s.%(ext)s")
    if out_name:
        # '.80B' truncates by bytes, which keeps names under the filesystem limit.
        template = os.path.join(out_dir, out_name.replace("%", "") + ".%(ext)s")

    options = {
        "format": fmt,
        "outtmpl": template,
        "paths": {"home": out_dir},
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "socket_timeout": 30,
        "retries": 10,
        "fragment_retries": 10,
        "concurrent_fragment_downloads": 4,
        "nocheckcertificate": True,
        "ignoreerrors": False,
        "overwrites": True,
        "logger": logger,
    }
    if headers:
        options["http_headers"] = headers
    return options


def _dependency_status():
    """Which optional runtime dependencies actually made it into the APK.

    Chaquopy installs with --no-deps, so a missing package is a real possibility. Reporting it here
    turns a silent runtime failure into something the diagnostics screen can show.
    """
    status = {}
    for module, label in (
        ("certifi", "certifi"),
        ("Cryptodome", "pycryptodomex"),
        ("mutagen", "mutagen"),
        ("requests", "requests"),
        ("websockets", "websockets"),
    ):
        try:
            __import__(module)
            status[label] = True
        except Exception:
            status[label] = False
    return status


def version():
    try:
        import yt_dlp
        return _json({
            "ok": True,
            "version": yt_dlp.version.__version__,
            "deps": _dependency_status(),
        })
    except Exception as exc:
        return _json({"ok": False, "error": "%s: %s" % (type(exc).__name__, exc)})


def probe(url, referer=None, user_agent=None, cookie=None):
    """Resolves metadata without downloading, so the UI can confirm a pasted URL is usable."""
    logs = []
    try:
        import yt_dlp

        options = _options("/tmp", "", referer, user_agent, cookie, 0, _Logger(logs.append))
        options["skip_download"] = True
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return _json({"ok": False, "error": "sin-metadata", "log": logs[-20:]})
        formats = info.get("formats") or []
        return _json({
            "ok": True,
            "title": info.get("title"),
            "duration": info.get("duration"),
            "ext": info.get("ext"),
            "is_live": info.get("is_live"),
            "format_count": len(formats),
            "protocols": sorted({f.get("protocol") for f in formats if f.get("protocol")}),
            "log": logs[-20:],
        })
    except Exception as exc:
        return _json({"ok": False, "error": "%s: %s" % (type(exc).__name__, exc), "log": logs[-20:]})


def download(url, out_dir, out_name, referer=None, user_agent=None, cookie=None,
             max_height=0, callback=None, cancel=None):
    """Downloads url into out_dir and reports the resulting file path."""
    logs = []
    info = None
    progress = _Progress(callback, cancel)
    try:
        import yt_dlp

        os.makedirs(out_dir, exist_ok=True)
        options = _options(out_dir, out_name, referer, user_agent, cookie,
                           int(max_height or 0), _Logger(logs.append))
        options["progress_hooks"] = [progress.hook]
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
            if not info:
                return _json({"ok": False, "error": "extraccion-vacia", "log": logs[-40:]})
            requested = info.get("requested_downloads") or []
            if requested and requested[0].get("filepath"):
                path = requested[0]["filepath"]
            else:
                path = ydl.prepare_filename(info)
        if not os.path.exists(path):
            # Native HLS merging can rename the output; fall back to the newest file.
            candidates = [
                os.path.join(out_dir, name)
                for name in os.listdir(out_dir)
                if os.path.isfile(os.path.join(out_dir, name))
            ]
            if not candidates:
                return _json({"ok": False, "error": "archivo-no-encontrado", "log": logs[-40:]})
            path = max(candidates, key=lambda p: os.path.getmtime(p))
        return _json({
            "ok": True,
            "path": path,
            "title": info.get("title"),
            "duration": info.get("duration"),
            "ext": os.path.splitext(path)[1].lstrip("."),
            "size": os.path.getsize(path),
            "log": logs[-40:],
        })
    except Exception as exc:
        message = "%s: %s" % (type(exc).__name__, exc)
        if "cancelled-by-user" in message:
            message = "cancelled"
        return _json({"ok": False, "error": message, "log": logs[-40:]})
