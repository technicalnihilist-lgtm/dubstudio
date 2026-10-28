# DubStudio — free video dubbing into Indian languages

Upload a video once and get it back dubbed into any of 10 Indian languages + English,
with the voice re-recorded, the timing matched to the original, and `.srt` subtitles.

Everything runs on your own machine. The only paid dependency is the Sarvam AI API
(free credits on signup at https://dashboard.sarvam.ai).

---

## What it does

1. Extracts the audio track from your video.
2. Finds the spoken parts by detecting silence, and splits them into chunks of ≤26s
   (the speech-to-text API's per-request limit).
3. Transcribes each chunk in the original language — the language is **detected
   automatically**, so you don't have to know what language the video is in.
4. Translates each chunk into every language you selected.
5. Generates the voice for each chunk with Sarvam's text-to-speech.
6. Places each voice clip at its original timestamp, speeding it up slightly if the
   translation runs longer than the slot it has to fit.
7. Muxes the new audio back onto the untouched video stream (no re-encoding of video,
   so it's fast and lossless).

Because transcription happens once and is reused, dubbing into 4 languages costs
about the same as dubbing into 1, plus translation + voice for each.

## Languages

Hindi, Bengali, Gujarati, Kannada, Malayalam, Marathi, Odia, Punjabi, Tamil, Telugu,
and English (India). Select as many as you like at once — you get one video per language.

Input can be any of 22 Indian languages (plus English) — detection handles it.

## Requirements

- Python 3.10 or newer
- ffmpeg on your PATH
  - macOS: `brew install ffmpeg`
  - Ubuntu / Debian: `sudo apt install ffmpeg`
  - Windows: download from https://ffmpeg.org/download.html and add it to PATH
- A Sarvam AI API key

If ffmpeg is missing, the app falls back to the copy bundled with the
`imageio-ffmpeg` Python package, so it will still usually work.

## Setup

```bash
cd dubapp

# 1. put your key in .env
cp .env.example .env
#    then edit .env and set:  SARVAM_API_KEY=sk_your_key_here

# 2. run
./run.sh          # macOS / Linux
run.bat           # Windows
```

The first run creates a virtual environment and installs the dependencies.
Then open **http://127.0.0.1:8000**.

## Using it

1. Drop a video onto the page.
2. Tick the languages you want.
3. Pick a voice, a translation tone, and how loud the original audio should stay
   underneath the dub (0% = voice only, ~15% keeps background music audible).
4. Press **Dub my video**. Progress is shown live; one video per language appears
   when it's done, along with the subtitle file.

## Command line / your own code

```python
import os
from dub import dub_video

results = dub_video(
    video_path="clip.mp4",
    out_dir="output",
    api_key=os.environ["SARVAM_API_KEY"],
    targets=["hi-IN", "ta-IN", "en-IN"],
    speaker="shubh",            # see VOICES in dub.py
    tone="modern-colloquial",   # formal | classic-colloquial | modern-colloquial | code-mixed
    original_volume=0.1,        # 0.0 = replace original audio
)
for r in results:
    print(r.language_name, r.video_path, r.translated)
```

## Files

```
app.py              web server + job queue (FastAPI)
dub.py              the dubbing pipeline (all the interesting logic)
static/index.html   the whole UI, no build step
requirements.txt
.env.example
run.sh / run.bat
jobs/<id>/          uploaded videos and dubbed output, created at runtime
```

## Notes and limits

- **Cost:** a 1-minute video is roughly 3 speech-to-text calls + 3 translations +
  3 voice calls per language. Sarvam's free credits cover a good amount of testing;
  check https://docs.sarvam.ai for current pricing.
- **Speed:** roughly real-time or faster for short clips. Most of the wall time is
  the API round trips.
- **Voice-only audio:** the dub replaces the original voice entirely. If your video
  has music you want to keep, set "keep original audio" to 10–20% — but note that the
  original *speech* stays in there too at that volume.
- **Long videos:** the whole job runs in one background thread. A 10-minute video is
  fine; very long files will take a while and hold a lot of memory in the mixing step.
- **Timing:** translations that expand a lot get sped up (max 2x) to fit their slot.
  If you want a slower, more natural read, dub one language at a time and pick a
  voice with a slower pace.

## Privacy

Your video never leaves your machine except as short audio chunks sent to the
Sarvam API for transcription, translation and voice generation. Nothing is stored
on any server. Job files stay in `jobs/` until you delete them.
