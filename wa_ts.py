#!/usr/bin/env python3
"""
WhatsApp Voice Note & Audio Transcriber for Termux (Android).
Watches WhatsApp media directories, correlates voice notes with sender/group
notifications, and transcribes audio via Gemini API upon notification interaction.
"""

import base64
import hashlib
import html
import json
import mimetypes
import os
import shlex
import stat
import subprocess
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

# ─────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────

WATCH_DIRS = [
    # Modern Android (Scoped Storage)
    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
    # WhatsApp Business
    "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
    "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio",
    # Legacy Android Storage Paths
    "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes",
    "/storage/emulated/0/WhatsApp/Media/WhatsApp Audio",
]

SUPPORTED_AUDIO_EXTENSIONS = {".opus", ".ogg", ".m4a", ".mp3", ".aac", ".wav", ".amr", ".3gp"}

FIFO = os.path.join(os.getenv("TMPDIR", "/tmp"), "wa_transcriber")

DEFAULT_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
SCAN_INTERVAL = 1.0
NOTIFICATION_POLL_INTERVAL = 0.5
NOTIFICATION_MATCH_WINDOW = 12.0  # seconds
NOTIFICATION_RETENTION = 45.0  # seconds
MAX_WORKERS = 2


# ─────────────────────────────────────────────
# Notification Listener (WhatsApp Sender Detection)
# ─────────────────────────────────────────────


class WhatsAppNotificationListener:
    """Monitors Android notifications via Termux:API to correlate voice notes with senders."""

    def __init__(self):
        self.notifications = {}
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = None

    @staticmethod
    def get_notifications():
        try:
            result = subprocess.run(["termux-notification-list"], capture_output=True, text=True, check=True, timeout=5)
            data = result.stdout.strip()
            return json.loads(data) if data else []
        except (subprocess.SubprocessError, json.JSONDecodeError, OSError):
            return []

    @staticmethod
    def parse_time(value):
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(value / 1000 if value > 1e11 else value)
        if not isinstance(value, str):
            return datetime.now()

        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f"):
            try:
                return datetime.strptime(value, fmt)
            except ValueError:
                pass
        try:
            return datetime.fromisoformat(value)
        except Exception:
            return datetime.now()

    @staticmethod
    def is_voice_message(notification):
        pkg = notification.get("packageName", "")
        if pkg not in ("com.whatsapp", "com.whatsapp.w4b"):
            return False

        content = (notification.get("content") or "").lower()
        lines = " ".join(notification.get("lines") or []).lower()
        full_text = f"{content} {lines}"

        keywords = ("voice message", "audio message", "voice note", "ptt", "audio (", "🎤")
        return any(kw in full_text for kw in keywords) or bool(notification.get("tag") and "voice" in full_text)

    @staticmethod
    def extract_sender(notification):
        """
        Extracts the individual contact name.
        If in a group, extracts the member name from 'Sender: 🎤 Voice message'
        and returns 'Sender (Group Name)'.
        """
        title = (notification.get("title") or notification.get("extraTitle") or "").strip()
        content = (notification.get("content") or "").strip()
        lines = notification.get("lines") or []

        # Check content and multi-line notification entries
        candidates = [content] + list(reversed(lines))
        for text in candidates:
            if not text or ":" not in text:
                continue

            sender_part, msg_part = text.split(":", 1)
            sender_part = sender_part.strip()
            msg_part = msg_part.strip().lower()

            # Verify that the message body contains audio indicators
            # and that sender_part is not the audio tag itself
            is_msg_voice = any(kw in msg_part for kw in ("voice", "audio", "ptt", "🎤", "message"))
            is_sender_voice = any(kw in sender_part.lower() for kw in ("voice message", "audio (", "🎤"))

            if sender_part and is_msg_voice and not is_sender_voice:
                if title and title.lower() != sender_part.lower():
                    # Group chat: sender + group title
                    return f"{sender_part} ({title})"
                return sender_part

        return title or "Unknown"

    def update(self):
        now = datetime.now()
        raw_notifications = self.get_notifications()

        for notification in raw_notifications:
            if not self.is_voice_message(notification):
                continue

            when = notification.get("when")
            if not when:
                continue

            key = notification.get("key") or str(notification.get("id")) or notification.get("title", "")
            sender = self.extract_sender(notification)

            with self.lock:
                if key in self.notifications:
                    continue

                self.notifications[key] = {"time": self.parse_time(when), "sender": sender}

        cutoff = now - timedelta(seconds=NOTIFICATION_RETENTION)
        with self.lock:
            self.notifications = {key: n for key, n in self.notifications.items() if n["time"] >= cutoff}

    def listen(self):
        while not self.stop_event.is_set():
            try:
                self.update()
            except Exception as exc:
                print(f"[WARN] Notification poll error: {exc}", file=sys.stderr)
            self.stop_event.wait(NOTIFICATION_POLL_INTERVAL)

    def start(self):
        self.thread = threading.Thread(target=self.listen, name="whatsapp-notification-listener", daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.5)

    def match(self, detected_at):
        with self.lock:
            if not self.notifications:
                return None

            candidates = []
            for key, notification in self.notifications.items():
                diff = abs((detected_at - notification["time"]).total_seconds())
                if diff <= NOTIFICATION_MATCH_WINDOW:
                    candidates.append((key, notification, diff))

            if not candidates:
                return None

            key, notification, diff = min(candidates, key=lambda item: item[2])
            del self.notifications[key]
            return notification["sender"]


# ─────────────────────────────────────────────
# WhatsApp Transcriber Engine
# ─────────────────────────────────────────────


class WhatsAppTranscriber:
    def __init__(self):
        self.pending = {}
        self.processing = set()

        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.executor = ThreadPoolExecutor(max_workers=MAX_WORKERS)
        self.notification_listener = WhatsAppNotificationListener()
        self.fifo_thread = None

    def __enter__(self):
        for root in WATCH_DIRS:
            if os.path.exists(os.path.dirname(root)):
                os.makedirs(root, exist_ok=True)

        self.setup_fifo()
        self.notification_listener.start()

        print("Listening for WhatsApp audio in:")
        active_dirs = [d for d in WATCH_DIRS if os.path.isdir(d)]
        for root in active_dirs:
            print(f"  📁 {root}")
        if not active_dirs:
            print("  ⚠️  No active WhatsApp media directories found yet. Watching paths for creation...")

        print(f"🔗 IPC Pipe: {FIFO}")

        self.fifo_thread = threading.Thread(target=self.fifo_listener, name="fifo-listener", daemon=True)
        self.fifo_thread.start()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.stop()

    def stop(self):
        self.stop_event.set()
        self.notification_listener.stop()

        # Unblock FIFO reader thread cleanly
        try:
            if os.path.exists(FIFO):
                fd = os.open(FIFO, os.O_WRONLY | os.O_NONBLOCK)
                os.write(fd, b"__SHUTDOWN__|nocopy\n")
                os.close(fd)
        except OSError:
            pass

        if self.fifo_thread and self.fifo_thread.is_alive():
            self.fifo_thread.join(timeout=1.5)

        self.executor.shutdown(wait=False, cancel_futures=True)
        self.cleanup_fifo()

    # ─────────────────────────────────────────────
    # System Helpers
    # ─────────────────────────────────────────────

    @staticmethod
    def exec_cmd(*args, input_bytes=None):
        try:
            subprocess.run(args, input=input_bytes, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as exc:
            print(f"[WARN] Command failed {args[0]}: {exc}", file=sys.stderr)

    @staticmethod
    def file_hash(path):
        return hashlib.sha1(os.path.abspath(path).encode()).hexdigest()[:12]

    @staticmethod
    def media_type(path):
        norm = os.path.normpath(path).lower()
        if "voice" in norm:
            return "Voice Note"
        if "audio" in norm:
            return "Audio"
        return "Audio"

    def notification_id(self, path):
        return "wa_" + self.file_hash(path)

    # ─────────────────────────────────────────────
    # FIFO Management
    # ─────────────────────────────────────────────

    def setup_fifo(self):
        if os.path.exists(FIFO):
            if not stat.S_ISFIFO(os.stat(FIFO).st_mode):
                os.remove(FIFO)
                os.mkfifo(FIFO, 0o600)
        else:
            os.mkfifo(FIFO, 0o600)

    def cleanup_fifo(self):
        if os.path.exists(FIFO) and stat.S_ISFIFO(os.stat(FIFO).st_mode):
            try:
                os.unlink(FIFO)
            except OSError:
                pass

    @staticmethod
    def fifo_action(file_id, copy=False):
        action = "copy" if copy else "nocopy"
        return f"printf '%s|%s\\n' '{file_id}' '{action}' > '{FIFO}'"

    def fifo_listener(self):
        try:
            fd = os.open(FIFO, os.O_RDWR)
            pipe = os.fdopen(fd, "r")
        except OSError as exc:
            print(f"[ERROR] Failed to open FIFO: {exc}", file=sys.stderr)
            return

        try:
            for line in pipe:
                if self.stop_event.is_set():
                    break

                line = line.strip()
                if not line or line.startswith("__SHUTDOWN__"):
                    continue

                try:
                    parts = line.split("|", 1)
                    if len(parts) != 2:
                        continue
                    file_id, action = parts
                except ValueError:
                    continue

                if action not in ("copy", "nocopy"):
                    continue

                with self.lock:
                    pending = self.pending.get(file_id)
                    if pending is None:
                        print(f"[WARN] Unknown file hash from action: {file_id}")
                        continue

                    if file_id in self.processing:
                        print(f"[INFO] Already processing: {pending['path']}")
                        continue

                    path = pending["path"]
                    sender = pending["sender"]
                    self.processing.add(file_id)

                copy = action == "copy"
                self.notify_processing(path, sender, copy)
                self.executor.submit(self.transcribe_and_finish, file_id, path, sender, copy)
        finally:
            pipe.close()

    # ─────────────────────────────────────────────
    # Notification Handlers
    # ─────────────────────────────────────────────

    def notify_new(self, path, detected_at):
        file_id = self.file_hash(path)
        kind = self.media_type(path)
        self.notification_listener.update()
        sender = self.notification_listener.match(detected_at)

        print(f"[DETECTED] {kind}: {path} (Sender: {sender or 'Unknown'})")

        with self.lock:
            self.pending[file_id] = {"path": path, "sender": sender}

        title = f"🎵 New WhatsApp {kind}"
        if sender:
            title += f" — {sender}"

        self.exec_cmd(
            "termux-notification",
            "--id",
            self.notification_id(path),
            "--title",
            title,
            "--content",
            os.path.basename(path),
            "--button1",
            "TS & Copy",
            "--button1-action",
            self.fifo_action(file_id, True),
            "--button2",
            "TS",
            "--button2-action",
            self.fifo_action(file_id, False),
            "--priority",
            "high",
        )

    def notify_processing(self, path, sender, copy):
        text = "Transcribing & copying..." if copy else "Transcribing..."
        title = f"⚙️ {text}"
        if sender:
            title += f" — {sender}"

        self.exec_cmd(
            "termux-notification",
            "--id",
            self.notification_id(path),
            "--title",
            title,
            "--content",
            os.path.basename(path),
            "--alert-once",
        )

    def notify_result(self, path, sender, text):
        notification_id = self.notification_id(path)
        action = (
            self.open_in_html_viewer(text, path, sender)
            + " && termux-notification-remove "
            + shlex.quote(notification_id)
        )

        title = f"📝 {self.media_type(path)} Transcription"
        if sender:
            title += f" — {sender}"

        self.exec_cmd(
            "termux-notification",
            "--id",
            notification_id,
            "--title",
            title,
            "--content",
            text,
            "--action",
            action,
        )

    def notify_error(self, path, sender, err_msg):
        notification_id = self.notification_id(path)
        title = f"❌ Transcription Error ({self.media_type(path)})"
        if sender:
            title += f" — {sender}"

        self.exec_cmd(
            "termux-notification",
            "--id",
            notification_id,
            "--title",
            title,
            "--content",
            str(err_msg)[:120],
        )

    # ─────────────────────────────────────────────
    # Transcription API & Parsing
    # ─────────────────────────────────────────────

    @staticmethod
    def _extract_response_text(result):
        """Extracts generated text reliably from various Gemini REST schemas."""
        if result.get("output_text"):
            return result["output_text"].strip()

        outputs = result.get("outputs")
        if isinstance(outputs, list):
            for out in reversed(outputs):
                if isinstance(out, dict) and "text" in out:
                    return out["text"].strip()
                if isinstance(out, str):
                    return out.strip()

        steps = result.get("steps")
        if isinstance(steps, list):
            for step in reversed(steps):
                if not isinstance(step, dict):
                    continue
                content_list = step.get("content")
                if isinstance(content_list, list):
                    for item in content_list:
                        if isinstance(item, dict) and "text" in item:
                            text_val = item["text"].strip()
                            if text_val:
                                return text_val
                elif isinstance(step.get("text"), str):
                    return step["text"].strip()

        candidates = result.get("candidates")
        if isinstance(candidates, list):
            for cand in candidates:
                parts = cand.get("content", {}).get("parts", [])
                for part in parts:
                    if "text" in part and part["text"].strip():
                        return part["text"].strip()

        return None

    def transcribe(self, path):
        api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
        if not api_key:
            raise RuntimeError("Neither GEMINI_API_KEY nor GOOGLE_API_KEY is set in environment.")

        if not os.path.exists(path) or os.path.getsize(path) == 0:
            raise RuntimeError(f"Audio file does not exist or is empty: {path}")

        ext = os.path.splitext(path)[1].lower()
        if ext == ".opus":
            mime_type = "audio/ogg"
        elif ext == ".m4a":
            mime_type = "audio/mp4"
        else:
            mime_type = mimetypes.guess_type(path)[0] or "audio/ogg"

        with open(path, "rb") as audio_file:
            audio_data = base64.b64encode(audio_file.read()).decode("ascii")

        payload = {
            "model": DEFAULT_MODEL,
            "input": [
                {
                    "type": "text",
                    "text": (
                        "Transcribe this audio exactly as spoken. "
                        "Do not translate it. "
                        "The speech may be Hindi, English, Hinglish, "
                        "or a mixture of Hindi and English. "
                        "Write Hindi and other non-English speech using "
                        "Latin/English characters (Romanized script), "
                        "not Devanagari or other native scripts. "
                        "Preserve English words as English. "
                        "Do not paraphrase, summarize, correct wording, "
                        "or add explanations. "
                        "Return only the exact transcription."
                    ),
                },
                {"type": "audio", "data": audio_data, "mime_type": mime_type},
            ],
        }

        request = urllib.request.Request(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                result = json.load(response)
        except urllib.error.HTTPError as http_err:
            error_body = http_err.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Gemini API HTTP {http_err.code}: {error_body}") from http_err
        except urllib.error.URLError as net_err:
            raise RuntimeError(f"Network error connecting to Gemini API: {net_err.reason}") from net_err

        text = self._extract_response_text(result)
        if not text:
            raise RuntimeError(f"Failed to parse text from Gemini response: {json.dumps(result)[:200]}")

        return text

    @staticmethod
    def open_in_html_viewer(text, path, sender):
        kind = WhatsAppTranscriber.media_type(path)
        filename = os.path.basename(path)

        title = f"WhatsApp {kind}"
        if sender:
            title += f" — {sender}"

        document = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  :root {{
    color-scheme: light dark;
    --bg: #ffffff;
    --fg: #1a1a1a;
    --card: #f4f4f5;
    --border: #e4e4e7;
    --accent: #2563eb;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg: #121212;
      --fg: #f3f4f6;
      --card: #1e1e1e;
      --border: #2e2e2e;
      --accent: #60a5fa;
    }}
  }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background-color: var(--bg);
    color: var(--fg);
    padding: 16px;
    margin: 0;
    line-height: 1.6;
  }}
  .header {{
    font-size: 1.15rem;
    font-weight: 700;
    color: var(--accent);
    margin-bottom: 4px;
  }}
  .meta {{
    font-size: 0.85rem;
    opacity: 0.7;
    margin-bottom: 12px;
    word-break: break-all;
  }}
  hr {{
    border: none;
    border-top: 1px solid var(--border);
    margin: 12px 0;
  }}
  .content {{
    background: var(--card);
    padding: 14px;
    border-radius: 8px;
    font-size: 1rem;
    white-space: pre-wrap;
    word-wrap: break-word;
    border: 1px solid var(--border);
  }}
</style>
</head>
<body>
  <div class="meta">{html.escape(filename)}</div>
  <hr>
  <div class="content">{html.escape(text)}</div>
</body>
</html>"""

        uri = "data:text/html;charset=utf-8," + urllib.parse.quote(document)
        return (
            "am start "
            "-n com.android.htmlviewer/.HTMLViewerActivity "
            "-a android.intent.action.VIEW "
            "-d "
            f"{shlex.quote(uri)} "
            "--es android.intent.extra.TITLE "
            f"{shlex.quote(title)}"
        )

    def transcribe_and_finish(self, file_id, path, sender, copy):
        try:
            print(f"🎙️ Transcribing: {path}")
            text = self.transcribe(path)

            if copy:
                self.exec_cmd("termux-clipboard-set", input_bytes=text.encode("utf-8"))
                self.exec_cmd("termux-toast", "Transcript Copied")

            self.notify_result(path, sender, text)
            print(f"✅ Success [{path}]:\n{text}\n")

        except Exception as exc:
            print(f"❌ Transcription failed for {path}: {exc}", file=sys.stderr)
            self.notify_error(path, sender, str(exc))
            self.exec_cmd("termux-toast", f"TS Failed: {exc}")

        finally:
            with self.lock:
                self.processing.discard(file_id)

    # ─────────────────────────────────────────────
    # File Scanner Loop
    # ─────────────────────────────────────────────

    def find_files(self):
        for root in WATCH_DIRS:
            if not os.path.isdir(root):
                continue

            for dirpath, _, filenames in os.walk(root):
                for filename in filenames:
                    if filename.startswith("."):
                        continue
                    ext = os.path.splitext(filename)[1].lower()
                    if ext not in SUPPORTED_AUDIO_EXTENSIONS:
                        continue

                    full_path = os.path.join(dirpath, filename)
                    try:
                        if os.path.getsize(full_path) > 0:
                            yield full_path
                    except OSError:
                        continue

    def scanner(self):
        seen = set(self.find_files())
        print(f"Initialized scanner: {len(seen)} existing audio files indexed.")

        while not self.stop_event.is_set():
            for path in self.find_files():
                if self.stop_event.is_set():
                    break

                if path in seen:
                    continue

                seen.add(path)
                detected_at = datetime.now()
                self.notify_new(path, detected_at)

            self.stop_event.wait(SCAN_INTERVAL)

    def run(self):
        try:
            self.scanner()
        except KeyboardInterrupt:
            print("\nShutting down transcriber daemon...")


if __name__ == "__main__":
    if not (os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")):
        print(
            "⚠️  Warning: GEMINI_API_KEY environment variable is not set.\n"
            "Please run: export GEMINI_API_KEY='your_api_key'",
            file=sys.stderr,
        )

    with WhatsAppTranscriber() as app:
        app.run()
