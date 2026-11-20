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
import java.util.Map;

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

        final String response;
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
                throw new AiProviderRouter.ProviderFailureException(
                        AiProviderRouter.Provider.GEMINI.getDisplayName(), status,
                        httpErrorMessage(status, errorBody));
            }
            response = readStream(connection.getInputStream());
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Gemini")) {
                throw exception;
            }
            throw networkError("Gemini network request", exception);
        } finally {
            connection.disconnect();
        }
        return extractIntent(response);
    }

    static String getIntentSystemInstruction() {
        return SYSTEM_INSTRUCTION;
    }

    static String buildIntentPrompt(final String markdown, final String instruction,
            final String selectedMode, final String folderContext) {
        return "Selected mode: " + selectedMode
                + "\nFor selected mode \"edit\", output edit. For \"study\" or \"folder\", output answer. "
                + "For \"jump\", output navigate. For \"new_note\", output create_note. "
                + "Use supplied folder context for folder search. If folder context is supplied in Auto, answer from it. "
                + "\"auto\" means choose the best action automatically.\n\n"
                + "User instruction:\n" + instruction
                + "\n\nCurrent Markdown document:\n" + markdown
                + (folderContext == null || folderContext.isEmpty()
                        ? "" : "\n\nFolder search context:\n" + folderContext);
    }

    public static IntentResult parseIntentResponse(final String response) throws IOException {
        final Map<?, ?> json = asObject(parseJson(response), "intent");
        final Object modeValue = json.get("mode");
        if (!(modeValue instanceof String)) {
            throw new IOException("The AI provider returned an intent without a valid \"mode\" string.");
        }
        final String mode = (String) modeValue;
        if ("edit".equals(mode)) {
            if (!(json.get("edits") instanceof List<?>)) {
                throw new IOException("The AI provider returned an edit intent without an \"edits\" array.");
            }
            return new IntentResult(mode, response, null, null, null, null, null, null);
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
        throw new IOException("The AI provider returned an unsupported intent mode.");
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

    private static String requiredString(final Map<?, ?> json, final String key) throws IOException {
        final Object value = json.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IOException("The AI provider returned an intent without a valid \"" + key + "\" string.");
        }
        return (String) value;
    }

    private static String optionalString(final Map<?, ?> json, final String key) throws IOException {
        final Object value = json.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IOException("The AI provider returned an invalid \"" + key + "\" value.");
        }
        return (String) value;
    }

    private static Object parseJson(final String json) throws IOException {
        if (json == null) {
            throw new IOException("The AI provider returned invalid JSON: response is missing.");
        }
        return new JsonParser(json).parse();
    }

    static Map<?, ?> parseJsonObject(final String json) throws IOException {
        return asObject(parseJson(json), "response");
    }

    private static Map<?, ?> asObject(final Object value, final String description) throws IOException {
        final Map<?, ?> object = asObjectOrNull(value);
        if (object == null) {
            throw new IOException("The AI provider returned an invalid " + description
                    + ": a JSON object is required.");
        }
        return object;
    }

    private static Map<?, ?> asObjectOrNull(final Object value) {
        return value instanceof Map<?, ?> ? (Map<?, ?>) value : null;
    }

    private static final class JsonParser {
        private final String input;
        private int position;

        private JsonParser(final String input) {
            this.input = input;
        }

        private Object parse() throws IOException {
            skipWhitespace();
            final Object value = parseValue(0);
            skipWhitespace();
            if (position != input.length()) {
                throw invalidJson("unexpected data after the JSON value");
            }
            return value;
        }

        private Object parseValue(final int depth) throws IOException {
            if (depth > 64) {
                throw invalidJson("JSON nesting is too deep");
            }
            skipWhitespace();
            if (position >= input.length()) {
                throw invalidJson("unexpected end of input");
            }
            switch (input.charAt(position)) {
                case '{':
                    return parseObject(depth + 1);
                case '[':
                    return parseArray(depth + 1);
                case '"':
                    return parseString();
                case 't':
                    return parseLiteral("true", Boolean.TRUE);
                case 'f':
                    return parseLiteral("false", Boolean.FALSE);
                case 'n':
                    return parseLiteral("null", null);
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject(final int depth) throws IOException {
            expect('{');
            final Map<String, Object> object = new java.util.LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw invalidJson("object keys must be strings");
                }
                final String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue(depth));
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray(final int depth) throws IOException {
            expect('[');
            final List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            while (true) {
                array.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() throws IOException {
            expect('"');
            final StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                final char character = input.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw invalidJson("unescaped control character in string");
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (position >= input.length()) {
                    throw invalidJson("incomplete string escape");
                }
                final char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append(parseUnicodeEscape());
                        break;
                    default:
                        throw invalidJson("invalid string escape");
                }
            }
            throw invalidJson("unterminated string");
        }

        private char parseUnicodeEscape() throws IOException {
            if (position + 4 > input.length()) {
                throw invalidJson("incomplete Unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                final char hex = input.charAt(position++);
                final int digit = hex >= '0' && hex <= '9' ? hex - '0'
                        : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                        : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                if (digit < 0) {
                    throw invalidJson("invalid Unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object parseNumber() throws IOException {
            final int start = position;
            consume('-');
            if (consume('0')) {
                if (position < input.length() && input.charAt(position) >= '0'
                        && input.charAt(position) <= '9') {
                    throw invalidJson("invalid leading zero");
                }
            } else {
                if (position >= input.length() || input.charAt(position) < '1' || input.charAt(position) > '9') {
                    throw invalidJson("invalid number");
                }
                consumeDigits();
            }
            if (consume('.')) {
                if (!consumeDigits()) {
                    throw invalidJson("invalid number fraction");
                }
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                if (!consumeDigits()) {
                    throw invalidJson("invalid number exponent");
                }
            }
            try {
                return new java.math.BigDecimal(input.substring(start, position));
            } catch (NumberFormatException exception) {
                throw invalidJson("invalid number");
            }
        }

        private boolean consumeDigits() {
            final int start = position;
            while (position < input.length() && input.charAt(position) >= '0'
                    && input.charAt(position) <= '9') {
                position++;
            }
            return position > start;
        }

        private Object parseLiteral(final String literal, final Object value) throws IOException {
            if (!input.startsWith(literal, position)) {
                throw invalidJson("invalid JSON value");
            }
            position += literal.length();
            return value;
        }

        private void expect(final char expected) throws IOException {
            if (!consume(expected)) {
                throw invalidJson("expected '" + expected + "'");
            }
        }

        private boolean consume(final char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                final char character = input.charAt(position);
                if (character != ' ' && character != '\t' && character != '\r' && character != '\n') {
                    return;
                }
                position++;
            }
        }

        private IOException invalidJson(final String detail) {
            return new IOException("AI provider returned invalid JSON at character " + position
                    + ": " + detail + ".");
        }
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
        final Map<?, ?> json = asObject(parseJson(response), "model list");
        final Object modelsValue = json.get("models");
        if (modelsValue instanceof List<?>) {
            for (Object modelValue : (List<?>) modelsValue) {
                final Map<?, ?> model = asObjectOrNull(modelValue);
                final Object methodsValue = model == null ? null : model.get("supportedGenerationMethods");
                boolean supportsGeneration = false;
                if (methodsValue instanceof List<?>) {
                    for (Object method : (List<?>) methodsValue) {
                        if ("generateContent".equals(method)) {
                            supportsGeneration = true;
                            break;
                        }
                    }
                }
                if (supportsGeneration) {
                    final Object nameValue = model.get("name");
                    final String name = nameValue instanceof String ? (String) nameValue : "";
                    final String modelId = name.startsWith("models/")
                            ? name.substring("models/".length()) : name;
                    if (!modelId.isEmpty() && !models.contains(modelId)) {
                        models.add(modelId);
                    }
                }
            }
        }
        final Object nextPageToken = json.get("nextPageToken");
        return nextPageToken instanceof String ? (String) nextPageToken : "";
    }

    public static String applyEdits(final String originalMarkdown, final String editsJson) throws IOException {
        if (originalMarkdown == null) {
            throw new IOException("Gemini edits could not be applied because the original Markdown is missing.");
        }

        final Map<?, ?> patchObject = asObject(parseJson(editsJson), "edit patch");
        final Object editsValue = patchObject.get("edits");
        if (!(editsValue instanceof List<?>)) {
            throw new IOException("The AI provider returned an invalid edit patch: the \"edits\" array is missing.");
        }
        final List<?> edits = (List<?>) editsValue;
        final List<Patch> patches = new ArrayList<>();
        for (int index = 0; index < edits.size(); index++) {
            final int editNumber = index + 1;
            final Map<?, ?> edit = asObjectOrNull(edits.get(index));
            if (edit == null) {
                throw invalidEdit(editNumber, "the edit must be a JSON object.");
            }
            final Object actionValue = edit.get("action");
            final Object targetValue = edit.get("target");
            final Object contentValue = edit.get("content");
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
                    throw new IOException("The AI provider returned edits at the same position. "
                            + "No changes were applied.");
                }
                if (!first.isInsertion() && !second.isInsertion()
                        && first.start < second.end && second.start < first.end) {
                    throw new IOException("The AI provider returned overlapping edit targets. "
                            + "No changes were applied.");
                }
                if ((first.isInsertion() && !second.isInsertion()
                        && first.start > second.start && first.start < second.end)
                        || (second.isInsertion() && !first.isInsertion()
                                && second.start > first.start && second.start < first.end)) {
                    throw new IOException("The AI provider returned an edit inside another edit target. "
                            + "No changes were applied.");
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
        if (!language.isEmpty() && !"markdown".equalsIgnoreCase(language)
                && !"md".equalsIgnoreCase(language) && !"json".equalsIgnoreCase(language)) {
            return markdown;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).replaceFirst("\\r?\\n$", "");
    }

    private static JSONObject buildIntentRequest(final String markdown, final String instruction,
            final String selectedMode, final String folderContext) throws IOException {
        try {
            final JSONObject systemPart = new JSONObject().put("text", getIntentSystemInstruction());
            final JSONObject systemInstruction = new JSONObject()
                    .put("parts", new JSONArray().put(systemPart));
            final String prompt = buildIntentPrompt(markdown, instruction, selectedMode, folderContext);
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
