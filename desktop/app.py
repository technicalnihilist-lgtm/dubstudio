"""
DubStudio — local video dubbing web app.

Run:  python app.py       (then open http://127.0.0.1:8000)

Needs SARVAM_API_KEY in the environment or in a .env file next to this script.
"""

from __future__ import annotations

import json
import os
import shutil
import threading
import time
import traceback
import uuid
from pathlib import Path
from typing import Dict, List

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from dub import (TTS_LANGUAGES, VOICES, DubError, dub_video, find_ffmpeg)

BASE_DIR = Path(__file__).resolve().parent
WORK_DIR = Path(os.environ.get("DUB_WORK_DIR", BASE_DIR / "jobs"))
WORK_DIR.mkdir(parents=True, exist_ok=True)


def load_dotenv() -> None:
    """Tiny .env reader so we don't need an extra dependency."""
    env_file = BASE_DIR / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


load_dotenv()

app = FastAPI(title="DubStudio")

# job_id -> state
JOBS: Dict[str, dict] = {}
JOBS_LOCK = threading.Lock()

ALLOWED_EXT = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".mp3", ".wav", ".m4a"}


def _update(job_id: str, **fields) -> None:
    with JOBS_LOCK:
        JOBS.setdefault(job_id, {}).update(fields)


def _run_job(job_id: str, video_path: str, opts: dict) -> None:
    def progress(stage: str, frac: float, msg: str) -> None:
        _update(job_id, stage=stage, progress=round(frac * 100, 1), message=msg)

    out_dir = WORK_DIR / job_id / "out"
    try:
        _update(job_id, status="running", stage="starting", progress=1.0,
                message="Starting…")
        results = dub_video(
            video_path=video_path,
            out_dir=str(out_dir),
            api_key=os.environ.get("SARVAM_API_KEY", ""),
            targets=opts["targets"],
            speaker=opts["speaker"],
            tone=opts["tone"],
            input_language=opts["input_language"],
            original_volume=opts["original_volume"],
            progress=progress,
        )
        outputs = []
        for r in results:
            outputs.append({
                "language": r.language,
                "language_name": r.language_name,
                "video": f"/api/files/{job_id}/{Path(r.video_path).name}",
                "srt": f"/api/files/{job_id}/{Path(r.srt_path).name}" if r.srt_path else None,
                "transcript": r.transcript[:4000],
                "translated": r.translated[:4000],
            })
        _update(job_id, status="done", stage="done", progress=100.0,
                message="All done.", outputs=outputs)
    except DubError as exc:
        _update(job_id, status="error", message=str(exc))
    except Exception as exc:  # noqa: BLE001
        traceback.print_exc()
        _update(job_id, status="error", message=f"Unexpected error: {exc}")


@app.get("/api/health")
def health() -> JSONResponse:
    ffmpeg_ok, ffmpeg_note = True, ""
    try:
        find_ffmpeg()
    except DubError as exc:
        ffmpeg_ok, ffmpeg_note = False, str(exc)
    has_key = bool(os.environ.get("SARVAM_API_KEY", "").strip())
    return JSONResponse({
        "ffmpeg": ffmpeg_ok,
        "ffmpeg_note": ffmpeg_note,
        "api_key": has_key,
        "languages": [{"code": k, "name": v} for k, v in TTS_LANGUAGES.items()],
        "voices": [{"id": v, "label": label} for v, label in VOICES],
    })


@app.post("/api/dub")
async def create_job(
    file: UploadFile = File(...),
    targets: str = Form("[]"),
    speaker: str = Form("shubh"),
    tone: str = Form("formal"),
    input_language: str = Form("unknown"),
    original_volume: float = Form(0.0),
) -> JSONResponse:
    if not os.environ.get("SARVAM_API_KEY", "").strip():
        raise HTTPException(400, "SARVAM_API_KEY is not set. Add it to your .env file.")

    suffix = Path(file.filename or "video.mp4").suffix.lower()
    if suffix not in ALLOWED_EXT:
        raise HTTPException(400, f"Unsupported file type '{suffix}'.")

    try:
        target_list: List[str] = json.loads(targets)
    except json.JSONDecodeError:
        raise HTTPException(400, "targets must be a JSON list of language codes.")
    target_list = [t for t in target_list if t in TTS_LANGUAGES]
    if not target_list:
        raise HTTPException(400, "Pick at least one output language.")

    job_id = uuid.uuid4().hex[:12]
    job_dir = WORK_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    video_path = job_dir / f"source{suffix}"

    with open(video_path, "wb") as fh:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            fh.write(chunk)

    opts = {
        "targets": target_list,
        "speaker": speaker,
        "tone": tone,
        "input_language": input_language or "unknown",
        "original_volume": max(0.0, min(float(original_volume), 1.0)),
    }

    with JOBS_LOCK:
        JOBS[job_id] = {
            "status": "queued", "stage": "queued", "progress": 0.0,
            "message": "Queued…", "outputs": [], "created": time.time(),
        }

    threading.Thread(target=_run_job, args=(job_id, str(video_path), opts), daemon=True).start()
    return JSONResponse({"job_id": job_id})


@app.get("/api/jobs/{job_id}")
def job_status(job_id: str) -> JSONResponse:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "Unknown job.")
    return JSONResponse(job)


@app.get("/api/files/{job_id}/{name}")
def get_file(job_id: str, name: str) -> FileResponse:
    path = WORK_DIR / job_id / "out" / name
    if not path.is_file() or path.parent.resolve() != (WORK_DIR / job_id / "out").resolve():
        raise HTTPException(404, "File not found.")
    return FileResponse(str(path), filename=name)


app.mount("/", StaticFiles(directory=str(BASE_DIR / "static"), html=True), name="static")


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", "8000"))
    print(f"\n  DubStudio running at http://127.0.0.1:{port}\n")
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")
