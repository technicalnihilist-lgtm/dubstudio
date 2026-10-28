package com.rajok.dubstudio;

import android.content.Context;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fully on-device dubbing: no API key, no network once the models are down.
 *
 *   speech  -> Vosk model (downloaded once, ~50 MB)
 *   text    -> ML Kit on-device translation model (downloaded once, ~30 MB)
 *   voice   -> the phone's own TextToSpeech engine (already installed)
 *
 * Needs a Gradle build: both libraries ship AARs with resources and manifests,
 * which the no-Gradle build.sh in this repo cannot merge.
 */
public final class OfflineEngine {

    /** Vosk ships these two Indic models; there is no Tamil/Telugu/Bengali offline model. */
    public static final String[] SOURCE_CODES = {"hi-IN", "en-IN"};
    public static final String[] SOURCE_NAMES = {"Hindi", "English (India)"};
    private static final String[] VOSK_MODELS = {"vosk-model-small-hi-0.22", "vosk-model-small-en-in-0.4"};

    private OfflineEngine() {}

    public static int modelSizeMb(int index) {
        return index == 0 ? 42 : 39;
    }

    // ------------------------------------------------------------------

    public static List<DubbingEngine.Result> run(Context ctx, Uri src, List<String> targets,
                                                 int sourceIndex, double background,
                                                 DubbingEngine.Listener listener)
            throws Exception {

        String sourceCode = SOURCE_CODES[sourceIndex];
        listener.onProgress("preparing", 3, "Reading the audio track…");
        Audio.Decoded decoded = Audio.decode(ctx, src);
        short[] pcm = decoded.mono;

        listener.onProgress("preparing", 8, "Finding the spoken parts…");
        List<int[]> segs = DubbingEngine.findSegments(pcm, Audio.MIX_RATE);
        if (segs.isEmpty()) throw new IOException("No speech found in this video.");

        // ---- 1. offline speech to text ----
        listener.onProgress("models", 12, "Preparing the speech model…");
        File modelDir = ensureVoskModel(ctx, sourceIndex);
        Model model = new Model(modelDir.getAbsolutePath());
        List<String> texts = new ArrayList<>();
        try {
            for (int i = 0; i < segs.size(); i++) {
                listener.onProgress("transcribing", 15 + (int) (35.0 * i / segs.size()),
                        "Listening to part " + (i + 1) + " of " + segs.size() + "…");
                texts.add(transcribe(model, pcm, segs.get(i)));
            }
        } finally {
            model.close();
        }

        double totalSec = pcm.length / (double) Audio.MIX_RATE;
        List<DubbingEngine.Result> results = new ArrayList<>();
        File outDir = new File(ctx.getExternalFilesDir(null), "out");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("cannot create output folder");

        for (int li = 0; li < targets.size(); li++) {
            String target = targets.get(li);
            int base = 52 + (int) (46.0 * li / targets.size());
            int span = (int) (46.0 / targets.size());

            listener.onProgress("translating", base, "Translating into "
                    + DubbingEngine.langName(target) + "…");
            Translator translator = buildTranslator(ctx, sourceCode, target);
            List<String> translated = new ArrayList<>();
            try {
                for (String t : texts) {
                    translated.add(t.trim().isEmpty() ? "" : Tasks.await(translator.translate(t)));
                }
            } finally {
                translator.close();
            }

            listener.onProgress("speaking", base + span / 3, "Speaking with the phone's voice…");
            short[] mix = new short[(int) ((totalSec + 2.0) * Audio.MIX_RATE)];
            speakInto(ctx, translated, segs, target, mix, base, span, listener);

            if (background > 0.01) {
                short[] bed = decoded.bed;
                double gain = background;
                if (!DubbingEngine.hasBackground(bed)) {
                    bed = decoded.mono;
                    gain = background * 0.45;
                }
                for (int k = 0; k < bed.length && k < mix.length; k++) {
                    int v = mix[k] + (int) Math.round(bed[k] * gain);
                    mix[k] = (short) Math.max(-32768, Math.min(32767, v));
                }
            }

            listener.onProgress("finishing", base + span - 4, "Writing the video…");
            String fileName = "dubstudio_offline_" + target.replace("-", "") + "_"
                    + System.currentTimeMillis() + ".mp4";
            File outFile = new File(outDir, fileName);
            DubbingEngine.encodeAndMux(ctx, src, mix, outFile.getAbsolutePath());

            DubbingEngine.Result res = new DubbingEngine.Result();
            res.lang = target;
            res.name = fileName;
            res.displayName = DubbingEngine.publish(ctx, outFile, fileName);
            res.transcript = String.join(" ", texts);
            res.translated = String.join(" ", translated);
            results.add(res);
        }

        listener.onProgress("done", 100, "Finished.");
        return results;
    }

    // ------------------------------------------------------------------
    // speech
    // ------------------------------------------------------------------

    private static String transcribe(Model model, short[] pcm, int[] seg) throws IOException {
        Recognizer rec = new Recognizer(model, 16000.0f);
        try {
            short[] part = new short[seg[1] - seg[0]];
            System.arraycopy(pcm, seg[0], part, 0, part.length);
            short[] at16k = Audio.resample(part, Audio.MIX_RATE, 16000);
            byte[] bytes = new byte[at16k.length * 2];
            for (int i = 0; i < at16k.length; i++) {
                bytes[2 * i] = (byte) (at16k[i] & 0xFF);
                bytes[2 * i + 1] = (byte) ((at16k[i] >> 8) & 0xFF);
            }
            if (!rec.acceptWaveForm(bytes, bytes.length)) return "";
            String json = rec.getFinalResult();
            int at = json.indexOf("\"text\"");
            if (at < 0) return "";
            int start = json.indexOf('"', at + 6) + 1;
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end).trim() : "";
        } finally {
            rec.close();
        }
    }

    /** Downloads and unzips the Vosk model on first use. */
    private static File ensureVoskModel(Context ctx, int index) throws IOException {
        String name = VOSK_MODELS[index];
        File dir = new File(ctx.getFilesDir(), "vosk/" + name);
        if (new File(dir, "conf").exists() || new File(dir, "am").exists()) return dir;

        File zip = new File(ctx.getCacheDir(), name + ".zip");
        if (!zip.exists()) {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://alphacephei.com/vosk/models/" + name + ".zip").openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(zip)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create model folder");

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File target = new File(dir, e.getName().substring(e.getName().indexOf('/') + 1));
                if (e.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
        zip.delete();
        return dir;
    }

    // ------------------------------------------------------------------
    // translate
    // ------------------------------------------------------------------

    private static Translator buildTranslator(Context ctx, String source, String target)
            throws Exception {
        String from = mlKitCode(source);
        String to = mlKitCode(target);
        if (from == null || to == null) {
            throw new IOException("ML Kit has no offline model for " + (from == null ? source : target));
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(from)
                .setTargetLanguage(to)
                .build();
        Translator translator = Translation.getClient(options);
        Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
        return translator;
    }

    private static String mlKitCode(String bcp47) {
        switch (bcp47) {
            case "hi-IN": return TranslateLanguage.HINDI;
            case "bn-IN": return TranslateLanguage.BENGALI;
            case "gu-IN": return TranslateLanguage.GUJARATI;
            case "kn-IN": return TranslateLanguage.KANNADA;
            case "ml-IN": return TranslateLanguage.MALAYALAM;
            case "mr-IN": return TranslateLanguage.MARATHI;
            case "ta-IN": return TranslateLanguage.TAMIL;
            case "te-IN": return TranslateLanguage.TELUGU;
            case "ur-IN": return TranslateLanguage.URDU;
            case "en-IN": return TranslateLanguage.ENGLISH;
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // voice — the phone's own TTS engine
    // ------------------------------------------------------------------

    private static void speakInto(Context ctx, List<String> lines, List<int[]> segs,
                                  String target, short[] mix, int base, int span,
                                  DubbingEngine.Listener listener) throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final TextToSpeech[] holder = new TextToSpeech[1];
        TextToSpeech tts = new TextToSpeech(ctx, status -> {
            ready.countDown();
        });
        holder[0] = tts;
        if (!ready.await(20, TimeUnit.SECONDS)) throw new IOException("Text-to-speech did not start");

        int langResult = tts.setLanguage(localeFor(target));
        if (langResult == TextToSpeech.LANG_MISSING_DATA
                || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.shutdown();
            throw new IOException("The phone has no offline voice for "
                    + DubbingEngine.langName(target)
                    + ". Install it in Settings → System → Languages → Text-to-speech output.");
        }

        try {
            for (int i = 0; i < segs.size(); i++) {
                String text = lines.get(i);
                if (text.trim().isEmpty()) continue;
                listener.onProgress("speaking", base + span / 3
                                + (int) (span * 0.5 * i / Math.max(1, segs.size())),
                        "Speaking part " + (i + 1) + " of " + segs.size() + "…");

                File wav = new File(ctx.getCacheDir(), "tts_" + i + ".wav");
                wav.delete();
                final CountDownLatch done = new CountDownLatch(1);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onDone(String id) { done.countDown(); }
                    public void onError(String id) { done.countDown(); }
                });
                tts.synthesizeToFile(text, null, wav, "u" + i);
                if (!done.await(60, TimeUnit.SECONDS)) continue;
                if (!wav.exists()) continue;

                byte[] raw = new byte[(int) wav.length()];
                try (FileInputStream in = new FileInputStream(wav)) {
                    int off = 0, n;
                    while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) off += n;
                }
                Audio.Pcm clip = Audio.readWav(raw);
                short[] clipPcm = Audio.resample(clip.data, clip.rate, Audio.MIX_RATE);

                int startSample = segs.get(i)[0];
                int slot = (i + 1 < segs.size() ? segs.get(i + 1)[0] : mix.length) - startSample;
                if (slot < Audio.MIX_RATE / 2) slot = Audio.MIX_RATE / 2;
                if (clipPcm.length > slot) {
                    double ratio = Math.min(clipPcm.length / (double) slot, 2.0);
                    clipPcm = Audio.resample(clipPcm,
                            (int) Math.round(Audio.MIX_RATE * ratio), Audio.MIX_RATE);
                }
                for (int k = 0; k < clipPcm.length && startSample + k < mix.length; k++) {
                    int v = mix[startSample + k] + clipPcm[k];
                    mix[startSample + k] = (short) Math.max(-32768, Math.min(32767, v));
                }
                wav.delete();
            }
        } finally {
            tts.shutdown();
        }
    }

    private static Locale localeFor(String bcp47) {
        String[] parts = bcp47.split("-");
        return new Locale(parts[0], parts.length > 1 ? parts[1] : "");
    }
}
