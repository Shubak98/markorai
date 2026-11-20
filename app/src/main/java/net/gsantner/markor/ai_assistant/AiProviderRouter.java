package net.gsantner.markor.ai_assistant;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class AiProviderRouter {
    public enum Provider {
        GEMINI("gemini", "Gemini", AiSettingsStore.DEFAULT_MODEL, ""),
        GROQ("groq", "Groq", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1"),
        OPENROUTER("openrouter", "OpenRouter", "openrouter/free", "https://openrouter.ai/api/v1"),
        CUSTOM("custom", "Custom", "", "");

        private final String id;
        private final String displayName;
        private final String defaultModel;
        private final String defaultBaseUrl;

        Provider(final String id, final String displayName, final String defaultModel,
                final String defaultBaseUrl) {
            this.id = id;
            this.displayName = displayName;
            this.defaultModel = defaultModel;
            this.defaultBaseUrl = defaultBaseUrl;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public String getDefaultBaseUrl() {
            return defaultBaseUrl;
        }

        public static Provider fromId(final String id) {
            if (id == null) {
                return null;
            }
            for (Provider provider : values()) {
                if (provider.id.equals(id)) {
                    return provider;
                }
            }
            return null;
        }
    }

    public interface ProviderRequest {
        GeminiMarkdownHelper.IntentResult generate(ProviderConfig config) throws IOException;
    }

    private final AiSettingsStore settingsStore;

    public AiProviderRouter(final AiSettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    public Result generateIntent(final String markdown, final String instruction, final String selectedMode,
            final String folderContext) throws IOException {
        return execute(settingsStore.getProviderConfigs(), settingsStore.getPrimaryProvider(),
                settingsStore.isAutoFailoverEnabled(), config -> {
                    if (config.provider == Provider.GEMINI) {
                        return GeminiMarkdownHelper.generateIntent(config.apiKey, config.model,
                                markdown, instruction, selectedMode, folderContext);
                    }
                    return OpenAiCompatibleHelper.generateIntent(config.provider.getDisplayName(),
                            config.baseUrl, config.apiKey, config.model, markdown, instruction,
                            selectedMode, folderContext);
                });
    }

    public static List<ProviderConfig> buildProviderChain(final String primaryProvider,
            final boolean autoFailoverEnabled, final List<ProviderConfig> configuredProviders) {
        final Map<Provider, ProviderConfig> configured = new EnumMap<>(Provider.class);
        for (ProviderConfig config : configuredProviders) {
            if (config != null && config.provider != null && !config.apiKey.trim().isEmpty()) {
                configured.put(config.provider, config);
            }
        }
        final List<ProviderConfig> chain = new ArrayList<>();
        final Provider primary = Provider.fromId(primaryProvider);
        if (primary != null && configured.containsKey(primary)) {
            chain.add(configured.get(primary));
        }
        if (autoFailoverEnabled) {
            for (Provider provider : Provider.values()) {
                if (provider != primary && configured.containsKey(provider)) {
                    chain.add(configured.get(provider));
                }
            }
        }
        return chain;
    }

    public static Result execute(final List<ProviderConfig> configuredProviders, final String primaryProvider,
            final boolean autoFailoverEnabled, final ProviderRequest request) throws IOException {
        final List<ProviderConfig> chain = buildProviderChain(
                primaryProvider, autoFailoverEnabled, configuredProviders);
        if (chain.isEmpty()) {
            throw new IOException("No AI provider is configured. Add an API key in AI Assistant settings.");
        }

        final List<Failure> failures = new ArrayList<>();
        for (int index = 0; index < chain.size(); index++) {
            final ProviderConfig config = chain.get(index);
            try {
                final GeminiMarkdownHelper.IntentResult result = request.generate(config);
                return new Result(result, config.provider.getDisplayName(),
                        failures.isEmpty() ? "" : buildFailoverNotice(config.provider, failures));
            } catch (IOException exception) {
                if (isRetryable(exception)) {
                    failures.add(new Failure(config.provider, errorCode(exception)));
                    if (index + 1 < chain.size()) {
                        continue;
                    }
                } else if (failures.isEmpty()) {
                    throw providerError(config.provider, exception);
                } else {
                    failures.add(new Failure(config.provider, errorCode(exception)));
                }
                throw combinedError(failures, exception);
            }
        }
        throw new IOException("No AI provider returned a response.");
    }

    private static boolean isRetryable(final IOException exception) {
        if (exception instanceof ProviderFailureException) {
            final int status = ((ProviderFailureException) exception).getStatusCode();
            return status == 429 || status >= 500 && status <= 599;
        }
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String errorCode(final IOException exception) {
        if (exception instanceof ProviderFailureException) {
            return "HTTP " + ((ProviderFailureException) exception).getStatusCode();
        }
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return "timeout";
            }
            cause = cause.getCause();
        }
        return "request error";
    }

    private static IOException providerError(final Provider provider, final IOException exception) {
        return new IOException(provider.getDisplayName() + " failed (" + errorCode(exception) + "): "
                + exception.getMessage(), exception);
    }

    private static IOException combinedError(final List<Failure> failures, final IOException cause) {
        final StringBuilder summary = new StringBuilder("All configured AI providers failed: ");
        for (int index = 0; index < failures.size(); index++) {
            if (index > 0) {
                summary.append("; ");
            }
            final Failure failure = failures.get(index);
            summary.append(failure.provider.getDisplayName()).append(' ').append(failure.code);
        }
        return new IOException(summary.append('.').toString(), cause);
    }

    private static String buildFailoverNotice(final Provider usedProvider, final List<Failure> failures) {
        final StringBuilder notice = new StringBuilder("Used ").append(usedProvider.getDisplayName()).append(" (");
        for (int index = 0; index < failures.size(); index++) {
            if (index > 0) {
                notice.append(", ");
            }
            final Failure failure = failures.get(index);
            notice.append(failure.provider.getDisplayName());
            if ("HTTP 429".equals(failure.code)) {
                notice.append(" rate-limited");
            } else if ("timeout".equals(failure.code)) {
                notice.append(" timed out");
            } else {
                notice.append(' ').append(failure.code);
            }
        }
        return notice.append(')').toString();
    }

    private static final class Failure {
        private final Provider provider;
        private final String code;

        private Failure(final Provider provider, final String code) {
            this.provider = provider;
            this.code = code;
        }
    }

    public static final class ProviderConfig {
        private final Provider provider;
        private final String apiKey;
        private final String model;
        private final String baseUrl;

        public ProviderConfig(final Provider provider, final String apiKey, final String model,
                final String baseUrl) {
            this.provider = provider;
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.model = model == null ? "" : model.trim();
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        }

        public Provider getProvider() {
            return provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public String getModel() {
            return model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }
    }

    public static final class Result {
        private final GeminiMarkdownHelper.IntentResult intentResult;
        private final String usedProviderName;
        private final String failoverNotice;

        private Result(final GeminiMarkdownHelper.IntentResult intentResult,
                final String usedProviderName, final String failoverNotice) {
            this.intentResult = intentResult;
            this.usedProviderName = usedProviderName;
            this.failoverNotice = failoverNotice;
        }

        public GeminiMarkdownHelper.IntentResult getIntentResult() {
            return intentResult;
        }

        public String getUsedProviderName() {
            return usedProviderName;
        }

        public String getFailoverNotice() {
            return failoverNotice;
        }
    }

    public static final class ProviderFailureException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String providerName;
        private final int statusCode;

        public ProviderFailureException(final String providerName, final int statusCode, final String message) {
            super(message);
            this.providerName = providerName;
            this.statusCode = statusCode;
        }

        public String getProviderName() {
            return providerName;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
