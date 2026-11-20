package net.gsantner.markor.ai_assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiAssistantModule {
    public interface ScrollResultCallback {
        void onResult(boolean found);
    }

    public interface ScrollCallback {
        void scrollTo(String target, ScrollResultCallback callback);
    }

    public interface OpenFileCallback {
        void open(File file);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Fragment fragment;
    private final ActivityResultLauncher<Intent> voiceLauncher;
    private final AiSettingsStore settingsStore;
    private EditText activeInstructionInput;
    private View floatingButton;
    private Runnable onAfterApply = () -> { };
    private boolean isViewModeVisible;
    private File currentFile;
    private ScrollCallback scrollCallback;
    private OpenFileCallback openFileCallback;

    public AiAssistantModule(final Fragment fragment) {
        this.fragment = fragment;
        settingsStore = new AiSettingsStore(fragment.requireContext());
        voiceLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onVoiceResult);
    }

    public void attachFloatingButton(final View rootView, final EditText editText, final File currentFile,
            final ScrollCallback scrollCallback, final OpenFileCallback openFileCallback,
            final Runnable onAfterApply) {
        if (fragment.getContext() == null || rootView == null || editText == null) {
            return;
        }
        if (!(rootView instanceof FrameLayout)) {
            throw new IllegalArgumentException("AI Edit floating button requires a FrameLayout root view.");
        }
        if (floatingButton != null && floatingButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) floatingButton.getParent()).removeView(floatingButton);
        }
        this.currentFile = currentFile;
        this.scrollCallback = scrollCallback;
        this.openFileCallback = openFileCallback;
        this.onAfterApply = onAfterApply == null ? () -> { } : onAfterApply;

        final android.content.Context context = fragment.requireContext();
        final TextView button = new TextView(context);
        button.setText("AI");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("AI Edit");
        button.setFocusable(true);
        button.setClickable(true);
        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(103, 80, 164));
        button.setBackground(background);
        button.setElevation(dp(context, 8));

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(context, 56), dp(context, 56), Gravity.BOTTOM | Gravity.RIGHT);
        params.setMargins(0, 0, dp(context, 20), dp(context, 24));
        final FrameLayout root = (FrameLayout) rootView;
        root.addView(button, params);
        button.setOnClickListener(view -> openEditorDialog(editText));
        floatingButton = button;
        setViewModeVisible(isViewModeVisible);
        button.bringToFront();
    }

    public void setViewModeVisible(final boolean isViewMode) {
        isViewModeVisible = isViewMode;
        if (floatingButton != null) {
            floatingButton.setVisibility(isViewMode ? View.VISIBLE : View.GONE);
        }
    }

    public void openEditorDialog(final EditText editText) {
        if (fragment.getContext() == null || editText == null || currentFile == null) {
            return;
        }
        final android.content.Context context = fragment.requireContext();
        final LinearLayout content = verticalLayout(context);
        final String[] selectedMode = {"auto"};
        addModeChips(context, content, selectedMode);
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final EditText instructions = new EditText(context);
        instructions.setHint("Ask or instruct in Hindi, Hinglish, or English");
        instructions.setGravity(Gravity.TOP | Gravity.START);
        instructions.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        instructions.setMinLines(4);
        instructions.setMaxLines(8);
        instructions.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        activeInstructionInput = instructions;

        final Button voiceButton = new Button(context);
        voiceButton.setText("Voice (Mic)");
        final Button settingsButton = new Button(context);
        settingsButton.setText("Settings");
        actions.addView(voiceButton, weightedButtonParams(context));
        actions.addView(settingsButton, weightedButtonParams(context));
        content.addView(actions);
        content.addView(instructions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        content.addView(progress, centeredParams());
        final TextView progressLabel = new TextView(context);
        progressLabel.setText("Thinking...");
        progressLabel.setVisibility(View.GONE);
        progressLabel.setGravity(Gravity.CENTER);
        content.addView(progressLabel);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("AI Assistant")
                .setView(content)
                .setPositiveButton("Run Assistant", null)
                .setNegativeButton("Cancel", null)
                .create();
        voiceButton.setOnClickListener(view -> startVoiceInput());
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        dialog.setOnDismissListener(ignored -> {
            if (activeInstructionInput == instructions) {
                activeInstructionInput = null;
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String apiKey = settingsStore.getApiKey();
                    if (apiKey.isEmpty()) {
                        showSettingsDialog();
                        return;
                    }
                    final String instruction = instructions.getText().toString().trim();
                    if (instruction.isEmpty()) {
                        Toast.makeText(context, "Enter an instruction before running the assistant.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String originalMarkdown = editText.getText().toString();
                    final String model = settingsStore.getModel();
                    final String requestedMode = selectedMode[0];
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    voiceButton.setEnabled(false);
                    settingsButton.setEnabled(false);
                    instructions.setEnabled(false);
                    progress.setVisibility(View.VISIBLE);
                    progressLabel.setVisibility(View.VISIBLE);
                    EXECUTOR.execute(() -> {
                        try {
                            final boolean searchFolder = "folder".equals(requestedMode)
                                    || ("auto".equals(requestedMode)
                                    && FolderSearchHelper.isFolderSearchQuery(instruction));
                            final FolderSearchHelper.SearchResult folderResults = searchFolder
                                    ? FolderSearchHelper.search(currentFile.getParentFile(), instruction) : null;
                            final String folderContext = folderResults == null ? ""
                                    : folderResults.getContext().isEmpty()
                                    ? "No relevant .md or .txt files matched the query in this folder."
                                    : folderResults.getContext();
                            final GeminiMarkdownHelper.IntentResult result = GeminiMarkdownHelper.generateIntent(
                                    apiKey, model, originalMarkdown, instruction, requestedMode, folderContext);
                            final String expectedMode = expectedIntentMode(requestedMode);
                            if (expectedMode != null && !expectedMode.equals(result.getMode())) {
                                throw new IOException("Gemini did not follow the selected mode. Please try again.");
                            }
                            final String updatedMarkdown = "edit".equals(result.getMode())
                                    ? GeminiMarkdownHelper.applyEdits(originalMarkdown, result.getEditsJson())
                                    : null;
                            MAIN_HANDLER.post(() -> {
                                dialog.dismiss();
                                if (fragment.getContext() != null) {
                                    handleIntentResult(editText, originalMarkdown, result, updatedMarkdown,
                                            folderResults);
                                }
                            });
                        } catch (IOException exception) {
                            MAIN_HANDLER.post(() -> {
                                if (!dialog.isShowing() || fragment.getContext() == null) {
                                    return;
                                }
                                progress.setVisibility(View.GONE);
                                progressLabel.setVisibility(View.GONE);
                                voiceButton.setEnabled(true);
                                settingsButton.setEnabled(true);
                                instructions.setEnabled(true);
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                showError(exception.getMessage());
                            });
                        }
                    });
                }));
        dialog.show();
        if (settingsStore.getApiKey().isEmpty()) {
            showSettingsDialog();
        }
    }

    private static String expectedIntentMode(final String selectedMode) {
        switch (selectedMode) {
            case "edit":
                return "edit";
            case "study":
            case "folder":
                return "answer";
            case "jump":
                return "navigate";
            case "new_note":
                return "create_note";
            default:
                return null;
        }
    }

    private static void addModeChips(final android.content.Context context, final LinearLayout parent,
            final String[] selectedMode) {
        final String[][] modes = {
                {"auto", "Auto"}, {"edit", "Edit"}, {"study", "Ask / Study"},
                {"jump", "Jump"}, {"folder", "Folder"}, {"new_note", "New Note"}
        };
        final HorizontalScrollView horizontalScroll = new HorizontalScrollView(context);
        horizontalScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] mode : modes) {
            final TextView chip = new TextView(context);
            chip.setText(mode[1]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
            final GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(context, 20));
            chip.setBackground(background);
            final String chipMode = mode[0];
            chip.setOnClickListener(view -> {
                selectedMode[0] = chipMode;
                for (int index = 0; index < row.getChildCount(); index++) {
                    final TextView item = (TextView) row.getChildAt(index);
                    final boolean active = modes[index][0].equals(selectedMode[0]);
                    item.setTextColor(active ? Color.WHITE : Color.DKGRAY);
                    ((GradientDrawable) item.getBackground()).setColor(active
                            ? Color.rgb(103, 80, 164) : Color.rgb(238, 235, 244));
                }
            });
            row.addView(chip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) chip.getLayoutParams();
            params.setMargins(0, 0, dp(context, 6), dp(context, 8));
            chip.setLayoutParams(params);
        }
        horizontalScroll.addView(row);
        parent.addView(horizontalScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.getChildAt(0).performClick();
    }

    private void handleIntentResult(final EditText editText, final String originalMarkdown,
            final GeminiMarkdownHelper.IntentResult result, final String updatedMarkdown,
            final FolderSearchHelper.SearchResult folderResults) {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        if ("edit".equals(result.getMode())) {
            showDiffPreview(editText, originalMarkdown, updatedMarkdown);
        } else if ("answer".equals(result.getMode())) {
            final String matchedFile = result.getMatchedFile() != null
                    ? result.getMatchedFile()
                    : folderResults == null ? null : folderResults.getBestMatch();
            showAnswerDialog(editText, originalMarkdown, result.getAnswer(), matchedFile);
        } else if ("navigate".equals(result.getMode())) {
            if (scrollCallback == null) {
                Toast.makeText(context, "Could not jump to the requested section.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            scrollCallback.scrollTo(result.getTargetText(), found -> {
                final android.content.Context currentContext = fragment.getContext();
                if (currentContext != null) {
                    Toast.makeText(currentContext, found
                                    ? "Jumped to section: " + result.getReason()
                                    : "Could not find the requested section.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else if ("create_note".equals(result.getMode())) {
            showNewNotePreview(result.getFilename(), result.getNoteContent());
        } else {
            showError("Gemini returned an unsupported intent.");
        }
    }

    private void startVoiceInput() {
        final Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            voiceLauncher.launch(intent);
        } catch (ActivityNotFoundException exception) {
            final android.content.Context context = fragment.getContext();
            if (context != null) {
                Toast.makeText(context, "No speech recognition app is available.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void onVoiceResult(final ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        final List<String> matches = result.getData().getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (matches == null || matches.isEmpty() || activeInstructionInput == null) {
            return;
        }
        final EditText input = activeInstructionInput;
        final String recognizedText = matches.get(0);
        final int length = input.length();
        input.getText().append((length > 0 && !Character.isWhitespace(
                input.getText().charAt(length - 1)) ? " " : "") + recognizedText);
    }

    private void showSettingsDialog() {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final LinearLayout content = verticalLayout(context);
        final TextView keyLabel = new TextView(context);
        keyLabel.setText("Gemini API Key");
        final EditText apiKeyInput = new EditText(context);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setHint("Paste your API key");
        apiKeyInput.setText(settingsStore.getApiKey());
        final TextView modelLabel = new TextView(context);
        modelLabel.setText("Gemini Model ID");
        final EditText modelInput = new EditText(context);
        modelInput.setSingleLine(true);
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        modelInput.setHint(AiSettingsStore.DEFAULT_MODEL);
        modelInput.setText(settingsStore.getModel());
        final LinearLayout modelRow = new LinearLayout(context);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.addView(modelInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button fetchModelsButton = new Button(context);
        fetchModelsButton.setText("Fetch Models");
        modelRow.addView(fetchModelsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar modelProgress = new ProgressBar(context);
        modelProgress.setVisibility(View.GONE);
        final LinearLayout.LayoutParams modelProgressParams = new LinearLayout.LayoutParams(
                dp(context, 24), dp(context, 24));
        modelProgressParams.gravity = Gravity.CENTER_VERTICAL;
        modelProgressParams.setMargins(dp(context, 8), 0, 0, 0);
        modelRow.addView(modelProgress, modelProgressParams);
        content.addView(keyLabel);
        content.addView(apiKeyInput);
        content.addView(modelLabel);
        content.addView(modelRow);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("AI Assistant Settings")
                .setView(content)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        fetchModelsButton.setOnClickListener(view -> {
            final String typedApiKey = apiKeyInput.getText().toString().trim();
            final String apiKey = typedApiKey.isEmpty() ? settingsStore.getApiKey() : typedApiKey;
            if (apiKey.isEmpty()) {
                showModelFetchError(context, "Enter your Gemini API key before fetching models.");
                return;
            }
            fetchModelsButton.setEnabled(false);
            modelProgress.setVisibility(View.VISIBLE);
            EXECUTOR.execute(() -> {
                try {
                    final List<String> models = GeminiMarkdownHelper.fetchAvailableModels(apiKey);
                    MAIN_HANDLER.post(() -> {
                        if (!dialog.isShowing() || fragment.getContext() == null) {
                            return;
                        }
                        modelProgress.setVisibility(View.GONE);
                        fetchModelsButton.setEnabled(true);
                        if (models.isEmpty()) {
                            showModelFetchError(context,
                                    "No models supporting generateContent are available for this API key.");
                            return;
                        }
                        final String[] modelIds = models.toArray(new String[0]);
                        new AlertDialog.Builder(context)
                                .setTitle("Select a Gemini model")
                                .setSingleChoiceItems(modelIds, -1, (picker, which) -> {
                                    modelInput.setText(modelIds[which]);
                                    picker.dismiss();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    });
                } catch (IOException exception) {
                    MAIN_HANDLER.post(() -> {
                        if (!dialog.isShowing() || fragment.getContext() == null) {
                            return;
                        }
                        modelProgress.setVisibility(View.GONE);
                        fetchModelsButton.setEnabled(true);
                        showModelFetchError(context, exception.getMessage());
                    });
                }
            });
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String apiKey = apiKeyInput.getText().toString().trim();
                    final String model = modelInput.getText().toString().trim();
                    if (apiKey.isEmpty()) {
                        apiKeyInput.setError("API key is required");
                    } else if (model.isEmpty()) {
                        modelInput.setError("Model ID is required");
                    } else {
                        settingsStore.save(apiKey, model);
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    private static void showModelFetchError(final android.content.Context context, final String message) {
        new AlertDialog.Builder(context)
                .setTitle("Could not fetch Gemini models")
                .setMessage(message == null || message.isEmpty()
                        ? "The Gemini model listing failed. Please try again." : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAnswerDialog(final EditText editText, final String originalMarkdown,
            final String answer, final String matchedFile) {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final LinearLayout content = verticalLayout(context);
        final TextView answerText = new TextView(context);
        answerText.setText(answer);
        answerText.setTextIsSelectable(true);
        answerText.setTextSize(16);
        answerText.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(answerText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 320)));

        final LinearLayout buttonRows = new LinearLayout(context);
        buttonRows.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout firstRow = new LinearLayout(context);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout secondRow = new LinearLayout(context);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button copyButton = new Button(context);
        copyButton.setText("Copy");
        final Button appendButton = new Button(context);
        appendButton.setText("Append to Note");
        final Button closeButton = new Button(context);
        closeButton.setText("Close");
        firstRow.addView(copyButton, weightedButtonParams(context));
        firstRow.addView(appendButton, weightedButtonParams(context));
        if (findMatchedFile(matchedFile) != null) {
            final Button openButton = new Button(context);
            openButton.setText("Open File");
            secondRow.addView(openButton, weightedButtonParams(context));
            openButton.setOnClickListener(view -> {
                final File file = findMatchedFile(matchedFile);
                if (file != null && openFileCallback != null) {
                    openFileCallback.open(file);
                }
            });
        }
        secondRow.addView(closeButton, weightedButtonParams(context));
        buttonRows.addView(firstRow);
        buttonRows.addView(secondRow);
        content.addView(buttonRows);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("AI Answer")
                .setView(content)
                .create();
        copyButton.setOnClickListener(view -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("AI Answer", answer));
                Toast.makeText(context, "Answer copied.", Toast.LENGTH_SHORT).show();
            }
        });
        appendButton.setOnClickListener(view -> {
            try {
                final String appendContent = "\n\n## AI Answer\n\n" + answer;
                final String updated = GeminiMarkdownHelper.applyEdits(originalMarkdown,
                        new JSONObject().put("edits", new JSONArray().put(new JSONObject()
                                .put("action", "append_end").put("target", "").put("content", appendContent)))
                                .toString());
                dialog.dismiss();
                showDiffPreview(editText, originalMarkdown, updated);
            } catch (org.json.JSONException | IOException exception) {
                showError(exception.getMessage());
            }
        });
        closeButton.setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    private File findMatchedFile(final String matchedFile) {
        if (matchedFile == null || matchedFile.trim().isEmpty() || currentFile == null
                || currentFile.getParentFile() == null) {
            return null;
        }
        try {
            final File parent = currentFile.getParentFile().getCanonicalFile();
            final File candidate = new File(parent, matchedFile).getCanonicalFile();
            final String parentPath = parent.getPath() + File.separator;
            return candidate.getPath().startsWith(parentPath) && candidate.isFile() ? candidate : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private void showNewNotePreview(final String requestedFilename, final String noteContent) {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final LinearLayout content = verticalLayout(context);
        final EditText filenameInput = new EditText(context);
        filenameInput.setSingleLine(true);
        filenameInput.setHint("New note filename");
        filenameInput.setText(GeminiMarkdownHelper.sanitizeNoteFilename(requestedFilename));
        final TextView preview = new TextView(context);
        preview.setText(noteContent);
        preview.setTextIsSelectable(true);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        final ScrollView previewScroll = new ScrollView(context);
        previewScroll.addView(preview, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(filenameInput);
        content.addView(previewScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 320)));
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Preview New Note")
                .setView(content)
                .setPositiveButton("Create & Open", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        final File created = createNote(filenameInput.getText().toString(), noteContent);
                        dialog.dismiss();
                        if (openFileCallback != null) {
                            openFileCallback.open(created);
                        }
                    } catch (IOException exception) {
                        showError(exception.getMessage());
                    }
                }));
        dialog.show();
    }

    private File createNote(final String requestedFilename, final String content) throws IOException {
        final File parent = currentFile == null ? null : currentFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The current note's folder is not available.");
        }
        final String safeName = GeminiMarkdownHelper.sanitizeNoteFilename(requestedFilename);
        final int extensionIndex = safeName.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".md");
        final String base = safeName.substring(0, extensionIndex);
        for (int suffix = 0; ; suffix++) {
            final String name = base + (suffix == 0 ? "" : "-" + suffix) + ".md";
            final File file = new File(parent, name);
            if (!file.createNewFile()) {
                continue;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                return file;
            } catch (IOException exception) {
                if (!file.delete()) {
                    exception.addSuppressed(new IOException("Could not remove the incomplete note: "
                            + file.getAbsolutePath()));
                }
                throw exception;
            }
        }
    }

    private void showDiffPreview(final EditText editText, final String original, final String updated) {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final TextView preview = new TextView(context);
        preview.setTextSize(14);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(preview, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Diff Preview")
                .setView(scrollView)
                .setPositiveButton("Apply Changes", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Full Preview", null)
                .create();
        final boolean[] fullPreview = {false};
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                editText.getText().replace(0, editText.getText().length(), updated);
                dialog.dismiss();
                onAfterApply.run();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                fullPreview[0] = !fullPreview[0];
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setText(fullPreview[0] ? "Diff View" : "Full Preview");
                preview.setText(fullPreview[0] ? updated : buildDiffText(original, updated));
                scrollView.scrollTo(0, 0);
            });
            preview.setText(buildDiffText(original, updated));
        });
        dialog.show();
    }

    private void showError(final String message) {
        final android.content.Context context = fragment.getContext();
        if (context != null) {
            new AlertDialog.Builder(context)
                    .setTitle("AI Assistant Failed")
                    .setMessage(message == null || message.isEmpty()
                            ? "The Gemini request failed. Please try again." : message)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private static SpannableStringBuilder buildDiffText(final String original, final String updated) {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        if (original.equals(updated)) {
            return text.append("(No changes)");
        }
        final List<SimpleLineDiff.Line> lines = SimpleLineDiff.diff(original, updated);
        if (lines.isEmpty()) {
            return text.append("(No changes)");
        }
        for (SimpleLineDiff.Line line : lines) {
            final int start = text.length();
            final String prefix;
            final int background;
            switch (line.getType()) {
                case ADDED:
                    prefix = "+ ";
                    background = Color.rgb(232, 247, 234);
                    break;
                case REMOVED:
                    prefix = "- ";
                    background = Color.rgb(253, 235, 235);
                    break;
                default:
                    prefix = "  ";
                    background = Color.TRANSPARENT;
                    break;
            }
            text.append(prefix).append(line.getText()).append('\n');
            if (line.getType() != SimpleLineDiff.Type.UNCHANGED) {
                text.setSpan(new BackgroundColorSpan(background), start, text.length(), 0);
            }
        }
        return text;
    }

    private static LinearLayout verticalLayout(final android.content.Context context) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        return layout;
    }

    private static LinearLayout.LayoutParams weightedButtonParams(
            final android.content.Context context) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(context, 4), 0, dp(context, 4), dp(context, 8));
        return params;
    }

    private static LinearLayout.LayoutParams centeredParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        return params;
    }

    private static int dp(final android.content.Context context, final int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
