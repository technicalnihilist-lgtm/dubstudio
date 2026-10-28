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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One place that knows how to talk to every voice provider.
 *
 * Speech-to-text and translation: Sarvam, OpenAI, Gemini.
 * Voice: Sarvam, OpenAI, ElevenLabs, Gemini, plus any OpenAI-compatible endpoint.
 */
public final class Providers {

    private static final int CONNECT = 20000;
    private static final int READ = 180000;

    private Providers() {}

    public static final Map<String, String> ENGINE_NAMES = new LinkedHashMap<>();
    static {
        ENGINE_NAMES.put(Settings.SARVAM, "Sarvam AI");
        ENGINE_NAMES.put(Settings.OPENAI, "OpenAI");
        ENGINE_NAMES.put(Settings.ELEVEN, "ElevenLabs");
        ENGINE_NAMES.put(Settings.GEMINI, "Google Gemini");
        ENGINE_NAMES.put(Settings.CUSTOM, "Custom (OpenAI-compatible)");
    }

    public static final Map<String, String[]> VOICES = new LinkedHashMap<>();
    static {
        VOICES.put(Settings.SARVAM, new String[]{
                "shubh", "aditya", "rahul", "amit", "dev", "rohan",
                "ritu", "priya", "neha", "ishita", "simran", "kavya"});
        VOICES.put(Settings.OPENAI, new String[]{
                "alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral", "sage"});
        VOICES.put(Settings.ELEVEN, new String[]{
                "21m00Tcm4TlvDq8ikWAM", "pNInz6obpgDQGcFmaJgB", "EXAVITQu4vr4xnSDxMaL",
                "AZnzlxzA4RlHGv3EHr6N", "TxGE6cUeAdwR8u5gWzR5", "yoZ06aMxZJJ28mfd3POQ"});
        VOICES.put(Settings.GEMINI, new String[]{
                "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr"});
        VOICES.put(Settings.CUSTOM, new String[]{"default"});
    }

    public static final Map<String, String[]> VOICE_LABELS = new LinkedHashMap<>();
    static {
        VOICE_LABELS.put(Settings.SARVAM, new String[]{
                "Shubh — male", "Aditya — male", "Rahul — male", "Amit — male",
                "Dev — male", "Rohan — male", "Ritu — female", "Priya — female",
                "Neha — female", "Ishita — female", "Simran — female", "Kavya — female"});
        VOICE_LABELS.put(Settings.OPENAI, new String[]{
                "Alloy", "Echo", "Fable", "Onyx", "Nova", "Shimmer", "Coral", "Sage"});
        VOICE_LABELS.put(Settings.ELEVEN, new String[]{
                "Rachel", "Adam", "Bella", "Domi", "Josh", "Sam"});
        VOICE_LABELS.put(Settings.GEMINI, new String[]{
                "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr"});
        VOICE_LABELS.put(Settings.CUSTOM, new String[]{"Server default"});
    }

    /** Only Sarvam exposes instant voice cloning today. */
    public static boolean supportsCloning(String engine) {
        return Settings.SARVAM.equals(engine);
    }

    // ------------------------------------------------------------------
    // speech to text
    // ------------------------------------------------------------------

    /** Returns {transcript, detectedLanguageCode}. */
    public static String[] transcribe(byte[] wav, Settings s) throws IOException {
        String engine = s.sttProvider();
        switch (engine) {
            case Settings.SARVAM: return Sarvam.transcribe(wav, s.sarvam, "unknown");
            case Settings.OPENAI: return openaiTranscribe(wav, s.openai);
            case Settings.GEMINI: return geminiTranscribe(wav, s.gemini);
            default: throw new IOException(
                    "No key that can transcribe speech. Add a Sarvam, OpenAI or Gemini key.");
        }
    }

    private static String[] openaiTranscribe(byte[] wav, String key) throws IOException {
        String boundary = "----dub" + System.nanoTime();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + key);
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", "whisper-1");
        field(body, boundary, "response_format", "verbose_json");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"chunk.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        String text = post("https://api.openai.com/v1/audio/transcriptions", h, body.toByteArray());
        try {
            JSONObject j = new JSONObject(text);
            String lang = j.optString("language", "");
            return new String[]{j.optString("text", "").trim(), codeFor(lang)};
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from OpenAI: " + trim(text));
        }
    }

    private static String[] geminiTranscribe(byte[] wav, String key) throws IOException {
        try {
            JSONObject part = new JSONObject();
            JSONObject inline = new JSONObject();
            inline.put("mime_type", "audio/wav");
            inline.put("data", Base64.encodeToString(wav, Base64.NO_WRAP));
            part.put("inline_data", inline);
            JSONObject prompt = new JSONObject();
            prompt.put("text", "Transcribe this audio exactly as spoken, in its own script. "
                    + "Output only the transcript, nothing else.");
            JSONArray parts = new JSONArray().put(part).put(prompt);
            JSONObject content = new JSONObject().put("parts", parts);
            JSONObject body = new JSONObject().put("contents", new JSONArray().put(content));

            String text = post("https://generativelanguage.googleapis.com/v1beta/models/"
                    + "gemini-2.5-flash:generateContent?key=" + key, jsonHeaders(), body.toString().getBytes(StandardCharsets.UTF_8));
            return new String[]{geminiText(text).trim(), ""};
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from Gemini: " + e.getMessage());
        }
    }

    private static String geminiText(String raw) throws IOException {
        try {
            JSONObject j = new JSONObject(raw);
            JSONArray cands = j.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) throw new IOException("Gemini returned nothing");
            JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
            return sb.toString();
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from Gemini: " + trim(raw));
        }
    }

    // ------------------------------------------------------------------
    // translate
    // ------------------------------------------------------------------

    public static String translate(String text, String source, String target,
                                   String tone, Settings s) throws IOException {
        if (text == null || text.trim().isEmpty() || target.equals(source)) return text;
        String engine = s.sttProvider();
        switch (engine) {
            case Settings.SARVAM:
                return Sarvam.translate(text, source, target, s.sarvam, tone);
            case Settings.OPENAI:
                return openaiTranslate(text, target, tone, s.openai);
            case Settings.GEMINI:
                return geminiTranslate(text, target, tone, s.gemini);
            default:
                throw new IOException("No key that can translate. Add a Sarvam, OpenAI or Gemini key.");
        }
    }

    private static String styleHint(String tone) {
        if ("formal".equals(tone)) return "Use a formal, polished register.";
        if ("code-mixed".equals(tone)) return "Write the way people actually talk, mixing English words in.";
        if ("classic-colloquial".equals(tone)) return "Use an everyday spoken register.";
        return "Use natural, modern, conversational language.";
    }

    private static String openaiTranslate(String text, String target, String tone, String key)
            throws IOException {
        try {
            JSONObject sys = new JSONObject().put("role", "system").put("content",
                    "You translate subtitles for video dubbing. Translate the user's text into the "
                            + "language with BCP-47 code " + target + ". " + styleHint(tone)
                            + " Keep it natural to say out loud. Output only the translation.");
            JSONObject usr = new JSONObject().put("role", "user").put("content", text);
            JSONObject body = new JSONObject()
                    .put("model", "gpt-4o-mini")
                    .put("temperature", 0.3)
                    .put("messages", new JSONArray().put(sys).put(usr));
            String out = post("https://api.openai.com/v1/chat/completions",
                    bearerHeaders(key), body.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject j = new JSONObject(out);
            return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .optString("content", text).trim();
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from OpenAI: " + e.getMessage());
        }
    }

    private static String geminiTranslate(String text, String target, String tone, String key)
            throws IOException {
        try {
            String prompt = "Translate this into the language with BCP-47 code " + target + ". "
                    + styleHint(tone) + " Output only the translation.\n\n" + text;
            JSONObject part = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
            JSONObject body = new JSONObject().put("contents", new JSONArray().put(content));
            String out = post("https://generativelanguage.googleapis.com/v1beta/models/"
                            + "gemini-2.5-flash:generateContent?key=" + key,
                    jsonHeaders(), body.toString().getBytes(StandardCharsets.UTF_8));
            return geminiText(out).trim();
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from Gemini: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // voice
    // ------------------------------------------------------------------

    /** Returns decoded mono PCM for the spoken text. */
    public static Audio.Pcm speak(String text, String targetLang, String voice,
                                  String clonedVoiceId, Settings s) throws IOException {
        String engine = s.ttsProvider();
        switch (engine) {
            case Settings.SARVAM: {
                byte[] wav = clonedVoiceId != null
                        ? Sarvam.speakCloned(text, clonedVoiceId, s.sarvam, targetLang)
                        : Sarvam.speak(text, targetLang, s.sarvam, voice);
                return Audio.readWav(wav);
            }
            case Settings.OPENAI:
                return Audio.readWav(openaiSpeak(text, voice, s.openai));
            case Settings.ELEVEN:
                return Audio.fromRawPcm(elevenSpeak(text, voice, s.eleven), 44100);
            case Settings.GEMINI:
                return Audio.fromRawPcm(geminiSpeak(text, voice, s.gemini), 24000);
            case Settings.CUSTOM:
                return Audio.readWav(customSpeak(text, voice, s));
            default:
                throw new IOException("No voice provider. Paste at least one API key in Settings.");
        }
    }

    private static byte[] openaiSpeak(String text, String voice, String key) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("model", "tts-1")
                    .put("voice", voice == null || voice.isEmpty() ? "alloy" : voice)
                    .put("input", text)
                    .put("response_format", "wav");
            return postBinary("https://api.openai.com/v1/audio/speech",
                    bearerHeaders(key), body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static byte[] elevenSpeak(String text, String voice, String key) throws IOException {
        try {
            String id = voice == null || voice.isEmpty() ? "21m00Tcm4TlvDq8ikWAM" : voice;
            JSONObject body = new JSONObject()
                    .put("text", text)
                    .put("model_id", "eleven_multilingual_v2");
            Map<String, String> h = new LinkedHashMap<>();
            h.put("xi-api-key", key);
            h.put("Content-Type", "application/json");
            h.put("Accept", "audio/pcm");
            return postBinary("https://api.elevenlabs.io/v1/text-to-speech/" + id
                    + "?output_format=pcm_44100", h, body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static byte[] geminiSpeak(String text, String voice, String key) throws IOException {
        try {
            JSONObject part = new JSONObject().put("text", text);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
            JSONObject voiceCfg = new JSONObject().put("prebuiltVoiceConfig",
                    new JSONObject().put("voiceName",
                            voice == null || voice.isEmpty() ? "Kore" : voice));
            JSONObject genCfg = new JSONObject()
                    .put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("speechConfig", new JSONObject().put("voiceConfig", voiceCfg));
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", genCfg);

            String out = post("https://generativelanguage.googleapis.com/v1beta/models/"
                            + "gemini-2.5-flash-preview-tts:generateContent?key=" + key,
                    jsonHeaders(), body.toString().getBytes(StandardCharsets.UTF_8));

            JSONObject j = new JSONObject(out);
            JSONArray cands = j.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) throw new IOException("Gemini returned no audio");
            JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                if (inline == null) inline = parts.getJSONObject(i).optJSONObject("inline_data");
                if (inline != null && inline.has("data")) {
                    return Base64.decode(inline.getString("data"), Base64.DEFAULT);
                }
            }
            throw new IOException("Gemini returned no audio data");
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from Gemini: " + e.getMessage());
        }
    }

    private static byte[] customSpeak(String text, String voice, Settings s) throws IOException {
        try {
            String base = s.customBase.trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String model = s.customModel.trim().isEmpty() ? "tts-1" : s.customModel.trim();
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("voice", voice == null || voice.isEmpty() ? "default" : voice)
                    .put("input", text)
                    .put("response_format", "wav");
            Map<String, String> h = bearerHeaders(s.customKey.trim());
            return postBinary(base + "/audio/speech", h,
                    body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // http plumbing
    // ------------------------------------------------------------------

    private static Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        return h;
    }

    private static Map<String, String> bearerHeaders(String key) {
        Map<String, String> h = jsonHeaders();
        if (key != null && !key.trim().isEmpty()) h.put("Authorization", "Bearer " + key.trim());
        return h;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String post(String url, Map<String, String> headers, byte[] body)
            throws IOException {
        return new String(postBinary(url, headers, body), StandardCharsets.UTF_8);
    }

    private static byte[] postBinary(String url, Map<String, String> headers, byte[] body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT);
        c.setReadTimeout(READ);
        c.setDoOutput(true);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        c.disconnect();
        byte[] bytes = out.toByteArray();
        if (code != 200 && code != 201) {
            throw new IOException("HTTP " + code + ": "
                    + trim(new String(bytes, StandardCharsets.UTF_8)));
        }
        return bytes;
    }

    private static String trim(String s) {
        return s.length() > 220 ? s.substring(0, 220) : s;
    }

    /** Whisper reports language names, not codes. */
    private static String codeFor(String name) {
        if (name == null) return "";
        switch (name.toLowerCase()) {
            case "hindi": return "hi-IN";
            case "bengali": return "bn-IN";
            case "gujarati": return "gu-IN";
            case "kannada": return "kn-IN";
            case "malayalam": return "ml-IN";
            case "marathi": return "mr-IN";
            case "urdu": return "ur-IN";
            case "punjabi": return "pa-IN";
            case "tamil": return "ta-IN";
            case "telugu": return "te-IN";
            case "english": return "en-IN";
            case "nepali": return "ne-IN";
            default: return "";
        }
    }
}
