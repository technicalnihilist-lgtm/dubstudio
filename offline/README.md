# DubStudio Offline

Fully on-device dubbing. No API key, no account, no internet after the models download.

This is a **separate Gradle project** from the main app in `../android/`. It has to be,
because the two libraries it needs ship as AARs with their own resources and manifests,
and the hand-rolled `aapt2 + javac + d8` build in `android/build.sh` cannot merge those.

## Build it

1. Install **Android Studio** (Hedgehog or newer) with Android SDK 36.
2. `File → Open` → pick this `offline/` folder.
3. Let Gradle sync (first sync downloads about 200 MB of dependencies).
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`.
5. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

To install it on a phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

If you want a signed release instead, add a `signingConfigs` block to `app/build.gradle`
and run `./gradlew assembleRelease`.

## The two models

| Stage | What does it | Model |
|---|---|---|
| Speech → text | [Vosk](https://alphacephei.com/vosk/) | ~40 MB, downloaded on first use into app storage |
| Text → target language | ML Kit on-device translation | ~30 MB per language pair, downloaded on first use |
| Text → voice | Android's own TextToSpeech engine | already on the phone |

The first dub of a language needs internet for those two downloads; after that the app
works in airplane mode.

## What it can and cannot do

**Speech recognition is the bottleneck.** Vosk publishes offline models for Hindi and
Indian English only — there is no Tamil, Telugu, Bengali, Marathi, Gujarati, Kannada,
Malayalam, Odia or Punjabi model. So the *source* video has to be in Hindi or English.
(Translation *into* all of those languages works, because ML Kit has them.)

Translation covers: Hindi, Bengali, Gujarati, Kannada, Malayalam, Marathi, Tamil, Telugu,
Urdu and English. Punjabi and Odia are not in ML Kit, so those two are rejected with a
clear message rather than producing garbage.

**Voice quality is the phone's.** Google TTS is intelligible but flat — no cloning, no
emotion, nothing like Bulbul or ElevenLabs. If a language has no voice data installed,
Android asks you to download it, and the app tells you where.

**Accuracy is lower than the API version.** Vosk's small models are trained for clean,
close speech; a noisy outdoor video or overlapping speakers will produce rough text, and
rough text translates badly.

## Why it is a separate app

Different applicationId is not set, so it installs *over* the main DubStudio only if the
signing keys match — they will not, since this project has no signing config. Treat it as
its own app; uninstall the other one first.

## Layout

```
settings.gradle, build.gradle      project setup, AGP 8.7.3
app/build.gradle                   module config + the two dependencies
app/AndroidManifest.xml            one permission: INTERNET (for the model downloads)
app/java/com/rajok/dubstudio/
    OfflineEngine.java             Vosk + ML Kit + TextToSpeech pipeline
    Audio.java                     decode, resample, WAV, mid/side background
    DubbingEngine.java             segmentation, encode, mux (shared with the API app)
    MainActivity.java              WebView shell + bridge
    Settings.java, Providers.java, Sarvam.java   present but unused in this build
app/assets/index.html              the UI
app/res/                           theme and icons
```

## If the build fails

Send me the first red line from the Gradle output. The likely spots are the AGP version
(`8.7.3` may want a newer Android Studio) and `compileSdk 36` — dropping both to 35 and
`8.5.2` usually clears it.
