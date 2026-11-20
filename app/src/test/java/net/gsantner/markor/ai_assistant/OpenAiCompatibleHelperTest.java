package net.gsantner.markor.ai_assistant;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class OpenAiCompatibleHelperTest {
    @Test
    public void extractsJsonIntentFromFencedOpenAiResponse() throws IOException {
        final String content = "```json\n{\"mode\":\"answer\",\"answer\":\"Study notes\"}\n```";
        final String response = "{\"choices\":[{\"message\":{\"content\":\""
                + content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"}}]}";

        assertEquals(content, OpenAiCompatibleHelper.extractMessageContent(response));
        assertEquals("Study notes", OpenAiCompatibleHelper.extractIntentResponse(response).getAnswer());
    }

    @Test
    public void sortsOpenRouterFreeModelsFirst() {
        assertEquals(Arrays.asList("alpha/model:free", "zeta/model:free", "alpha/model", "beta/model"),
                OpenAiCompatibleHelper.sortModels(
                        Arrays.asList("beta/model", "alpha/model:free", "alpha/model", "zeta/model:free"),
                        "https://openrouter.ai/api/v1"));
    }

    @Test
    public void doesNotPrioritizeFreeSuffixForOtherProviders() {
        assertEquals(Arrays.asList("alpha/model", "beta/model:free"),
                OpenAiCompatibleHelper.sortModels(
                        Arrays.asList("beta/model:free", "alpha/model"), "https://api.groq.com/openai/v1"));
    }
}
