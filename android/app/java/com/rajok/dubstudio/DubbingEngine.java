package com.rajok.dubstudio;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** The whole dubbing pipeline, on the phone. */
public final class DubbingEngine {

    public interface Listener {
        void onProgress(String stage, int pct, String msg);
    }

    public static final class Result {
        public String lang;
        public String name;
        public String displayName;
        public String transcript;
        public String translated;
    }

    public static final class Lang {
        public final String code;
        public final String name;
        public Lang(String code, String name) { this.code = code; this.name = name; }
    }

    public static final Lang[] LANGS = {
            new Lang("hi-IN", "Hindi"),
            new Lang("bn-IN", "Bengali"),
            new Lang("gu-IN", "Gujarati"),
            new Lang("kn-IN", "Kannada"),
            new Lang("ml-IN", "Malayalam"),
            new Lang("mr-IN", "Marathi"),
            new Lang("od-IN", "Odia"),
            new Lang("pa-IN", "Punjabi"),
            new Lang("ta-IN", "Tamil"),
            new Lang("te-IN", "Telugu"),
            new Lang("en-IN", "English (India)"),
    };

    public static final String[] VOICE_IDS = {
            "shubh", "aditya", "rahul", "amit", "dev", "rohan",
            "ritu", "priya", "neha", "ishita", "simran", "kavya"
    };

    public static final String[] VOICE_LABELS = {
            "Shubh — warm male", "Aditya — male", "Rahul — male", "Amit — male",
            "Dev — male", "Rohan — male", "Ritu — female", "Priya — female",
            "Neha — female", "Ishita — female", "Simran — female", "Kavya — female"
    };

    public static String langName(String code) {
        for (Lang l : LANGS) if (l.code.equals(code)) return l.name;
        return code;
    }

    private DubbingEngine() {}

    // ------------------------------------------------------------------
    // pipeline
    // ------------------------------------------------------------------

    public static List<Result> run(Context ctx, Uri src, List<String> targets,
                                   String speaker, String tone, double originalVolume,
                                   String apiKey, Listener listener) throws IOException {

        listener.onProgress("preparing", 3, "Reading the audio track…");
        short[] pcm = Audio.decodeMono(ctx, src);
        if (pcm.length < Audio.MIX_RATE / 2) {
            throw new IOException("That file has almost no audio in it.");
        }
        double totalSec = pcm.length / (double) Audio.MIX_RATE;

        listener.onProgress("preparing", 10, "Finding the spoken parts…");
        List<int[]> segs = findSegments(pcm, Audio.MIX_RATE);
        if (segs.isEmpty()) throw new IOException("No speech found — is the audio silent?");

        // ---- transcribe once ----
        String[] texts = new String[segs.size()];
        String detected = "";
        for (int i = 0; i < segs.size(); i++) {
            listener.onProgress("transcribing", 10 + (int) (35.0 * i / segs.size()),
                    "Listening to part " + (i + 1) + " of " + segs.size() + "…");
            int s = segs.get(i)[0], e = segs.get(i)[1];
            short[] part = new short[e - s];
            System.arraycopy(pcm, s, part, 0, e - s);
            byte[] wav = Audio.toWav(Audio.resample(part, Audio.MIX_RATE, 16000), 16000);
            String[] r = Sarvam.transcribe(wav, apiKey, detected.isEmpty() ? "unknown" : detected);
            texts[i] = r[0] == null ? "" : r[0].trim();
            if (detected.isEmpty() && r[1] != null && !r[1].isEmpty()) detected = r[1];
        }
        if (detected.isEmpty()) detected = "hi-IN";
        listener.onProgress("transcribing", 46, "Language detected: " + langName(detected));

        String fullTranscript = join(texts, " ");
        List<Result> results = new ArrayList<>();
        File outDir = new File(ctx.getExternalFilesDir(null), "out");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("cannot create output folder");

        for (int li = 0; li < targets.size(); li++) {
            String target = targets.get(li);
            String tName = langName(target);
            int base = 46 + (int) (54.0 * li / targets.size());
            int span = (int) (54.0 / targets.size());

            listener.onProgress("translating", base + 2, "Translating into " + tName + "…");
            String[] translated = new String[texts.length];
            for (int i = 0; i < texts.length; i++) {
                translated[i] = texts[i].isEmpty() ? ""
                        : Sarvam.translate(texts[i], detected, target, apiKey, tone);
            }

            listener.onProgress("speaking", base + span / 3, "Recording the " + tName + " voice…");
            short[] mix = new short[(int) ((totalSec + 2.0) * Audio.MIX_RATE)];
            for (int i = 0; i < segs.size(); i++) {
                if (translated[i].isEmpty()) continue;
                listener.onProgress("speaking", base + span / 3
                                + (int) (span * 0.5 * i / Math.max(1, segs.size())),
                        tName + ": voicing part " + (i + 1) + " of " + segs.size() + "…");

                byte[] wav = Sarvam.speak(translated[i], target, apiKey, speaker);
                Audio.Pcm clip = Audio.readWav(wav);
                short[] clipPcm = Audio.resample(clip.data, clip.rate, Audio.MIX_RATE);

                int startSample = segs.get(i)[0];
                int slot = (i + 1 < segs.size() ? segs.get(i + 1)[0] : mix.length) - startSample;
                if (slot < Audio.MIX_RATE / 2) slot = Audio.MIX_RATE / 2;
                if (clipPcm.length > slot) {
                    double ratio = clipPcm.length / (double) slot;
                    if (ratio > 2.0) ratio = 2.0;
                    int newRate = (int) Math.round(Audio.MIX_RATE * ratio);
                    clipPcm = Audio.resample(clipPcm, newRate, Audio.MIX_RATE);
                }
                int at = startSample;
                for (int k = 0; k < clipPcm.length && at + k < mix.length; k++) {
                    int v = mix[at + k] + clipPcm[k];
                    mix[at + k] = (short) Math.max(-32768, Math.min(32767, v));
                }
            }

            if (originalVolume > 0.01) {
                for (int k = 0; k < pcm.length && k < mix.length; k++) {
                    int v = mix[k] + (int) Math.round(pcm[k] * originalVolume);
                    mix[k] = (short) Math.max(-32768, Math.min(32767, v));
                }
            }

            listener.onProgress("finishing", base + span - 4, "Writing the " + tName + " video…");
            String fileName = "dubstudio_" + target.replace("-", "") + "_"
                    + System.currentTimeMillis() + ".mp4";
            File outFile = new File(outDir, fileName);
            encodeAndMux(ctx, src, mix, outFile.getAbsolutePath());

            String display = publish(ctx, outFile, fileName);

            Result res = new Result();
            res.lang = target;
            res.name = fileName;
            res.displayName = display;
            res.transcript = fullTranscript;
            res.translated = join(translated, " ");
            results.add(res);
        }

        listener.onProgress("done", 100, "Finished.");
        return results;
    }

    private static String join(String[] parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.trim());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // voice activity: turn the waveform into speakable chunks
    // ------------------------------------------------------------------

    private static List<int[]> findSegments(short[] pcm, int rate) {
        int win = Math.max(160, rate / 50);          // 20 ms
        int nWin = pcm.length / win;
        List<int[]> segs = new ArrayList<>();
        if (nWin < 2) return segs;

        double[] rms = new double[nWin];
        double mean = 0;
        for (int w = 0; w < nWin; w++) {
            long s = 0;
            int base = w * win;
            for (int i = 0; i < win; i++) {
                int v = pcm[base + i];
                s += (long) v * v;
            }
            rms[w] = Math.sqrt((double) s / win);
            mean += rms[w];
        }
        mean /= nWin;
        double thr = Math.max(220.0, mean * 0.45);

        int maxGapWin = (int) (0.35 * 50);
        int maxLenWin = (int) (26.0 * 50);
        int minLenWin = (int) (0.40 * 50);

        int i = 0;
        while (i < nWin) {
            if (rms[i] <= thr) { i++; continue; }
            int start = i, end = i, j = i;
            while (j < nWin) {
                if (rms[j] > thr) { end = j; j++; continue; }
                int k = j;
                while (k < nWin && rms[k] <= thr) k++;
                if (k < nWin && (k - end) <= maxGapWin && (k - start) <= maxLenWin) {
                    j = k;
                } else {
                    break;
                }
            }
            int s0 = start;
            while (end - s0 > maxLenWin) {
                segs.add(new int[]{s0 * win, (s0 + maxLenWin) * win});
                s0 += maxLenWin;
            }
            if (end - s0 >= minLenWin) segs.add(new int[]{s0 * win, (end + 1) * win});
            i = end + 1;
        }
        return segs;
    }

    // ------------------------------------------------------------------
    // encode the new audio and mux it onto the untouched video
    // ------------------------------------------------------------------

    private static void encodeAndMux(Context ctx, Uri src, short[] mix, String outPath)
            throws IOException {
        MediaExtractor vex = new MediaExtractor();
        int vTrack = -1;
        MediaFormat vFormat = null;
        int rotation = 0;
        try {
            vex.setDataSource(ctx, src, null);
            for (int i = 0; i < vex.getTrackCount(); i++) {
                MediaFormat f = vex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    vTrack = i;
                    vFormat = f;
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = f.getInteger(MediaFormat.KEY_ROTATION);
                    }
                    break;
                }
            }

            MediaFormat encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm",
                    Audio.MIX_RATE, 1);
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            MediaMuxer mux = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) mux.setOrientationHint(rotation);

            int audioTrack = -1, videoOut = -1;
            boolean started = false;
            int offset = 0;
            boolean inputDone = false, outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = enc.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(inIdx);
                        if (ib == null) throw new IOException("encoder input unavailable");
                        ib.clear();
                        if (offset >= mix.length) {
                            enc.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int count = Math.min(2048, mix.length - offset);
                            for (int i = 0; i < count; i++) ib.putShort(mix[offset + i]);
                            long pts = (long) ((double) offset * 1000000.0 / Audio.MIX_RATE);
                            enc.queueInputBuffer(inIdx, 0, count * 2, pts, 0);
                            offset += count;
                        }
                    }
                }

                int outIdx = enc.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioTrack = mux.addTrack(enc.getOutputFormat());
                    if (vFormat != null) videoOut = mux.addTrack(vFormat);
                    mux.start();
                    started = true;
                } else if (outIdx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0 && started
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(audioTrack, ob, info);
                    }
                    enc.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }

            // copy the video stream across untouched
            if (vFormat != null && videoOut >= 0) {
                vex.selectTrack(vTrack);
                ByteBuffer buf = ByteBuffer.allocateDirect(8 * 1024 * 1024);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                while (true) {
                    int size = vex.readSampleData(buf, 0);
                    if (size < 0) break;
                    bi.offset = 0;
                    bi.size = size;
                    bi.presentationTimeUs = vex.getSampleTime();
                    bi.flags = (vex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mux.writeSampleData(videoOut, buf, bi);
                    vex.advance();
                }
            }

            mux.stop();
            mux.release();
            enc.stop();
            enc.release();
        } finally {
            vex.release();
        }
    }

    // ------------------------------------------------------------------
    // make the result visible in the gallery
    // ------------------------------------------------------------------

    private static String publish(Context ctx, File file, String fileName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/DubStudio");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    InputStream in = new FileInputStream(file);
                    OutputStream out = ctx.getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.close();
                    }
                    in.close();
                    v.clear();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    ctx.getContentResolver().update(uri, v, null, null);
                    return "Movies/DubStudio/" + fileName;
                }
            } catch (Exception ignored) {
                // fall through to the app folder
            }
        }
        return file.getAbsolutePath();
    }
}
