package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public final class AiSettingsStore {
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final String PREFERENCES_NAME = "markor_ai_assistant_prefs";
    private static final String KEY_PRIMARY_PROVIDER = "primary_provider";
    private static final String KEY_AUTO_FAILOVER = "auto_failover_enabled";

    private final SharedPreferences preferences;

    public AiSettingsStore(final Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        return getProviderApiKey(AiProviderRouter.Provider.GEMINI);
    }

    public String getModel() {
        return getProviderModel(AiProviderRouter.Provider.GEMINI);
    }

    public void save(final String apiKey, final String model) {
        saveProvider(AiProviderRouter.Provider.GEMINI, apiKey, model, "");
    }

    public String getPrimaryProvider() {
        final String value = preferences.getString(KEY_PRIMARY_PROVIDER, "gemini");
        return AiProviderRouter.Provider.fromId(value) == null ? "gemini" : value;
    }

    public boolean isAutoFailoverEnabled() {
        return preferences.getBoolean(KEY_AUTO_FAILOVER, true);
    }

    public void savePreferences(final String primaryProvider, final boolean autoFailoverEnabled) {
        final AiProviderRouter.Provider provider = AiProviderRouter.Provider.fromId(primaryProvider);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported AI provider: " + primaryProvider);
        }
        preferences.edit()
                .putString(KEY_PRIMARY_PROVIDER, provider.getId())
                .putBoolean(KEY_AUTO_FAILOVER, autoFailoverEnabled)
                .apply();
    }

    public String getProviderApiKey(final AiProviderRouter.Provider provider) {
        return trim(preferences.getString(apiKeyKey(provider), ""));
    }

    public String getProviderModel(final AiProviderRouter.Provider provider) {
        return preferences.getString(modelKey(provider), provider.getDefaultModel());
    }

    public String getProviderBaseUrl(final AiProviderRouter.Provider provider) {
        if (provider == AiProviderRouter.Provider.GROQ
                || provider == AiProviderRouter.Provider.OPENROUTER) {
            return provider.getDefaultBaseUrl();
        }
        if (provider == AiProviderRouter.Provider.CUSTOM) {
            return preferences.getString("custom_base_url", "");
        }
        return "";
    }

    public void saveProvider(final AiProviderRouter.Provider provider, final String apiKey,
            final String model, final String baseUrl) {
        final String modelValue = trim(model).isEmpty()
                ? provider.getDefaultModel() : trim(model);
        final SharedPreferences.Editor editor = preferences.edit()
                .putString(apiKeyKey(provider), trim(apiKey))
                .putString(modelKey(provider), modelValue);
        if (provider == AiProviderRouter.Provider.CUSTOM) {
            editor.putString("custom_base_url", trim(baseUrl));
        }
        editor.apply();
    }

    public List<AiProviderRouter.ProviderConfig> getProviderConfigs() {
        final List<AiProviderRouter.ProviderConfig> providers = new ArrayList<>();
        for (AiProviderRouter.Provider provider : AiProviderRouter.Provider.values()) {
            providers.add(new AiProviderRouter.ProviderConfig(provider,
                    getProviderApiKey(provider), getProviderModel(provider), getProviderBaseUrl(provider)));
        }
        return providers;
    }

    public boolean hasConfiguredProviders() {
        for (AiProviderRouter.Provider provider : AiProviderRouter.Provider.values()) {
            if (!getProviderApiKey(provider).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String apiKeyKey(final AiProviderRouter.Provider provider) {
        switch (provider) {
            case GEMINI:
                return "gemini_api_key";
            case GROQ:
                return "groq_api_key";
            case OPENROUTER:
                return "openrouter_api_key";
            case CUSTOM:
                return "custom_api_key";
            default:
                throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        }
    }

    private static String modelKey(final AiProviderRouter.Provider provider) {
        switch (provider) {
            case GEMINI:
                return "gemini_model";
            case GROQ:
                return "groq_model";
            case OPENROUTER:
                return "openrouter_model";
            case CUSTOM:
                return "custom_model";
            default:
                throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        }
    }

    private static String trim(final String value) {
        return value == null ? "" : value.trim();
    }
}
