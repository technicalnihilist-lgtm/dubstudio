package com.rajok.dubstudio;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Thin client for the Sarvam AI REST API. */
public final class Sarvam {

    private static final String BASE = "https://api.sarvam.ai";

    private Sarvam() {}

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String postJson(String path, String key, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("api-subscription-key", key);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        c.disconnect();
        if (code != 200) throw new IOException("Sarvam " + code + ": " + trim(text));
        return text;
    }

    private static String trim(String s) {
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    /** Returns {transcript, languageCode}. */
    public static String[] transcribe(byte[] wav, String key, String languageCode) throws IOException {
        String boundary = "----dub" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/speech-to-text").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("api-subscription-key", key);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "model", "saaras:v3");
        writeField(body, boundary, "mode", "transcribe");
        writeField(body, boundary, "language_code", languageCode);
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        byte[] payload = body.toByteArray();
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.close();

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        c.disconnect();
        if (code != 200) throw new IOException("Sarvam STT " + code + ": " + trim(text));

        try {
            JSONObject j = new JSONObject(text);
            String transcript = j.optString("transcript", "");
            String lang = j.optString("language_code", "");
            if (lang == null || lang.equals("null")) lang = "";
            return new String[]{transcript, lang};
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from Sarvam: " + trim(text));
        }
    }

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    public static String translate(String text, String source, String target,
                                   String key, String tone) throws IOException {
        if (text == null || text.trim().isEmpty() || source.equals(target)) return text;
        boolean mayura = source.startsWith("hi") || source.startsWith("bn") || source.startsWith("gu")
                || source.startsWith("kn") || source.startsWith("ml") || source.startsWith("mr")
                || source.startsWith("od") || source.startsWith("pa") || source.startsWith("ta")
                || source.startsWith("te") || source.startsWith("en");
        try {
            JSONObject body = new JSONObject();
            body.put("input", text);
            body.put("source_language_code", source.isEmpty() ? "auto" : source);
            body.put("target_language_code", target);
            body.put("model", mayura ? "mayura:v1" : "sarvam-translate:v1");
            body.put("numerals_format", "international");
            if (mayura) body.put("mode", tone);
            JSONObject j = new JSONObject(postJson("/translate", key, body));
            return j.optString("translated_text", text);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Returns the WAV bytes for the spoken text. */
    public static byte[] speak(String text, String target, String key, String speaker)
            throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("text", text);
            body.put("language_code", target);
            body.put("speaker", speaker);
            body.put("model", "bulbul:v3");
            body.put("speech_sample_rate", 22050);
            body.put("output_audio_codec", "wav");
            body.put("pace", 1.0);
            JSONObject j = new JSONObject(postJson("/text-to-speech", key, body));
            JSONArray audios = j.getJSONArray("audios");
            if (audios.length() == 0) throw new IOException("no audio returned");
            return Base64.decode(audios.getString(0), Base64.DEFAULT);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }
}
