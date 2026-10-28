"""
Core dubbing pipeline.

video -> extract audio -> split on silence -> speech-to-text
      -> translate -> text-to-speech -> re-time + mix -> mux back to video

Uses the Sarvam AI REST API (api.sarvam.ai).
"""

from __future__ import annotations

import base64
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import wave
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

import numpy as np
import requests

SARVAM_BASE = os.environ.get("SARVAM_BASE_URL", "https://api.sarvam.ai").rstrip("/")
STT_MODEL = os.environ.get("STT_MODEL", "saaras:v3")
TTS_MODEL = os.environ.get("TTS_MODEL", "bulbul:v3")
SR = 44100  # sample rate of our internal mix

# Languages the TTS engine can speak (BCP-47 -> display name)
TTS_LANGUAGES: Dict[str, str] = {
    "hi-IN": "Hindi",
    "bn-IN": "Bengali",
    "gu-IN": "Gujarati",
    "kn-IN": "Kannada",
    "ml-IN": "Malayalam",
    "mr-IN": "Marathi",
    "od-IN": "Odia",
    "pa-IN": "Punjabi",
    "ta-IN": "Tamil",
    "te-IN": "Telugu",
    "en-IN": "English (India)",
}

# Languages the translation model handles with all tone modes
MAYURA_LANGS = set(TTS_LANGUAGES)

# Input languages the speech recogniser understands (superset of the above)
STT_LANGUAGES = list(TTS_LANGUAGES) + [
    "as-IN", "ur-IN", "ne-IN", "kok-IN", "ks-IN", "sd-IN",
    "sa-IN", "sat-IN", "mni-IN", "brx-IN", "mai-IN", "doi-IN",
]

VOICES = [
    ("shubh", "Shubh — warm male"),
    ("aditya", "Aditya — male"),
    ("rahul", "Rahul — male"),
    ("amit", "Amit — male"),
    ("dev", "Dev — male"),
    ("rohan", "Rohan — male"),
    ("ritu", "Ritu — female"),
    ("priya", "Priya — female"),
    ("neha", "Neha — female"),
    ("ishita", "Ishita — female"),
    ("simran", "Simran — female"),
    ("kavya", "Kavya — female"),
]


class DubError(RuntimeError):
    pass


# --------------------------------------------------------------------------
# ffmpeg helpers
# --------------------------------------------------------------------------

def find_ffmpeg() -> str:
    """Locate an ffmpeg binary: env override, PATH, or the bundled imageio copy."""
    env = os.environ.get("FFMPEG_BIN")
    if env and os.path.exists(env):
        return env
    which = shutil.which("ffmpeg")
    if which:
        return which
    try:
        import imageio_ffmpeg  # type: ignore

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        raise DubError(
            "ffmpeg is not installed. Install it (macOS: `brew install ffmpeg`, "
            "Ubuntu/Debian: `sudo apt install ffmpeg`, Windows: download from "
            "ffmpeg.org) and restart the app."
        )


def _run(cmd: List[str]) -> str:
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise DubError(f"ffmpeg failed: {proc.stderr[-600:]}")
    return proc.stderr


def probe_duration(path: str, ffmpeg: str) -> float:
    """Duration in seconds, parsed from ffmpeg's banner (no ffprobe needed)."""
    # `ffmpeg -i file` with no output always exits 1, so read stderr directly.
    proc = subprocess.run([ffmpeg, "-hide_banner", "-i", path],
                          capture_output=True, text=True)
    out = proc.stderr
    match = re.search(r"Duration:\s*(\d+):(\d+):(\d+\.?\d*)", out)
    if not match:
        raise DubError("Could not read the duration of this file — is it a valid video?")
    h, m, s = match.groups()
    return int(h) * 3600 + int(m) * 60 + float(s)


def extract_audio(video: str, out_wav: str, ffmpeg: str) -> None:
    _run([ffmpeg, "-y", "-hide_banner", "-i", video,
          "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", out_wav])


def detect_silences(wav: str, ffmpeg: str, noise_db: int = -32, min_sil: float = 0.30) -> List[Tuple[float, float]]:
    out = _run([ffmpeg, "-hide_banner", "-i", wav, "-af",
                f"silencedetect=noise={noise_db}dB:d={min_sil}", "-f", "null", "-"])
    starts = [float(x) for x in re.findall(r"silence_start:\s*(-?\d+\.?\d*)", out)]
    ends = [float(x) for x in re.findall(r"silence_end:\s*(-?\d+\.?\d*)", out)]
    spans = []
    for i, s in enumerate(starts):
        e = ends[i] if i < len(ends) else 1e9
        if e > s:
            spans.append((max(0.0, s), e))
    return spans


def build_segments(total: float, silences: List[Tuple[float, float]],
                   max_len: float = 26.0, min_speech: float = 0.45) -> List[Tuple[float, float]]:
    """Turn silence spans into speakable chunks of at most `max_len` seconds."""
    speech: List[Tuple[float, float]] = []
    cursor = 0.0
    for s, e in silences:
        if s > cursor:
            speech.append((cursor, min(s, total)))
        cursor = max(cursor, min(e, total))
    if cursor < total:
        speech.append((cursor, total))
    speech = [(s, e) for s, e in speech if e - s >= min_speech]
    if not speech:
        return []

    segments: List[Tuple[float, float]] = []
    seg_start, seg_end = speech[0]
    for s, e in speech[1:]:
        if e - seg_start <= max_len:
            seg_end = e
        else:
            segments.append((seg_start, seg_end))
            seg_start, seg_end = s, e
    segments.append((seg_start, seg_end))

    # Hard-split anything still too long (fast talker, no pauses)
    final: List[Tuple[float, float]] = []
    for s, e in segments:
        while e - s > max_len:
            final.append((s, s + max_len))
            s += max_len
        final.append((s, e))
    return final


def cut_wav(src: str, dst: str, start: float, dur: float, ffmpeg: str) -> None:
    _run([ffmpeg, "-y", "-hide_banner", "-ss", f"{start:.3f}", "-t", f"{dur:.3f}",
          "-i", src, "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", dst])


def to_mix_format(src: str, dst: str, ffmpeg: str, tempo: float = 1.0) -> None:
    """Resample to our mix format, optionally speeding up to fit the original slot."""
    filters = ["-af", _atempo_chain(tempo)] if abs(tempo - 1.0) > 0.01 else []
    _run([ffmpeg, "-y", "-hide_banner", "-i", src, *filters,
          "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", dst])


def _atempo_chain(tempo: float) -> str:
    tempo = max(0.5, min(tempo, 4.0))
    parts = []
    while tempo > 2.0:
        parts.append("atempo=2.0")
        tempo /= 2.0
    while tempo < 0.5:
        parts.append("atempo=0.5")
        tempo /= 0.5
    parts.append(f"atempo={tempo:.4f}")
    return ",".join(parts)


def read_wav_mono(path: str) -> np.ndarray:
    with wave.open(path, "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise DubError("unexpected wav format from ffmpeg")
        frames = w.readframes(w.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def write_wav_mono(path: str, samples: np.ndarray) -> None:
    data = np.clip(samples, -1.0, 1.0)
    pcm = (data * 32767.0).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def mux(video: str, audio_wav: str, out_path: str, ffmpeg: str) -> None:
    _run([ffmpeg, "-y", "-hide_banner", "-i", video, "-i", audio_wav,
          "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
          "-c:a", "aac", "-b:a", "192k", "-shortest", out_path])


# --------------------------------------------------------------------------
# Sarvam API
# --------------------------------------------------------------------------

def _post(url: str, headers: Dict[str, str], **kwargs):
    last: Optional[Exception] = None
    for attempt in range(4):
        try:
            r = requests.post(url, headers=headers, timeout=180, **kwargs)
        except Exception as exc:  # network hiccup
            last = exc
            time.sleep(1.5 * (attempt + 1))
            continue
        if r.status_code == 200:
            return r.json()
        if r.status_code in (429, 500, 502, 503, 504):
            last = DubError(f"HTTP {r.status_code}: {r.text[:300]}")
            time.sleep(2.0 * (attempt + 1))
            continue
        raise DubError(f"Sarvam API error {r.status_code}: {r.text[:500]}")
    raise DubError(f"Sarvam API unreachable after retries: {last}")


def transcribe(path: str, api_key: str, language_code: str = "unknown") -> Tuple[str, str]:
    with open(path, "rb") as fh:
        data = _post(
            f"{SARVAM_BASE}/speech-to-text",
            headers={"api-subscription-key": api_key},
            files={"file": ("chunk.wav", fh, "audio/wav")},
            data={"model": STT_MODEL, "mode": "transcribe", "language_code": language_code},
        )
    return (data.get("transcript") or "").strip(), data.get("language_code") or ""


def translate(text: str, source: str, target: str, api_key: str, tone: str = "formal") -> str:
    if not text.strip() or source == target:
        return text
    model = "mayura:v1" if (source in MAYURA_LANGS and target in MAYURA_LANGS) else "sarvam-translate:v1"
    payload = {
        "input": text,
        "source_language_code": source or "auto",
        "target_language_code": target,
        "model": model,
        "numerals_format": "international",
    }
    if model == "mayura:v1":
        payload["mode"] = tone
    data = _post(f"{SARVAM_BASE}/translate",
                 headers={"api-subscription-key": api_key, "Content-Type": "application/json"},
                 json=payload)
    return (data.get("translated_text") or "").strip()


def synthesize(text: str, target: str, api_key: str, speaker: str = "shubh",
               pace: float = 1.0) -> bytes:
    payload = {
        "text": text,
        "language_code": target,
        "speaker": speaker,
        "model": TTS_MODEL,
        "speech_sample_rate": 22050,
        "output_audio_codec": "wav",
        "pace": max(0.5, min(pace, 2.0)),
    }
    data = _post(f"{SARVAM_BASE}/text-to-speech",
                 headers={"api-subscription-key": api_key, "Content-Type": "application/json"},
                 json=payload)
    audios = data.get("audios") or []
    if not audios:
        raise DubError("text-to-speech returned no audio")
    return base64.b64decode(audios[0])


# --------------------------------------------------------------------------
# The job
# --------------------------------------------------------------------------

@dataclass
class Chunk:
    start: float
    end: float
    text: str = ""
    source_lang: str = ""


@dataclass
class DubResult:
    language: str
    language_name: str
    video_path: str
    srt_path: Optional[str] = None
    transcript: str = ""
    translated: str = ""


Progress = Callable[[str, float, str], None]


def _noop(stage: str, frac: float, msg: str) -> None:
    pass


def _srt_time(t: float) -> str:
    ms = int(round(t * 1000))
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def dub_video(
    video_path: str,
    out_dir: str,
    api_key: str,
    targets: List[str],
    speaker: str = "shubh",
    tone: str = "formal",
    input_language: str = "unknown",
    original_volume: float = 0.0,
    progress: Progress = _noop,
) -> List[DubResult]:
    """Dub one video into every language in `targets`. Returns one result per language."""
    if not api_key:
        raise DubError("No API key. Set SARVAM_API_KEY in your .env file.")
    bad = [t for t in targets if t not in TTS_LANGUAGES]
    if bad:
        raise DubError(f"Voice output is not available for: {', '.join(bad)}")

    ffmpeg = find_ffmpeg()
    os.makedirs(out_dir, exist_ok=True)
    work = tempfile.mkdtemp(prefix="dub_")

    try:
        progress("preparing", 0.02, "Reading the video…")
        total = probe_duration(video_path, ffmpeg)
        src_wav = os.path.join(work, "source.wav")
        extract_audio(video_path, src_wav, ffmpeg)

        progress("preparing", 0.05, "Finding the spoken parts…")
        silences = detect_silences(src_wav, ffmpeg)
        segments = build_segments(total, silences)
        if not segments:
            raise DubError("No speech found in this video. Is the audio silent?")

        # ---- 1. transcribe once (shared by every target language) ----
        chunks: List[Chunk] = []
        detected = "" if input_language == "unknown" else input_language
        for i, (s, e) in enumerate(segments):
            frac = 0.06 + 0.44 * (i / len(segments))
            progress("transcribing", frac,
                     f"Listening to part {i + 1} of {len(segments)}…")
            part = os.path.join(work, f"in_{i:04d}.wav")
            cut_wav(src_wav, part, s, e - s, ffmpeg)
            lang = detected or "unknown"
            text, lang_out = transcribe(part, api_key, lang)
            if lang_out and not detected:
                detected = lang_out
            chunks.append(Chunk(start=s, end=e, text=text, source_lang=lang_out or detected))

        spoken = [c for c in chunks if c.text]
        if not spoken:
            raise DubError("Could not recognise any speech in this video.")

        progress("transcribing", 0.50,
                 f"Detected language: {TTS_LANGUAGES.get(detected, detected or 'unknown')}")

        # ---- 2. per-language: translate, voice, mix, mux ----
        results: List[DubResult] = []
        n = len(targets)
        for li, target in enumerate(targets):
            base = 0.50 + 0.50 * (li / n)
            span = 0.50 / n
            lang_name = TTS_LANGUAGES[target]

            progress("translating", base + span * 0.05, f"Translating into {lang_name}…")
            translated: List[str] = []
            for i, c in enumerate(chunks):
                translated.append(
                    translate(c.text, c.source_lang or detected, target, api_key, tone)
                    if c.text else ""
                )

            progress("speaking", base + span * 0.35, f"Recording the {lang_name} voice…")
            canvas = np.zeros(int((total + 2.0) * SR), dtype=np.float32)
            placed = []
            for i, (c, text) in enumerate(zip(chunks, translated)):
                if not text:
                    continue
                progress("speaking", base + span * (0.35 + 0.55 * i / max(1, len(chunks))),
                         f"{lang_name}: voicing part {i + 1} of {len(chunks)}…")
                raw = synthesize(text, target, api_key, speaker)
                raw_path = os.path.join(work, f"tts_{li}_{i:04d}.wav")
                with open(raw_path, "wb") as fh:
                    fh.write(raw)
                fitted = os.path.join(work, f"fit_{li}_{i:04d}.wav")
                slot = (chunks[i + 1].start - c.start) if i + 1 < len(chunks) else (total - c.start)
                slot = max(slot, 0.5)
                raw_len = _wav_duration(raw_path)
                tempo = 1.0
                if raw_len > slot * 1.02:
                    tempo = min(raw_len / slot, 2.0)
                to_mix_format(raw_path, fitted, ffmpeg, tempo)
                samples = read_wav_mono(fitted)
                at = int(c.start * SR)
                end = min(len(canvas), at + len(samples))
                canvas[at:end] += samples[: end - at]
                placed.append((c.start, c.end, text))

            if original_volume > 0.001:
                bed = read_wav_mono(_resample(src_wav, os.path.join(work, "bed.wav"), ffmpeg))
                m = min(len(canvas), len(bed))
                canvas[:m] += bed[:m] * float(original_volume)

            peak = float(np.max(np.abs(canvas))) if canvas.size else 0.0
            if peak > 0.98:
                canvas *= 0.98 / peak
            mix_path = os.path.join(work, f"mix_{li}.wav")
            write_wav_mono(mix_path, canvas)

            out_video = os.path.join(out_dir, f"dubbed_{target}.mp4")
            progress("finishing", base + span * 0.95, f"Writing the {lang_name} video…")
            mux(video_path, mix_path, out_video, ffmpeg)

            srt_path = os.path.join(out_dir, f"subtitles_{target}.srt")
            with open(srt_path, "w", encoding="utf-8") as fh:
                for idx, (s, e, text) in enumerate(placed, 1):
                    fh.write(f"{idx}\n{_srt_time(s)} --> {_srt_time(e)}\n{text}\n\n")

            results.append(DubResult(
                language=target,
                language_name=lang_name,
                video_path=out_video,
                srt_path=srt_path,
                transcript=" ".join(c.text for c in chunks if c.text),
                translated=" ".join(translated),
            ))

        progress("done", 1.0, "Finished.")
        return results
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _wav_duration(path: str) -> float:
    with wave.open(path, "rb") as w:
        return w.getnframes() / float(w.getframerate())


def _resample(src: str, dst: str, ffmpeg: str) -> str:
    _run([ffmpeg, "-y", "-hide_banner", "-i", src, "-ac", "1", "-ar", str(SR),
          "-c:a", "pcm_s16le", dst])
    return dst
