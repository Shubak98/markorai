package net.gsantner.markor.ai_assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OpenAiCompatibleHelper {
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private OpenAiCompatibleHelper() {
    }

    public static GeminiMarkdownHelper.IntentResult generateIntent(final String providerName,
            final String baseUrl, final String apiKey, final String model, final String markdown,
            final String instruction, final String selectedMode, final String folderContext) throws IOException {
        validateCredentials(providerName, apiKey, model);
        final JSONObject requestBody = buildRequest(model,
                GeminiMarkdownHelper.buildIntentPrompt(markdown, instruction, selectedMode, folderContext));
        final String response = sendChatCompletion(providerName, baseUrl, apiKey, requestBody);
        return extractIntentResponse(response);
    }

    public static List<String> fetchAvailableModels(final String providerName,
            final String baseUrl, final String apiKey) throws IOException {
        validateCredentials(providerName, apiKey, "model");
        final HttpURLConnection connection = openConnection(buildEndpoint(baseUrl, "models"));
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            final int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw providerFailure(providerName, status, readError(connection));
            }
            final Map<?, ?> response = GeminiMarkdownHelper.parseJsonObject(readStream(connection.getInputStream()));
            final Object data = response.get("data");
            final List<String> models = new ArrayList<>();
            if (data instanceof List<?>) {
                for (Object item : (List<?>) data) {
                    if (item instanceof Map<?, ?>) {
                        final Object id = ((Map<?, ?>) item).get("id");
                        if (id instanceof String && !((String) id).trim().isEmpty()) {
                            models.add((String) id);
                        }
                    }
                }
            }
            return sortModels(models, baseUrl);
        } finally {
            connection.disconnect();
        }
    }

    public static String extractMessageContent(final String response) throws IOException {
        final Map<?, ?> json = GeminiMarkdownHelper.parseJsonObject(response);
        final Object choicesValue = json.get("choices");
        if (!(choicesValue instanceof List<?>) || ((List<?>) choicesValue).isEmpty()) {
            throw new IOException("The OpenAI-compatible provider returned no assistant response.");
        }
        final Object choice = ((List<?>) choicesValue).get(0);
        final Map<?, ?> choiceObject = choice instanceof Map<?, ?> ? (Map<?, ?>) choice : null;
        final Object messageValue = choiceObject == null ? null : choiceObject.get("message");
        final Map<?, ?> message = messageValue instanceof Map<?, ?> ? (Map<?, ?>) messageValue : null;
        final Object content = message == null ? null : message.get("content");
        if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
            throw new IOException("The OpenAI-compatible provider returned an empty assistant response.");
        }
        return (String) content;
    }

    public static GeminiMarkdownHelper.IntentResult extractIntentResponse(final String response)
            throws IOException {
        return GeminiMarkdownHelper.parseIntentResponse(
                GeminiMarkdownHelper.stripMarkdownFences(extractMessageContent(response)));
    }

    public static List<String> sortModels(final List<String> models, final String baseUrl) {
        final List<String> sorted = new ArrayList<>(models);
        final boolean openRouter = baseUrl != null
                && baseUrl.toLowerCase(Locale.ROOT).contains("openrouter.ai");
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(final String left, final String right) {
                final boolean leftFree = openRouter && left.toLowerCase(Locale.ROOT).contains(":free");
                final boolean rightFree = openRouter && right.toLowerCase(Locale.ROOT).contains(":free");
                if (leftFree != rightFree) {
                    return leftFree ? -1 : 1;
                }
                return left.compareToIgnoreCase(right);
            }
        });
        return sorted;
    }

    private static JSONObject buildRequest(final String model, final String prompt) throws IOException {
        try {
            final JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system")
                            .put("content", GeminiMarkdownHelper.getIntentSystemInstruction()))
                    .put(new JSONObject().put("role", "user").put("content", prompt));
            return new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("response_format", new JSONObject().put("type", "json_object"));
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the OpenAI-compatible request.", exception);
        }
    }

    private static String sendChatCompletion(final String providerName, final String baseUrl,
            final String apiKey, final JSONObject requestBody) throws IOException {
        try {
            return sendRequest(providerName, baseUrl, apiKey, requestBody);
        } catch (AiProviderRouter.ProviderFailureException exception) {
            if ((exception.getStatusCode() != HttpURLConnection.HTTP_BAD_REQUEST
                    && exception.getStatusCode() != 422)
                    || !isResponseFormatRejection(exception.getMessage())) {
                throw exception;
            }
            requestBody.remove("response_format");
            return sendRequest(providerName, baseUrl, apiKey, requestBody);
        }
    }

    private static String sendRequest(final String providerName, final String baseUrl,
            final String apiKey, final JSONObject requestBody) throws IOException {
        final HttpURLConnection connection = openConnection(buildEndpoint(baseUrl, "chat/completions"));
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            if (baseUrl.toLowerCase(Locale.ROOT).contains("openrouter.ai")) {
                connection.setRequestProperty("X-Title", "Markor AI");
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw providerFailure(providerName, status, readError(connection));
            }
            return readStream(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(final String endpoint) throws IOException {
        return (HttpURLConnection) new URL(endpoint).openConnection();
    }

    private static String buildEndpoint(final String baseUrl, final String path) throws IOException {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IOException("Enter a base URL for the OpenAI-compatible provider.");
        }
        final String normalized = baseUrl.trim().replaceAll("/+$", "");
        final URL url;
        try {
            url = new URL(normalized);
        } catch (IOException exception) {
            throw new IOException("The OpenAI-compatible base URL is invalid.", exception);
        }
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))
                || url.getHost().isEmpty() || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("The OpenAI-compatible base URL must be an HTTP(S) URL without credentials.");
        }
        return normalized + "/" + path;
    }

    private static void validateCredentials(final String providerName, final String apiKey,
            final String model) throws IOException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter an API key for " + providerName + " in AI Assistant settings.");
        }
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IOException(providerName + " API keys must be a single line.");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IOException("Enter a model ID for " + providerName + " in AI Assistant settings.");
        }
    }

    private static String readError(final HttpURLConnection connection) throws IOException {
        final InputStream stream = connection.getErrorStream();
        return stream == null ? "" : readStream(stream);
    }

    private static String readStream(final InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            return content.toString();
        }
    }

    private static AiProviderRouter.ProviderFailureException providerFailure(final String providerName,
            final int status, final String responseBody) {
        final String body = responseBody.trim().replaceAll("\\s+", " ");
        final String detail = body.length() > 400 ? body.substring(0, 400) + "..." : body;
        return new AiProviderRouter.ProviderFailureException(providerName, status,
                providerName + " request failed: HTTP " + status
                        + (detail.isEmpty() ? "." : ". " + detail));
    }

    private static boolean isResponseFormatRejection(final String message) {
        if (message == null) {
            return false;
        }
        final String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("response_format") || normalized.contains("json_object")
                || normalized.contains("response format") || normalized.contains("json mode")
                || normalized.contains("structured output");
    }
}
