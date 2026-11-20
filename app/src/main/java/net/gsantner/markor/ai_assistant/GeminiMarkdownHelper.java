package net.gsantner.markor.ai_assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class GeminiMarkdownHelper {
    private static final String SYSTEM_INSTRUCTION = "You are a precise Markdown document editor. Read the full Markdown document and the user's instruction (which may be in Hindi, Hinglish, or English). Insert, update, or reorganize the requested content under the most relevant existing heading/section (only create a new heading if no existing section fits). Preserve all untouched sections, links, checkboxes, and Markdown formatting verbatim. Return ONLY the complete updated raw Markdown document—no explanations, no commentary, and no outer triple-backtick code fences.";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private GeminiMarkdownHelper() {
    }

    public static String generateMarkdownEdit(final String apiKey, final String model,
            final String markdown, final String instruction) throws IOException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter your Gemini API key in AI Edit settings.");
        }
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IOException("The Gemini API key must be a single line. Check AI Edit settings.");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IOException("Enter a Gemini model ID in AI Edit settings.");
        }
        final JSONObject request = buildRequest(markdown, instruction);

        final HttpURLConnection connection;
        try {
            final String encodedModel = URLEncoder.encode(model.trim(), "UTF-8").replace("+", "%20");
            connection = (HttpURLConnection) new URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                            + encodedModel + ":generateContent").openConnection();
        } catch (IOException exception) {
            throw new IOException("Could not prepare the Gemini request: " + exception.getMessage(), exception);
        }

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("x-goog-api-key", apiKey);

            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }

            final int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                final InputStream errorStream = connection.getErrorStream();
                final String errorBody = errorStream == null ? "" : readStream(errorStream);
                throw new IOException(httpErrorMessage(status, errorBody));
            }
            return extractGeneratedMarkdown(readStream(connection.getInputStream()));
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Gemini")) {
                throw exception;
            }
            throw new IOException("Gemini network request failed: " + exception.getMessage(), exception);
        } finally {
            connection.disconnect();
        }
    }

    public static String stripMarkdownFences(final String markdown) {
        if (markdown == null) {
            return null;
        }
        final String trimmed = markdown.trim();
        if (!trimmed.startsWith("```")) {
            return markdown;
        }
        final int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) {
            return markdown;
        }
        final String language = trimmed.substring(3, firstLineEnd).trim();
        if (!language.isEmpty() && !"markdown".equalsIgnoreCase(language) && !"md".equalsIgnoreCase(language)) {
            return markdown;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).replaceFirst("\\r?\\n$", "");
    }

    private static JSONObject buildRequest(final String markdown, final String instruction) throws IOException {
        try {
            final JSONObject systemPart = new JSONObject().put("text", SYSTEM_INSTRUCTION);
            final JSONObject systemInstruction = new JSONObject()
                    .put("parts", new JSONArray().put(systemPart));
            final String prompt = "User instruction:\n" + instruction
                    + "\n\nFull Markdown document:\n" + markdown;
            final JSONObject contentPart = new JSONObject().put("text", prompt);
            final JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(contentPart));
            return new JSONObject()
                    .put("systemInstruction", systemInstruction)
                    .put("contents", new JSONArray().put(content));
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the Gemini request.", exception);
        }
    }

    private static String extractGeneratedMarkdown(final String response) throws IOException {
        try {
            final JSONObject json = new JSONObject(response);
            final JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new IOException("Gemini returned no generated Markdown. Check the model and API access.");
            }
            final JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            final JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) {
                throw new IOException("Gemini returned an empty response instead of Markdown.");
            }
            final StringBuilder generated = new StringBuilder();
            for (int index = 0; index < parts.length(); index++) {
                final String text = parts.getJSONObject(index).optString("text", "");
                if (!text.isEmpty()) {
                    generated.append(text);
                }
            }
            final String result = stripMarkdownFences(generated.toString());
            if (result.trim().isEmpty()) {
                throw new IOException("Gemini returned an empty Markdown document.");
            }
            return result;
        } catch (JSONException exception) {
            throw new IOException("Gemini returned an invalid response: " + exception.getMessage(), exception);
        }
    }

    private static String httpErrorMessage(final int status, final String responseBody) {
        final String detail;
        switch (status) {
            case HttpURLConnection.HTTP_BAD_REQUEST:
                detail = "Gemini rejected the request. Check the model ID and request.";
                break;
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                detail = "The Gemini API key was not accepted. Check it in AI Edit settings.";
                break;
            case HttpURLConnection.HTTP_FORBIDDEN:
                detail = "Gemini denied access. Check the API key permissions and API availability.";
                break;
            case HttpURLConnection.HTTP_NOT_FOUND:
                detail = "The Gemini model was not found. Check the model ID in AI Edit settings.";
                break;
            case 429:
                detail = "Gemini rate limit or quota exceeded. Try again later or check your quota.";
                break;
            default:
                detail = "Gemini returned HTTP " + status + ".";
                break;
        }
        final String serverMessage = extractServerError(responseBody);
        return "Gemini request failed: " + detail
                + (serverMessage.isEmpty() ? "" : " " + serverMessage);
    }

    private static String extractServerError(final String responseBody) {
        if (responseBody.isEmpty()) {
            return "";
        }
        try {
            final JSONObject error = new JSONObject(responseBody).optJSONObject("error");
            return error == null ? "" : error.optString("message", "");
        } catch (JSONException ignored) {
            return "";
        }
    }

    private static String readStream(final InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
}
