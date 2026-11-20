package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.content.SharedPreferences;

public final class AiSettingsStore {
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final String PREFERENCES_NAME = "markor_ai_assistant_prefs";
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_MODEL = "gemini_model";

    private final SharedPreferences preferences;

    public AiSettingsStore(final Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        return preferences.getString(KEY_API_KEY, "");
    }

    public String getModel() {
        return preferences.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public void save(final String apiKey, final String model) {
        preferences.edit()
                .putString(KEY_API_KEY, apiKey.trim())
                .putString(KEY_MODEL, model.trim())
                .apply();
    }
}
