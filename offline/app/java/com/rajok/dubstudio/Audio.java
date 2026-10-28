package com.rajok.dubstudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Audio decoding, resampling and WAV in/out. */
public final class Audio {

    public static final int MIX_RATE = 22050;

    private Audio() {}

    /** Growable short array. */
    public static final class ShortList {
        short[] a;
        int n;

        public ShortList(int cap) { a = new short[Math.max(16, cap)]; }

        public void add(short v) {
            if (n == a.length) {
                short[] b = new short[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = v;
        }

        public short[] toArray() {
            short[] r = new short[n];
            System.arraycopy(a, 0, r, 0, n);
            return r;
        }

        public int size() { return n; }
    }

    /** Streaming linear-interpolation resampler (source rate -> target rate). */
    public static final class Resampler {
        private final double step;
        private double pos = 0;
        private short carry = 0;
        private boolean hasCarry = false;

        public Resampler(int from, int to) { step = (double) from / (double) to; }

        public void feed(short[] buf, int len, ShortList out) {
            if (len <= 0) return;
            short[] src;
            int n;
            if (hasCarry) {
                src = new short[len + 1];
                src[0] = carry;
                System.arraycopy(buf, 0, src, 1, len);
                n = len + 1;
            } else {
                src = buf;
                n = len;
            }
            while (pos + 1 < n) {
                int k = (int) pos;
                double f = pos - k;
                double v = src[k] + (src[k + 1] - src[k]) * f;
                out.add((short) Math.max(-32768, Math.min(32767, Math.round(v))));
                pos += step;
            }
            carry = src[n - 1];
            hasCarry = true;
            pos -= (n - 1);
            if (pos < 0) pos = 0;
        }
    }

    public static short[] resample(short[] in, int from, int to) {
        if (from == to) return in;
        Resampler rs = new Resampler(from, to);
        ShortList out = new ShortList(in.length * to / Math.max(1, from) + 64);
        rs.feed(in, in.length, out);
        return out.toArray();
    }

    /** Mono mix (for transcription and timing) plus the background bed. */
    public static final class Decoded {
        public short[] mono;
        /** Mid/side residual: (L-R)/2, which cancels a centred voice and keeps music. */
        public short[] bed;
        public boolean stereo;
    }

    /** Decode any audio track in the file to mono PCM at MIX_RATE. */
    public static short[] decodeMono(Context ctx, Uri uri) throws IOException {
        return decode(ctx, uri).mono;
    }

    public static Decoded decode(Context ctx, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) throw new IOException("This file has no audio track.");

            ex.selectTrack(track);
            MediaFormat fmt = ex.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            MediaCodec dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            ShortList out = new ShortList(1 << 18);
            ShortList bed = new ShortList(1 << 18);
            Resampler rs = new Resampler(srcRate, MIX_RATE);
            Resampler rsBed = new Resampler(srcRate, MIX_RATE);
            short[] tmpBed = new short[1 << 14];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            short[] tmp = new short[1 << 14];

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(inIdx);
                        if (ib == null) throw new IOException("decoder input unavailable");
                        ib.clear();
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    rs = new Resampler(srcRate, MIX_RATE);
                    rsBed = new Resampler(srcRate, MIX_RATE);
                } else if (outIdx >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        int frames;
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            frames = info.size / (4 * channels);
                            if (tmp.length < frames) tmp = new short[frames];
                            java.nio.FloatBuffer fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            fb.position(info.offset / 4);
                            if (tmpBed.length < frames) tmpBed = new short[frames];
                            for (int i = 0; i < frames; i++) {
                                float l = fb.get();
                                float r = channels > 1 ? fb.get() : l;
                                float sum = l + r;
                                for (int c = 2; c < channels; c++) sum += fb.get();
                                float v = sum / channels;
                                tmp[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
                                float side = channels > 1 ? (l - r) / 2f : 0f;
                                tmpBed[i] = (short) Math.max(-32768, Math.min(32767,
                                        Math.round(side * 32767f)));
                            }
                        } else {
                            frames = info.size / (2 * channels);
                            if (tmp.length < frames) tmp = new short[frames];
                            java.nio.ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                            sb.position(info.offset / 2);
                            if (tmpBed.length < frames) tmpBed = new short[frames];
                            for (int i = 0; i < frames; i++) {
                                int l = sb.get();
                                int r = channels > 1 ? sb.get() : l;
                                int sum = l + r;
                                for (int c = 2; c < channels; c++) sum += sb.get();
                                tmp[i] = (short) (sum / channels);
                                tmpBed[i] = (short) (channels > 1 ? (l - r) / 2 : 0);
                            }
                        }
                        rs.feed(tmp, frames, out);
                        rsBed.feed(tmpBed, frames, bed);
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }

            dec.stop();
            dec.release();
            Decoded d = new Decoded();
            d.mono = out.toArray();
            d.bed = bed.toArray();
            d.stereo = channels > 1;
            return d;
        } finally {
            ex.release();
        }
    }

    /** 16-bit PCM mono WAV file bytes. */
    public static byte[] toWav(short[] pcm, int rate) {
        int dataLen = pcm.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) 1);
        bb.putInt(rate);
        bb.putInt(rate * 2);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put("data".getBytes());
        bb.putInt(dataLen);
        for (short s : pcm) bb.putShort(s);
        return bb.array();
    }

    /** Parsed PCM from a WAV file. */
    public static final class Pcm {
        public short[] data;
        public int rate;
    }

    /** Wraps raw little-endian 16-bit mono PCM (what ElevenLabs and Gemini return). */
    public static Pcm fromRawPcm(byte[] data, int rate) {
        int n = data.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((data[2 * i] & 0xFF) | (data[2 * i + 1] << 8));
        }
        Pcm p = new Pcm();
        p.data = out;
        p.rate = rate;
        return p;
    }

    public static Pcm readWav(byte[] raw) throws IOException {
        if (raw.length < 12) throw new IOException("audio response too short");
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.get() != 'R' || bb.get() != 'I' || bb.get() != 'F' || bb.get() != 'F') {
            throw new IOException("not a WAV response");
        }
        bb.getInt();
        bb.get(); bb.get(); bb.get(); bb.get(); // WAVE
        int rate = 22050, channels = 1, bits = 16;
        int pos = 12;
        while (pos + 8 <= raw.length) {
            bb.position(pos);
            int id = bb.getInt();
            int size = bb.getInt();
            int body = pos + 8;
            if (size < 0 || body + size > raw.length) size = raw.length - body;
            if (id == 0x20746d66) { // "fmt "
                bb.position(body);
                bb.getShort(); // format
                channels = bb.getShort();
                rate = bb.getInt();
                bb.getInt();
                bb.getShort();
                bits = bb.getShort();
            } else if (id == 0x61746164) { // "data"
                int bytesPerSample = Math.max(1, bits / 8);
                int frames = size / (bytesPerSample * Math.max(1, channels));
                short[] mono = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        int off = body + (i * channels + c) * bytesPerSample;
                        int v;
                        if (bytesPerSample == 2) {
                            v = (short) ((raw[off] & 0xFF) | (raw[off + 1] << 8));
                        } else if (bytesPerSample == 1) {
                            v = (short) ((raw[off] & 0xFF) - 128) << 8;
                        } else {
                            int b0 = raw[off] & 0xFF, b1 = raw[off + 1] & 0xFF,
                                    b2 = raw[off + 2] & 0xFF, b3 = raw[off + 3] & 0xFF;
                            int iv = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
                            v = (short) (iv >> 16);
                        }
                        sum += v;
                    }
                    mono[i] = (short) (sum / Math.max(1, channels));
                }
                Pcm pcm = new Pcm();
                pcm.data = mono;
                pcm.rate = rate;
                return pcm;
            }
            pos = body + size + (size % 2);
        }
        throw new IOException("no audio data in WAV response");
    }

    public static short[] concat(ByteArrayOutputStream ignored, short[] a) { return a; }
}
