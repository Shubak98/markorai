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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class GeminiMarkdownHelper {
    private static final String SYSTEM_INSTRUCTION = "You are a helpful Markdown AI assistant. Understand instructions in Hindi, Hinglish, and English. Return exactly one compact JSON object without Markdown fences or commentary, with mode set to edit, answer, navigate, or create_note. Respect the selected mode in the request; when it is auto, choose the best mode. For edit, return edits: an array of objects with action (insert_after, replace, or append_end), target, and content. For insert_after and replace, target must be an exact snippet from the original Markdown occurring exactly once; append_end requires an empty target. All targets refer to the unchanged original. For answer, handle Q&A, summaries, explanations, MCQs, flashcards, Anki-style Q&A, and tables without editing; return answer and optionally matched_file. If folder context is supplied, answer from that context, cite exact filenames and headings, and set matched_file to the best source filename. For navigate, return target_text (an exact heading or unique line from the document) and reason (a short one-line summary). For create_note, return a safe .md filename and full Markdown content. Preserve unrelated document content when editing.";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private GeminiMarkdownHelper() {
    }

    public static String generateMarkdownEdit(final String apiKey, final String model,
            final String markdown, final String instruction) throws IOException {
        final IntentResult result = generateIntent(apiKey, model, markdown, instruction, "edit", "");
        if (!"edit".equals(result.getMode())) {
            throw new IOException("Gemini did not return an edit patch.");
        }
        return applyEdits(markdown, result.getEditsJson());
    }

    public static IntentResult generateIntent(final String apiKey, final String model,
            final String markdown, final String instruction, final String selectedMode,
            final String folderContext) throws IOException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter your Gemini API key in AI Assistant settings.");
        }
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IOException("The Gemini API key must be a single line. Check AI Assistant settings.");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IOException("Enter a Gemini model ID in AI Assistant settings.");
        }
        final JSONObject request = buildIntentRequest(markdown, instruction, selectedMode, folderContext);

        final HttpURLConnection connection;
        try {
            final String encodedModel = URLEncoder.encode(model.trim(), "UTF-8").replace("+", "%20");
            connection = (HttpURLConnection) new URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                            + encodedModel + ":generateContent").openConnection();
        } catch (IOException exception) {
            throw networkError("Could not prepare the Gemini request", exception);
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
            return extractIntent(readStream(connection.getInputStream()));
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Gemini")) {
                throw exception;
            }
            throw networkError("Gemini network request", exception);
        } finally {
            connection.disconnect();
        }
    }

    public static IntentResult parseIntentResponse(final String response) throws IOException {
        try {
            final JSONObject json = new JSONObject(response);
            final Object modeValue = json.opt("mode");
            if (!(modeValue instanceof String)) {
                throw new IOException("Gemini returned an intent without a valid \"mode\" string.");
            }
            final String mode = (String) modeValue;
            if ("edit".equals(mode)) {
                final JSONArray edits = json.optJSONArray("edits");
                if (edits == null) {
                    throw new IOException("Gemini returned an edit intent without an \"edits\" array.");
                }
                return new IntentResult(mode, json.toString(), null, null, null, null, null, null);
            } else if ("answer".equals(mode)) {
                return new IntentResult(mode, null, requiredString(json, "answer"),
                        optionalString(json, "matched_file"), null, null, null, null);
            } else if ("navigate".equals(mode)) {
                return new IntentResult(mode, null, null, null, requiredString(json, "target_text"),
                        requiredString(json, "reason"), null, null);
            } else if ("create_note".equals(mode)) {
                return new IntentResult(mode, null, null, null, null, null,
                        requiredString(json, "filename"), requiredString(json, "content"));
            }
            throw new IOException("Gemini returned an unsupported intent mode.");
        } catch (JSONException exception) {
            throw new IOException("Gemini returned invalid intent JSON (" + exception.getClass().getName()
                    + "): " + exception.getMessage(), exception);
        }
    }

    public static String sanitizeNoteFilename(final String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        name = name.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                .replaceAll("^\\.+", "")
                .trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "New Note";
        }
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            name += ".md";
        }
        return name;
    }

    private static String requiredString(final JSONObject json, final String key) throws IOException {
        final Object value = json.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IOException("Gemini returned an intent without a valid \"" + key + "\" string.");
        }
        return (String) value;
    }

    private static String optionalString(final JSONObject json, final String key) throws IOException {
        final Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IOException("Gemini returned an invalid \"" + key + "\" value.");
        }
        return (String) value;
    }

    public static List<String> fetchAvailableModels(final String apiKey) throws IOException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter your Gemini API key to fetch available models.");
        }
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IOException("The Gemini API key must be a single line. Check AI Assistant settings.");
        }

        final List<String> models = new ArrayList<>();
        String pageToken = "";
        do {
            final String url = "https://generativelanguage.googleapis.com/v1beta/models"
                    + (pageToken.isEmpty() ? "" : "?pageToken="
                            + URLEncoder.encode(pageToken, "UTF-8").replace("+", "%20"));
            final HttpURLConnection connection;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
            } catch (IOException exception) {
                throw networkError("Could not prepare the Gemini model listing request", exception);
            }

            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("x-goog-api-key", apiKey);

                final int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    final InputStream errorStream = connection.getErrorStream();
                    final String errorBody = errorStream == null ? "" : readStream(errorStream);
                    throw new IOException(httpErrorMessage(status, errorBody));
                }
                pageToken = appendAvailableModels(readStream(connection.getInputStream()), models);
            } catch (IOException exception) {
                if (exception.getMessage() != null && exception.getMessage().startsWith("Gemini")) {
                    throw exception;
                }
                throw networkError("Could not fetch Gemini models", exception);
            } finally {
                connection.disconnect();
            }
        } while (!pageToken.isEmpty());

        Collections.sort(models);
        return models;
    }

    static String appendAvailableModels(final String response, final List<String> models) throws IOException {
        try {
            final JSONObject json = new JSONObject(response);
            final JSONArray availableModels = json.optJSONArray("models");
            if (availableModels != null) {
                for (int index = 0; index < availableModels.length(); index++) {
                    final JSONObject model = availableModels.optJSONObject(index);
                    final JSONArray methods = model == null ? null : model.optJSONArray("supportedGenerationMethods");
                    boolean supportsGeneration = false;
                    if (methods != null) {
                        for (int methodIndex = 0; methodIndex < methods.length(); methodIndex++) {
                            if ("generateContent".equals(methods.optString(methodIndex))) {
                                supportsGeneration = true;
                                break;
                            }
                        }
                    }
                    if (supportsGeneration) {
                        final String name = model.optString("name", "");
                        final String modelId = name.startsWith("models/") ? name.substring("models/".length()) : name;
                        if (!modelId.isEmpty() && !models.contains(modelId)) {
                            models.add(modelId);
                        }
                    }
                }
            }
            return json.optString("nextPageToken", "");
        } catch (JSONException exception) {
            throw new IOException("Gemini returned an invalid model list (" + exception.getClass().getName()
                    + "): " + exception.getMessage(), exception);
        }
    }

    public static String applyEdits(final String originalMarkdown, final String editsJson) throws IOException {
        if (originalMarkdown == null) {
            throw new IOException("Gemini edits could not be applied because the original Markdown is missing.");
        }

        try {
            final JSONArray edits = new JSONObject(editsJson).optJSONArray("edits");
            if (edits == null) {
                throw new IOException("Gemini returned an invalid edit patch: the \"edits\" array is missing.");
            }
            final List<Patch> patches = new ArrayList<>();
            for (int index = 0; index < edits.length(); index++) {
                final int editNumber = index + 1;
                final JSONObject edit = edits.optJSONObject(index);
                if (edit == null) {
                    throw invalidEdit(editNumber, "the edit must be a JSON object.");
                }
                final Object actionValue = edit.opt("action");
                final Object targetValue = edit.opt("target");
                final Object contentValue = edit.opt("content");
                if (!(actionValue instanceof String) || !(targetValue instanceof String)
                        || !(contentValue instanceof String)) {
                    throw invalidEdit(editNumber, "action, target, and content must be strings.");
                }
                final String action = (String) actionValue;
                final String target = (String) targetValue;
                final String content = (String) contentValue;
                if ("append_end".equals(action)) {
                    if (!target.isEmpty()) {
                        throw invalidEdit(editNumber, "append_end requires an empty target.");
                    }
                    patches.add(new Patch(originalMarkdown.length(), originalMarkdown.length(), content));
                } else if ("insert_after".equals(action) || "replace".equals(action)) {
                    if (target.isEmpty()) {
                        throw invalidEdit(editNumber, action + " requires a non-empty target.");
                    }
                    final int targetIndex = findUniqueTarget(originalMarkdown, target, editNumber);
                    final int targetEnd = targetIndex + target.length();
                    if ("insert_after".equals(action)) {
                        patches.add(new Patch(targetEnd, targetEnd, content));
                    } else {
                        patches.add(new Patch(targetIndex, targetEnd, content));
                    }
                } else {
                    throw invalidEdit(editNumber, "unsupported action \"" + action + "\".");
                }
            }

            validatePatchPositions(patches);
            Collections.sort(patches, new Comparator<Patch>() {
                @Override
                public int compare(final Patch first, final Patch second) {
                    final int byStart = Integer.compare(first.start, second.start);
                    if (byStart != 0) {
                        return byStart;
                    }
                    if (first.isInsertion() == second.isInsertion()) {
                        return 0;
                    }
                    return first.isInsertion() ? -1 : 1;
                }
            });

            final StringBuilder updated = new StringBuilder(originalMarkdown.length());
            int position = 0;
            for (Patch patch : patches) {
                updated.append(originalMarkdown, position, patch.start);
                updated.append(patch.content);
                position = patch.end;
            }
            updated.append(originalMarkdown, position, originalMarkdown.length());
            final String result = updated.toString();
            if (!originalMarkdown.trim().isEmpty() && result.trim().isEmpty()) {
                throw new IOException("Gemini edits would remove all Markdown content. No changes were applied.");
            }
            return result;
        } catch (JSONException exception) {
            throw new IOException("Gemini returned an invalid edit patch (" + exception.getClass().getName()
                    + "): " + exception.getMessage(), exception);
        }
    }

    private static IOException invalidEdit(final int editNumber, final String detail) {
        return new IOException("Gemini edit " + editNumber + " is invalid: " + detail);
    }

    private static int findUniqueTarget(final String markdown, final String target, final int editNumber)
            throws IOException {
        final int targetIndex = markdown.indexOf(target);
        if (targetIndex < 0) {
            throw new IOException("Gemini edit " + editNumber + " target was not found: \"" + target + "\"");
        }
        if (markdown.indexOf(target, targetIndex + 1) >= 0) {
            throw new IOException("Gemini edit " + editNumber + " target is ambiguous because it occurs more than "
                    + "once: \"" + target + "\"");
        }
        return targetIndex;
    }

    private static void validatePatchPositions(final List<Patch> patches) throws IOException {
        for (int firstIndex = 0; firstIndex < patches.size(); firstIndex++) {
            final Patch first = patches.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < patches.size(); secondIndex++) {
                final Patch second = patches.get(secondIndex);
                if (first.isInsertion() && second.isInsertion() && first.start == second.start) {
                    throw new IOException("Gemini returned edits at the same position. No changes were applied.");
                }
                if (!first.isInsertion() && !second.isInsertion()
                        && first.start < second.end && second.start < first.end) {
                    throw new IOException("Gemini returned overlapping edit targets. No changes were applied.");
                }
                if ((first.isInsertion() && !second.isInsertion()
                        && first.start > second.start && first.start < second.end)
                        || (second.isInsertion() && !first.isInsertion()
                                && second.start > first.start && second.start < first.end)) {
                    throw new IOException("Gemini returned an edit inside another edit target. No changes were applied.");
                }
            }
        }
    }

    private static final class Patch {
        private final int start;
        private final int end;
        private final String content;

        private Patch(final int start, final int end, final String content) {
            this.start = start;
            this.end = end;
            this.content = content;
        }

        private boolean isInsertion() {
            return start == end;
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

    private static JSONObject buildIntentRequest(final String markdown, final String instruction,
            final String selectedMode, final String folderContext) throws IOException {
        try {
            final JSONObject systemPart = new JSONObject().put("text", SYSTEM_INSTRUCTION);
            final JSONObject systemInstruction = new JSONObject()
                    .put("parts", new JSONArray().put(systemPart));
            final String prompt = "Selected mode: " + selectedMode
                    + "\nFor selected mode \"edit\", output edit. For \"study\" or \"folder\", output answer. "
                    + "For \"jump\", output navigate. For \"new_note\", output create_note. "
                    + "Use supplied folder context for folder search. If folder context is supplied in Auto, answer from it. "
                    + "\"auto\" means choose the best action automatically.\n\n"
                    + "User instruction:\n" + instruction
                    + "\n\nCurrent Markdown document:\n" + markdown
                    + (folderContext == null || folderContext.isEmpty()
                            ? "" : "\n\nFolder search context:\n" + folderContext);
            final JSONObject contentPart = new JSONObject().put("text", prompt);
            final JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(contentPart));
            return new JSONObject()
                    .put("systemInstruction", systemInstruction)
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", new JSONObject()
                            .put("responseMimeType", "application/json"));
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the Gemini request ("
                    + exception.getClass().getName() + "): " + exception.getMessage(), exception);
        }
    }

    private static IntentResult extractIntent(final String response) throws IOException {
        try {
            final JSONObject json = new JSONObject(response);
            final JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new IOException("Gemini returned no assistant response. Check the model and API access.");
            }
            final JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            final JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) {
                throw new IOException("Gemini returned an empty assistant response.");
            }
            final StringBuilder generatedJson = new StringBuilder();
            for (int index = 0; index < parts.length(); index++) {
                final String text = parts.getJSONObject(index).optString("text", "");
                if (!text.isEmpty()) {
                    generatedJson.append(text);
                }
            }
            if (generatedJson.toString().trim().isEmpty()) {
                throw new IOException("Gemini returned an empty assistant response.");
            }
            return parseIntentResponse(stripMarkdownFences(generatedJson.toString()));
        } catch (JSONException exception) {
            throw new IOException("Gemini returned an invalid assistant response ("
                    + exception.getClass().getName() + "): " + exception.getMessage(), exception);
        }
    }

    public static final class IntentResult {
        private final String mode;
        private final String editsJson;
        private final String answer;
        private final String matchedFile;
        private final String targetText;
        private final String reason;
        private final String filename;
        private final String noteContent;

        private IntentResult(final String mode, final String editsJson, final String answer,
                final String matchedFile, final String targetText, final String reason,
                final String filename, final String noteContent) {
            this.mode = mode;
            this.editsJson = editsJson;
            this.answer = answer;
            this.matchedFile = matchedFile;
            this.targetText = targetText;
            this.reason = reason;
            this.filename = filename;
            this.noteContent = noteContent;
        }

        public String getMode() {
            return mode;
        }

        public String getEditsJson() {
            return editsJson;
        }

        public String getAnswer() {
            return answer;
        }

        public String getMatchedFile() {
            return matchedFile;
        }

        public String getTargetText() {
            return targetText;
        }

        public String getReason() {
            return reason;
        }

        public String getFilename() {
            return filename;
        }

        public String getNoteContent() {
            return noteContent;
        }
    }

    private static String httpErrorMessage(final int status, final String responseBody) {
        final String detail;
        switch (status) {
            case HttpURLConnection.HTTP_BAD_REQUEST:
                detail = "Gemini rejected the request. Check the model ID and request.";
                break;
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                detail = "The Gemini API key was not accepted. Check it in AI Assistant settings.";
                break;
            case HttpURLConnection.HTTP_FORBIDDEN:
                detail = "Gemini denied access. Check the API key permissions and API availability.";
                break;
            case HttpURLConnection.HTTP_NOT_FOUND:
                detail = "The Gemini model was not found. Check the model ID in AI Assistant settings.";
                break;
            case 429:
                detail = "Gemini rate limit or quota exceeded. Try again later or check your quota.";
                break;
            default:
                detail = "Gemini returned an HTTP error.";
                break;
        }
        final String body = responseBody.trim().replaceAll("\\s+", " ");
        final String shortBody = body.length() > 400 ? body.substring(0, 400) + "..." : body;
        return "Gemini request failed: HTTP " + status + ". " + detail
                + (shortBody.isEmpty() ? "" : " Response body: " + shortBody);
    }

    private static IOException networkError(final String operation, final IOException exception) {
        final String message = exception.getMessage();
        return new IOException(operation + " failed (" + exception.getClass().getName() + ")"
                + (message == null || message.isEmpty() ? "." : ": " + message), exception);
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
