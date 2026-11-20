package net.gsantner.markor.ai_assistant;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

    private interface ModelSelectionCallback {
        void onSelected(String model);
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
        background.setColor(AiUiTheme.ACCENT);
        background.setStroke(dp(context, 2), Color.rgb(77, 0, 0));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED), background, null));
        button.setElevation(dp(context, 7));

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(context, 54), dp(context, 54), Gravity.BOTTOM | Gravity.RIGHT);
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
        addVerticalGap(context, content, 10);

        final EditText instructions = new EditText(context);
        instructions.setHint("Ask or instruct in Hindi, Hinglish, or English");
        instructions.setTextColor(AiUiTheme.PRIMARY_TEXT);
        instructions.setHintTextColor(Color.rgb(128, 128, 140));
        instructions.setGravity(Gravity.TOP | Gravity.START);
        instructions.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        instructions.setMinLines(5);
        instructions.setMaxLines(7);
        instructions.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        instructions.setBackground(AiUiTheme.inputBackground(context));
        activeInstructionInput = instructions;

        content.addView(instructions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        final ProgressBar progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        final LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(context, 22), dp(context, 22));
        progressParams.setMargins(0, dp(context, 12), dp(context, 8), 0);
        statusRow.addView(progress, progressParams);
        final TextView progressLabel = new TextView(context);
        progressLabel.setText("Thinking...");
        progressLabel.setVisibility(View.GONE);
        progressLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        statusRow.addView(progressLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(statusRow);

        final TextView settingsButton = AiUiTheme.headerAction(context, "⚙");
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button voiceButton = AiUiTheme.button(context, "Mic", AiUiTheme.ButtonKind.MIC);
        final Button runButton = AiUiTheme.button(context, "Run AI", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        final LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        voiceParams.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        actions.addView(voiceButton, voiceParams);
        actions.addView(runButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Assistant", content, actions, settingsButton);
        voiceButton.setOnClickListener(view -> startVoiceInput());
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (activeInstructionInput == instructions) {
                activeInstructionInput = null;
            }
        });
        runButton.setOnClickListener(view -> {
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
            runButton.setEnabled(false);
            cancelButton.setEnabled(false);
            voiceButton.setEnabled(false);
            settingsButton.setEnabled(false);
            instructions.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            progressLabel.setVisibility(View.VISIBLE);
            final android.view.inputmethod.InputMethodManager inputMethodManager =
                    (android.view.inputmethod.InputMethodManager)
                            context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(instructions.getWindowToken(), 0);
            }
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
                        cancelButton.setEnabled(true);
                        voiceButton.setEnabled(true);
                        settingsButton.setEnabled(true);
                        instructions.setEnabled(true);
                        runButton.setEnabled(true);
                        showError(exception.getMessage());
                    });
                }
            });
        });
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
        final TextView[] chips = new TextView[modes.length];
        for (int modeIndex = 0; modeIndex < modes.length; modeIndex++) {
            final String[] mode = modes[modeIndex];
            final TextView chip = new TextView(context);
            chips[modeIndex] = chip;
            chip.setText(mode[1]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            AiUiTheme.styleChip(chip, modeIndex == 0);
            final String chipMode = mode[0];
            chip.setOnClickListener(view -> {
                selectedMode[0] = chipMode;
                for (int index = 0; index < chips.length; index++) {
                    AiUiTheme.styleChip(chips[index], modes[index][0].equals(selectedMode[0]));
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
        final TextView keyLabel = AiUiTheme.label(context, "Gemini API Key", 13);
        keyLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        final EditText apiKeyInput = new EditText(context);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setHint("Paste your API key");
        styleInput(apiKeyInput, context);
        apiKeyInput.setText(settingsStore.getApiKey());
        final TextView modelLabel = AiUiTheme.label(context, "Gemini Model ID", 13);
        modelLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        final EditText modelInput = new EditText(context);
        modelInput.setSingleLine(true);
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        modelInput.setHint(AiSettingsStore.DEFAULT_MODEL);
        styleInput(modelInput, context);
        modelInput.setText(settingsStore.getModel());
        final LinearLayout modelRow = new LinearLayout(context);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.addView(modelInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button fetchModelsButton = AiUiTheme.button(
                context, "Fetch Models", AiUiTheme.ButtonKind.SECONDARY);
        modelRow.addView(fetchModelsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar modelProgress = new ProgressBar(context);
        modelProgress.setVisibility(View.GONE);
        modelProgress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        final LinearLayout.LayoutParams modelProgressParams = new LinearLayout.LayoutParams(
                dp(context, 24), dp(context, 24));
        modelProgressParams.gravity = Gravity.CENTER_VERTICAL;
        modelProgressParams.setMargins(dp(context, 8), 0, 0, 0);
        modelRow.addView(modelProgress, modelProgressParams);
        content.addView(keyLabel);
        content.addView(apiKeyInput);
        addVerticalGap(context, content, 16);
        content.addView(modelLabel);
        content.addView(modelRow);

        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button saveButton = AiUiTheme.button(context, "Save", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(saveButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Settings", content, actions, null);
        cancelButton.setOnClickListener(view -> dialog.dismiss());
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
                        showModelPicker(context, modelIds, model -> modelInput.setText(model));
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
        saveButton.setOnClickListener(view -> {
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
        });
    }

    private static void showModelFetchError(final android.content.Context context, final String message) {
        final LinearLayout body = verticalLayout(context);
        final TextView detail = AiUiTheme.label(context,
                message == null || message.isEmpty()
                        ? "The Gemini model listing failed. Please try again." : message, 14);
        body.addView(detail);
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(close, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Could Not Fetch Models", body, actions, null);
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private static void showModelPicker(final android.content.Context context, final String[] modelIds,
            final ModelSelectionCallback selection) {
        final LinearLayout body = verticalLayout(context);
        final Dialog[] pickerDialog = new Dialog[1];
        for (String modelId : modelIds) {
            final TextView row = AiUiTheme.label(context, modelId, 15);
            row.setPadding(dp(context, 12), dp(context, 13), dp(context, 12), dp(context, 13));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                    AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                            AiUiTheme.INPUT_BORDER, dp(context, 1)), null));
            row.setClickable(true);
            row.setFocusable(true);
            final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(context, 8));
            body.addView(row, rowParams);
            row.setOnClickListener(view -> {
                selection.onSelected(modelId);
                pickerDialog[0].dismiss();
            });
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        pickerDialog[0] = AiUiTheme.showDialog(context, "Select a Gemini Model", body, actions, null);
        final Dialog dialog = pickerDialog[0];
        cancelButton.setOnClickListener(view -> dialog.dismiss());
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
        answerText.setTextColor(AiUiTheme.PRIMARY_TEXT);
        answerText.setLineSpacing(dp(context, 3), 1f);
        answerText.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        answerText.setBackground(AiUiTheme.inputBackground(context));
        content.addView(answerText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout firstRow = AiUiTheme.actionRow(context);
        final LinearLayout secondRow = AiUiTheme.actionRow(context);
        final Button copyButton = AiUiTheme.button(context, "Copy", AiUiTheme.ButtonKind.SECONDARY);
        final Button appendButton = AiUiTheme.button(
                context, "Append to Note", AiUiTheme.ButtonKind.PRIMARY);
        final Button closeButton = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        firstRow.addView(copyButton, AiUiTheme.weightedButtonParams(context));
        firstRow.addView(appendButton, AiUiTheme.weightedButtonParams(context));
        final File matched = findMatchedFile(matchedFile);
        final Button openButton = matched == null ? null
                : AiUiTheme.button(context, "Open File", AiUiTheme.ButtonKind.SECONDARY);
        if (openButton != null) {
            secondRow.addView(openButton, AiUiTheme.weightedButtonParams(context));
        }
        secondRow.addView(closeButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(firstRow);
        actions.addView(secondRow);
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Answer", content, actions, null);
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
        if (openButton != null) {
            openButton.setOnClickListener(view -> {
                if (openFileCallback != null) {
                    dialog.dismiss();
                    openFileCallback.open(matched);
                }
            });
        }
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
        styleInput(filenameInput, context);
        filenameInput.setText(GeminiMarkdownHelper.sanitizeNoteFilename(requestedFilename));
        final TextView preview = new TextView(context);
        preview.setText(noteContent);
        preview.setTextIsSelectable(true);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextColor(AiUiTheme.PRIMARY_TEXT);
        preview.setTextSize(13);
        preview.setLineSpacing(dp(context, 3), 1f);
        preview.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        preview.setBackground(AiUiTheme.inputBackground(context));
        content.addView(filenameInput);
        addVerticalGap(context, content, 12);
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button createButton = AiUiTheme.button(context, "Create & Open", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(createButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Preview New Note", content, actions, null);
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        createButton.setOnClickListener(view -> {
            try {
                final File created = createNote(filenameInput.getText().toString(), noteContent);
                dialog.dismiss();
                if (openFileCallback != null) {
                    openFileCallback.open(created);
                }
            } catch (IOException exception) {
                showError(exception.getMessage());
            }
        });
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
        final LinearLayout body = verticalLayout(context);
        final LinearLayout toggle = AiUiTheme.actionRow(context);
        final Button diffButton = AiUiTheme.button(context, "Diff View", AiUiTheme.ButtonKind.PRIMARY);
        final Button fullButton = AiUiTheme.button(context, "Full Preview", AiUiTheme.ButtonKind.SECONDARY);
        toggle.addView(diffButton, AiUiTheme.weightedButtonParams(context));
        toggle.addView(fullButton, AiUiTheme.weightedButtonParams(context));
        body.addView(toggle);
        final LinearLayout preview = verticalLayout(context);
        final LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.setMargins(0, dp(context, 12), 0, 0);
        body.addView(preview, previewParams);
        renderDiffPreview(context, preview, original, updated, false);

        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button applyButton = AiUiTheme.button(context, "Apply Changes", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(applyButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Diff Preview", body, actions, null);
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        applyButton.setOnClickListener(view -> {
            editText.getText().replace(0, editText.getText().length(), updated);
            dialog.dismiss();
            onAfterApply.run();
        });
        diffButton.setOnClickListener(view -> {
            AiUiTheme.styleButton(diffButton, AiUiTheme.ButtonKind.PRIMARY);
            AiUiTheme.styleButton(fullButton, AiUiTheme.ButtonKind.SECONDARY);
            renderDiffPreview(context, preview, original, updated, false);
        });
        fullButton.setOnClickListener(view -> {
            AiUiTheme.styleButton(diffButton, AiUiTheme.ButtonKind.SECONDARY);
            AiUiTheme.styleButton(fullButton, AiUiTheme.ButtonKind.PRIMARY);
            renderDiffPreview(context, preview, original, updated, true);
        });
    }

    private void showError(final String message) {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final LinearLayout body = verticalLayout(context);
        final TextView detail = AiUiTheme.label(context,
                message == null || message.isEmpty()
                        ? "The Gemini request failed. Please try again." : message, 14);
        body.addView(detail);
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(close, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Assistant Failed", body, actions, null);
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private static void renderDiffPreview(final android.content.Context context, final LinearLayout preview,
            final String original, final String updated, final boolean fullPreview) {
        preview.removeAllViews();
        if (original.equals(updated)) {
            final TextView unchanged = AiUiTheme.label(context, "(No changes)", 13);
            preview.addView(unchanged);
            return;
        }
        if (fullPreview) {
            final TextView text = AiUiTheme.label(context, updated, 14);
            text.setTypeface(Typeface.MONOSPACE);
            text.setLineSpacing(dp(context, 4), 1f);
            text.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
            text.setBackground(AiUiTheme.inputBackground(context));
            preview.addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        final List<SimpleLineDiff.Line> lines = SimpleLineDiff.diff(original, updated);
        if (lines.isEmpty()) {
            return;
        }
        for (SimpleLineDiff.Line line : lines) {
            final String prefix;
            final int background;
            final int foreground;
            switch (line.getType()) {
                case ADDED:
                    prefix = "+ ";
                    background = AiUiTheme.ADDED_BACKGROUND;
                    foreground = AiUiTheme.ADDED_TEXT;
                    break;
                case REMOVED:
                    prefix = "- ";
                    background = AiUiTheme.REMOVED_BACKGROUND;
                    foreground = AiUiTheme.REMOVED_TEXT;
                    break;
                default:
                    prefix = "  ";
                    background = AiUiTheme.UNCHANGED_BACKGROUND;
                    foreground = AiUiTheme.UNCHANGED_TEXT;
                    break;
            }
            final TextView lineView = new TextView(context);
            lineView.setText(prefix + line.getText());
            lineView.setTextColor(foreground);
            lineView.setTextSize(12.5f);
            lineView.setTypeface(Typeface.MONOSPACE);
            lineView.setLineSpacing(dp(context, 4), 1f);
            lineView.setIncludeFontPadding(true);
            lineView.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            lineView.setBackground(AiUiTheme.rounded(background, dp(context, 3), background, 0));
            final LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lineParams.setMargins(0, 0, 0, dp(context, 2));
            preview.addView(lineView, lineParams);
        }
    }

    private static LinearLayout verticalLayout(final android.content.Context context) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static void styleInput(final EditText input, final android.content.Context context) {
        input.setTextColor(AiUiTheme.PRIMARY_TEXT);
        input.setHintTextColor(AiUiTheme.SECONDARY_TEXT);
        input.setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11));
        input.setBackground(AiUiTheme.inputBackground(context));
    }

    private static void addVerticalGap(final android.content.Context context, final LinearLayout parent,
            final int heightDp) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
    }

    private static int dp(final android.content.Context context, final int value) {
        return AiUiTheme.dp(context, value);
    }
}
