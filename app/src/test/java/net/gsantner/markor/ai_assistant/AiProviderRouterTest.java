package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AiProviderRouterTest {
    @Test
    public void retriesNextConfiguredProviderAfterRateLimit() throws IOException {
        final List<AiProviderRouter.ProviderConfig> providers = Arrays.asList(
                config(AiProviderRouter.Provider.GEMINI, "gemini-key"),
                config(AiProviderRouter.Provider.GROQ, "groq-key"),
                config(AiProviderRouter.Provider.CUSTOM, ""));
        final List<AiProviderRouter.Provider> attempted = new ArrayList<>();

        final AiProviderRouter.Result result = AiProviderRouter.execute(
                providers, "gemini", true, provider -> {
                    attempted.add(provider.getProvider());
                    if (provider.getProvider() == AiProviderRouter.Provider.GEMINI) {
                        throw new AiProviderRouter.ProviderFailureException(
                                "Gemini", 429, "Gemini request failed: HTTP 429");
                    }
                    return GeminiMarkdownHelper.parseIntentResponse(
                            "{\"mode\":\"answer\",\"answer\":\"Success\"}");
                });

        assertEquals(Arrays.asList(AiProviderRouter.Provider.GEMINI, AiProviderRouter.Provider.GROQ), attempted);
        assertEquals("Groq", result.getUsedProviderName());
        assertEquals("Used Groq (Gemini rate-limited)", result.getFailoverNotice());
        assertEquals("Success", result.getIntentResult().getAnswer());
    }

    @Test
    public void ordersConfiguredProvidersAndSkipsProvidersWithoutKeys() {
        final List<AiProviderRouter.ProviderConfig> providers = Arrays.asList(
                config(AiProviderRouter.Provider.CUSTOM, "custom-key"),
                config(AiProviderRouter.Provider.GEMINI, ""),
                config(AiProviderRouter.Provider.OPENROUTER, "router-key"),
                config(AiProviderRouter.Provider.GROQ, "groq-key"));

        final List<AiProviderRouter.ProviderConfig> chain = AiProviderRouter.buildProviderChain(
                "gemini", true, providers);

        assertEquals(Arrays.asList(AiProviderRouter.Provider.GROQ, AiProviderRouter.Provider.OPENROUTER,
                        AiProviderRouter.Provider.CUSTOM),
                providerIds(chain));
    }

    @Test
    public void putsPrimaryBeforeRemainingProvidersInCanonicalOrder() {
        final List<AiProviderRouter.ProviderConfig> providers = Arrays.asList(
                config(AiProviderRouter.Provider.CUSTOM, "custom-key"),
                config(AiProviderRouter.Provider.GROQ, "groq-key"),
                config(AiProviderRouter.Provider.GEMINI, "gemini-key"));

        assertEquals(Arrays.asList(AiProviderRouter.Provider.CUSTOM, AiProviderRouter.Provider.GEMINI,
                        AiProviderRouter.Provider.GROQ),
                providerIds(AiProviderRouter.buildProviderChain("custom", true, providers)));
    }

    private static AiProviderRouter.ProviderConfig config(final AiProviderRouter.Provider provider,
            final String apiKey) {
        return new AiProviderRouter.ProviderConfig(provider, apiKey, provider.getDefaultModel(),
                provider.getDefaultBaseUrl());
    }

    private static List<AiProviderRouter.Provider> providerIds(
            final List<AiProviderRouter.ProviderConfig> configs) {
        final List<AiProviderRouter.Provider> providers = new ArrayList<>();
        for (AiProviderRouter.ProviderConfig config : configs) {
            providers.add(config.getProvider());
        }
        return providers;
    }
}
