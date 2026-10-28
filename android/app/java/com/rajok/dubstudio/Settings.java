package com.rajok.dubstudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** All the keys and engine choices, persisted in one place. */
public final class Settings {

    public static final String AUTO = "auto";
    public static final String SARVAM = "sarvam";
    public static final String OPENAI = "openai";
    public static final String ELEVEN = "elevenlabs";
    public static final String GEMINI = "gemini";
    public static final String CUSTOM = "custom";

    public String sarvam = "";
    public String openai = "";
    public String eleven = "";
    public String gemini = "";
    public String customKey = "";
    public String customBase = "";
    public String customModel = "";
    public String ttsEngine = AUTO;
    public String sttEngine = AUTO;

    public static Settings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("dubstudio", Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.sarvam = p.getString("sarvam_key", "");
        s.openai = p.getString("openai_key", "");
        s.eleven = p.getString("eleven_key", "");
        s.gemini = p.getString("gemini_key", "");
        s.customKey = p.getString("custom_key", "");
        s.customBase = p.getString("custom_base", "");
        s.customModel = p.getString("custom_model", "");
        s.ttsEngine = p.getString("tts_engine", AUTO);
        s.sttEngine = p.getString("stt_engine", AUTO);
        // v1.3 stored a single key; keep it if the new field is empty
        if (s.sarvam.isEmpty()) s.sarvam = p.getString("api_key", "");
        return s;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences("dubstudio", Context.MODE_PRIVATE).edit()
                .putString("sarvam_key", sarvam)
                .putString("openai_key", openai)
                .putString("eleven_key", eleven)
                .putString("gemini_key", gemini)
                .putString("custom_key", customKey)
                .putString("custom_base", customBase)
                .putString("custom_model", customModel)
                .putString("tts_engine", ttsEngine)
                .putString("stt_engine", sttEngine)
                .apply();
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("sarvam", sarvam);
            o.put("openai", openai);
            o.put("eleven", eleven);
            o.put("gemini", gemini);
            o.put("customKey", customKey);
            o.put("customBase", customBase);
            o.put("customModel", customModel);
            o.put("ttsEngine", ttsEngine);
            o.put("sttEngine", sttEngine);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static Settings fromJson(String json) {
        Settings s = new Settings();
        try {
            JSONObject o = new JSONObject(json);
            s.sarvam = o.optString("sarvam", "");
            s.openai = o.optString("openai", "");
            s.eleven = o.optString("eleven", "");
            s.gemini = o.optString("gemini", "");
            s.customKey = o.optString("customKey", "");
            s.customBase = o.optString("customBase", "");
            s.customModel = o.optString("customModel", "");
            s.ttsEngine = o.optString("ttsEngine", AUTO);
            s.sttEngine = o.optString("sttEngine", AUTO);
        } catch (Exception ignored) {
        }
        return s;
    }

    public boolean has(String engine) {
        switch (engine) {
            case SARVAM: return !sarvam.trim().isEmpty();
            case OPENAI: return !openai.trim().isEmpty();
            case ELEVEN: return !eleven.trim().isEmpty();
            case GEMINI: return !gemini.trim().isEmpty();
            case CUSTOM: return !customBase.trim().isEmpty();
            default: return false;
        }
    }

    /** Which provider will actually do the voice, after resolving "auto". */
    public String ttsProvider() {
        if (!AUTO.equals(ttsEngine) && has(ttsEngine)) return ttsEngine;
        for (String e : new String[]{SARVAM, OPENAI, ELEVEN, GEMINI, CUSTOM}) {
            if (has(e)) return e;
        }
        return "";
    }

    /** Which provider will transcribe and translate. */
    public String sttProvider() {
        if (!AUTO.equals(sttEngine) && has(sttEngine)) return sttEngine;
        for (String e : new String[]{SARVAM, OPENAI, GEMINI}) {
            if (has(e)) return e;
        }
        return "";
    }
}
