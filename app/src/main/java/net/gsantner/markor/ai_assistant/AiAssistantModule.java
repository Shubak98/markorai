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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    private interface ImageResultCallback {
        void onSelected(String base64);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Fragment fragment;
    private final ActivityResultLauncher<Intent> voiceLauncher;
    private final ActivityResultLauncher<String> imageLauncher;
    private final AiSettingsStore settingsStore;
    private final AiDiffHistoryStore diffHistoryStore;
    private EditText activeInstructionInput;
    private View floatingButton;
    private Runnable onAfterApply = () -> { };
    private boolean isViewModeVisible;
    private File currentFile;
    private ScrollCallback scrollCallback;
    private OpenFileCallback openFileCallback;
    private ImageResultCallback pendingImageCallback;

    public AiAssistantModule(final Fragment fragment) {
        this.fragment = fragment;
        settingsStore = new AiSettingsStore(fragment.requireContext());
        diffHistoryStore = new AiDiffHistoryStore(fragment.requireContext());
        voiceLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onVoiceResult);
        imageLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImageSelected);
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
        final String[] attachedImage = {null};
        final TextView imageChip = AiUiTheme.label(context, "📷 Image attached  ✕", 12);
        imageChip.setTextColor(AiUiTheme.ACCENT);
        imageChip.setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 6));
        imageChip.setBackground(AiUiTheme.rounded(AiUiTheme.SURFACE,
                dp(context, 16), AiUiTheme.ACCENT, dp(context, 1)));
        imageChip.setVisibility(View.GONE);
        content.addView(imageChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        imageChip.setOnClickListener(view -> {
            attachedImage[0] = null;
            imageChip.setVisibility(View.GONE);
        });
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
        settingsButton.setContentDescription("Settings");
        final TextView allDiffsButton = createAllDiffsButton(context);
        final LinearLayout headerActions = AiUiTheme.actionRow(context);
        final LinearLayout.LayoutParams allDiffsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
        allDiffsParams.setMargins(0, 0, dp(context, 6), 0);
        headerActions.addView(allDiffsButton, allDiffsParams);
        headerActions.addView(settingsButton, new LinearLayout.LayoutParams(
                dp(context, 40), dp(context, 40)));
        IOException historyReadError = null;
        try {
            updateAllDiffsButton(allDiffsButton);
        } catch (IOException exception) {
            historyReadError = exception;
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button voiceButton = AiUiTheme.button(context, "Mic", AiUiTheme.ButtonKind.MIC);
        final Button imageButton = AiUiTheme.button(context, "📷 Image", AiUiTheme.ButtonKind.MIC);
        final Button runButton = AiUiTheme.button(context, "Run AI", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        final LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        voiceParams.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        actions.addView(voiceButton, voiceParams);
        final LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imageParams.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        actions.addView(imageButton, imageParams);
        actions.addView(runButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Assistant", content, actions, headerActions);
        if (historyReadError != null) {
            showError(historyReadError.getMessage());
        }
        voiceButton.setOnClickListener(view -> startVoiceInput());
        imageButton.setOnClickListener(view -> pickImage(base64 -> {
            attachedImage[0] = base64;
            imageChip.setVisibility(View.VISIBLE);
        }));
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        allDiffsButton.setOnClickListener(view -> showAllDiffsDialog(context, editText, allDiffsButton));
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (activeInstructionInput == instructions) {
                activeInstructionInput = null;
            }
        });
        runButton.setOnClickListener(view -> {
            final String instruction = instructions.getText().toString().trim();
            final String requestedMode = selectedMode[0];
            final boolean canStartWithoutInstruction = "tutor".equals(requestedMode)
                    || "quiz".equals(requestedMode) || "anki".equals(requestedMode)
                    || "diagram".equals(requestedMode);
            if (instruction.isEmpty() && !canStartWithoutInstruction) {
                Toast.makeText(context, "Enter an instruction before running the assistant.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            final String originalMarkdown = editText.getText().toString();
            if ("tutor".equals(requestedMode)) {
                dialog.dismiss();
                showTutorChat(editText, originalMarkdown, attachedImage[0]);
                return;
            }
            runButton.setEnabled(false);
            cancelButton.setEnabled(false);
            voiceButton.setEnabled(false);
            imageButton.setEnabled(false);
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
                    if ("quiz".equals(requestedMode)) {
                        final String rawQuiz = new AiProviderRouter(settingsStore).generateStudyAnswer(
                                originalMarkdown, "Create 5 to 10 multiple-choice questions from the note. "
                                        + "Return strict JSON only: {\"questions\":[{\"question\":\"...\","
                                        + "\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctIndex\":0,"
                                        + "\"explanation\":\"...\"}]}. User request: " + instruction,
                                attachedImage[0]);
                        final List<AiStudyDialogs.Question> questions = AiStudyDialogs.parseQuiz(rawQuiz);
                        MAIN_HANDLER.post(() -> {
                            dialog.dismiss();
                            if (fragment.getContext() != null) {
                                AiStudyDialogs.showQuiz(context, questions,
                                        text -> appendToNotePreview(editText, originalMarkdown, text,
                                                instruction, "quiz", ""));
                            }
                        });
                        return;
                    }
                    if ("anki".equals(requestedMode)) {
                        final String rawCards = new AiProviderRouter(settingsStore).generateStudyAnswer(
                                originalMarkdown, "Create useful Anki flashcards from this note. Return "
                                        + "strict JSON only: {\"cards\":[{\"front\":\"...\",\"back\":\"...\"}]}. "
                                        + "User request: " + instruction, attachedImage[0]);
                        final List<AnkiCsvHelper.Card> cards = AnkiCsvHelper.parseCards(rawCards);
                        final String csv = AnkiCsvHelper.toCsv(cards);
                        MAIN_HANDLER.post(() -> {
                            dialog.dismiss();
                            if (fragment.getContext() != null) {
                                showAnkiPreview(context, cards, csv);
                            }
                        });
                        return;
                    }
                    if ("diagram".equals(requestedMode)) {
                        final String diagram = new AiProviderRouter(settingsStore).generateStudyAnswer(
                                originalMarkdown, "Create one valid Mermaid flowchart or mindmap that "
                                        + "summarizes the note. Use simple alphanumeric node IDs and safe "
                                        + "labels without quotes, brackets, parentheses, or unescaped "
                                        + "special characters. Return only a ```mermaid fenced block. "
                                        + "User request: " + instruction, attachedImage[0]);
                        final String markdownDiagram = formatMermaid(diagram);
                        MAIN_HANDLER.post(() -> {
                            dialog.dismiss();
                            if (fragment.getContext() != null) {
                                appendToNotePreview(editText, originalMarkdown,
                                        "\n\n" + markdownDiagram, instruction, "diagram", "");
                            }
                        });
                        return;
                    }
                    final boolean searchFolder = "folder".equals(requestedMode)
                            || ("auto".equals(requestedMode)
                            && FolderSearchHelper.isFolderSearchQuery(instruction));
                    final FolderSearchHelper.SearchResult folderResults = searchFolder
                            ? FolderSearchHelper.search(currentFile.getParentFile(), instruction) : null;
                    final String folderContext = folderResults == null ? ""
                            : folderResults.getContext().isEmpty()
                            ? "No relevant .md or .txt files matched the query in this folder."
                            : folderResults.getContext();
                    final boolean hasImage = attachedImage[0] != null;
                    final String effectiveInstruction = hasImage
                            ? "Transcribe the attached page or handwriting into clean structured Markdown. "
                                    + "Return it as an answer that can be reviewed and appended to the note.\n\n"
                                    + instruction : instruction;
                    final String effectiveMode = hasImage ? "study" : requestedMode;
                    final AiProviderRouter.Result providerResult = new AiProviderRouter(settingsStore)
                            .generateIntent(originalMarkdown, effectiveInstruction, effectiveMode,
                                    folderContext, attachedImage[0]);
                    final GeminiMarkdownHelper.IntentResult result = providerResult.getIntentResult();
                    final String expectedMode = expectedIntentMode(effectiveMode);
                    if (expectedMode != null && !expectedMode.equals(result.getMode())) {
                        throw new IOException("The AI provider did not follow the selected mode. Please try again.");
                    }
                    final String updatedMarkdown = "edit".equals(result.getMode())
                            ? GeminiMarkdownHelper.applyEdits(originalMarkdown, result.getEditsJson())
                            : null;
                    MAIN_HANDLER.post(() -> {
                        dialog.dismiss();
                        if (fragment.getContext() != null) {
                            if (!providerResult.getFailoverNotice().isEmpty()) {
                                Toast.makeText(context, providerResult.getFailoverNotice(),
                                        Toast.LENGTH_SHORT).show();
                            }
                            handleIntentResult(editText, originalMarkdown, result, updatedMarkdown,
                                    folderResults, effectiveInstruction, providerResult.getUsedProviderId(),
                                    providerResult.getUsedModelName());
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
                        imageButton.setEnabled(true);
                        settingsButton.setEnabled(true);
                        instructions.setEnabled(true);
                        runButton.setEnabled(true);
                        showError(exception.getMessage());
                    });
                }
            });
        });
        if (!settingsStore.hasConfiguredProviders()) {
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
                {"jump", "Jump"}, {"folder", "Folder"}, {"new_note", "New Note"},
                {"tutor", "Tutor Chat"}, {"quiz", "MCQ Quiz"},
                {"anki", "Anki CSV"}, {"diagram", "Diagram"}
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

    private TextView createAllDiffsButton(final android.content.Context context) {
        final TextView button = new TextView(context);
        button.setText("All Diffs");
        button.setTextColor(AiUiTheme.ACCENT);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                AiUiTheme.rounded(AiUiTheme.SECONDARY, dp(context, 18),
                        AiUiTheme.SECONDARY_BORDER, dp(context, 1)), null));
        button.setContentDescription("All Diffs");
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void updateAllDiffsButton(final TextView button) throws IOException {
        final int count = diffHistoryStore.getHistory(currentFile).size();
        button.setText(count == 0 ? "All Diffs" : "All Diffs (" + count + ")");
        button.setContentDescription("All Diffs, " + count + " saved edits");
    }

    private void showAllDiffsDialog(final android.content.Context context, final EditText editText,
            final TextView allDiffsButton) {
        final List<AiDiffHistoryStore.Entry> history;
        try {
            history = diffHistoryStore.getHistory(currentFile);
        } catch (IOException exception) {
            showError(exception.getMessage());
            return;
        }

        final LinearLayout body = verticalLayout(context);
        final Dialog[] historyDialog = new Dialog[1];
        if (history.isEmpty()) {
            final TextView emptyState = AiUiTheme.label(context,
                    "No edits have been saved for this note yet.", 14);
            emptyState.setTextColor(AiUiTheme.SECONDARY_TEXT);
            emptyState.setGravity(Gravity.CENTER);
            emptyState.setPadding(dp(context, 12), dp(context, 36), dp(context, 12), dp(context, 36));
            body.addView(emptyState, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (AiDiffHistoryStore.Entry entry : history) {
                final LinearLayout card = createHistoryCard(context, entry);
                final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 0, 0, dp(context, 10));
                body.addView(card, cardParams);
                card.setOnClickListener(view -> showDiffDetailDialog(
                        context, entry, editText, historyDialog[0]));
            }
        }

        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button clearButton = AiUiTheme.button(context, "Clear History", AiUiTheme.ButtonKind.SECONDARY);
        clearButton.setTextColor(AiUiTheme.ACCENT);
        clearButton.setEnabled(!history.isEmpty());
        final Button closeButton = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(clearButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(closeButton, AiUiTheme.weightedButtonParams(context));
        historyDialog[0] = AiUiTheme.showDialog(context, "All Diffs", body, actions, null);
        closeButton.setOnClickListener(view -> historyDialog[0].dismiss());
        clearButton.setOnClickListener(view ->
                showClearHistoryConfirmation(context, editText, historyDialog[0], allDiffsButton));
    }

    private LinearLayout createHistoryCard(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry) {
        final LinearLayout card = verticalLayout(context);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setElevation(dp(context, 2));
        final GradientDrawable cardShape = AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                AiUiTheme.INPUT_BORDER, dp(context, 1));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED), cardShape, null));
        card.setClickable(true);
        card.setFocusable(true);

        final LinearLayout titleRow = AiUiTheme.actionRow(context);
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        final TextView date = AiUiTheme.label(context,
                dateFormat.format(new Date(entry.getTimestampMs())), 12);
        date.setTextColor(AiUiTheme.SECONDARY_TEXT);
        titleRow.addView(date, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView provider = AiUiTheme.label(context, displayProvider(entry.getProvider()), 11);
        provider.setTextColor(AiUiTheme.ACCENT);
        provider.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        provider.setBackground(AiUiTheme.rounded(Color.rgb(36, 19, 23), dp(context, 12),
                AiUiTheme.SECONDARY_BORDER, dp(context, 1)));
        titleRow.addView(provider);
        card.addView(titleRow);

        final TextView instruction = AiUiTheme.label(context,
                entry.getInstruction().isEmpty() ? "No instruction recorded" : entry.getInstruction(), 14);
        instruction.setMaxLines(2);
        instruction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionParams.setMargins(0, dp(context, 8), 0, 0);
        card.addView(instruction, instructionParams);

        if (!entry.getModel().isEmpty()) {
            final TextView model = AiUiTheme.label(context, entry.getModel(), 11);
            model.setTextColor(AiUiTheme.SECONDARY_TEXT);
            final LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            modelParams.setMargins(0, dp(context, 4), 0, 0);
            card.addView(model, modelParams);
        }

        final LinearLayout counts = AiUiTheme.actionRow(context);
        final TextView added = AiUiTheme.label(context, "+" + entry.getAddedCount(), 12);
        added.setTextColor(AiUiTheme.ADDED_TEXT);
        added.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        final TextView removed = AiUiTheme.label(context, "-" + entry.getRemovedCount(), 12);
        removed.setTextColor(AiUiTheme.REMOVED_TEXT);
        removed.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        counts.addView(added);
        final LinearLayout.LayoutParams removedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removedParams.setMargins(dp(context, 12), 0, 0, 0);
        counts.addView(removed, removedParams);
        final LinearLayout.LayoutParams countsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countsParams.setMargins(0, dp(context, 8), 0, 0);
        card.addView(counts, countsParams);
        return card;
    }

    private void showDiffDetailDialog(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry, final EditText editText, final Dialog historyDialog) {
        final LinearLayout body = verticalLayout(context);
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        final TextView timestamp = AiUiTheme.label(context,
                dateFormat.format(new Date(entry.getTimestampMs())), 12);
        timestamp.setTextColor(AiUiTheme.SECONDARY_TEXT);
        body.addView(timestamp);

        final TextView instruction = AiUiTheme.label(context,
                entry.getInstruction().isEmpty() ? "No instruction recorded" : entry.getInstruction(), 15);
        final LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionParams.setMargins(0, dp(context, 8), 0, dp(context, 12));
        body.addView(instruction, instructionParams);

        final LinearLayout diffCard = verticalLayout(context);
        diffCard.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        diffCard.setBackground(AiUiTheme.inputBackground(context));
        renderFormattedDiff(context, diffCard, entry.getDiffText());
        body.addView(diffCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout footer = verticalLayout(context);
        final Button copyButton = AiUiTheme.button(context, "Copy Diff", AiUiTheme.ButtonKind.SECONDARY);
        final Button restoreButton = AiUiTheme.button(
                context, "Restore Before This Edit", AiUiTheme.ButtonKind.PRIMARY);
        final Button backButton = AiUiTheme.button(context, "Back", AiUiTheme.ButtonKind.SECONDARY);
        addFooterButton(context, footer, copyButton);
        addFooterButton(context, footer, restoreButton);
        addFooterButton(context, footer, backButton);
        final Dialog detailDialog = AiUiTheme.showDialog(context, "Diff Detail", body, footer, null);
        copyButton.setOnClickListener(view -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showError("Could not access the clipboard.");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AI Edit Diff", entry.getDiffText()));
            Toast.makeText(context, "Diff copied.", Toast.LENGTH_SHORT).show();
        });
        restoreButton.setOnClickListener(view ->
                showRestoreConfirmation(context, entry, editText, detailDialog, historyDialog));
        backButton.setOnClickListener(view -> detailDialog.dismiss());
    }

    private void showRestoreConfirmation(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry, final EditText editText, final Dialog detailDialog,
            final Dialog historyDialog) {
        final LinearLayout body = verticalLayout(context);
        body.addView(AiUiTheme.label(context,
                "Replace the current note with the snapshot from before this edit?", 14));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button restoreButton = AiUiTheme.button(context, "Restore", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(restoreButton, AiUiTheme.weightedButtonParams(context));
        final Dialog confirmation = AiUiTheme.showDialog(context, "Restore Note", body, actions, null);
        cancelButton.setOnClickListener(view -> confirmation.dismiss());
        restoreButton.setOnClickListener(view -> {
            editText.getText().replace(0, editText.getText().length(), entry.getBeforeSnapshot());
            confirmation.dismiss();
            detailDialog.dismiss();
            historyDialog.dismiss();
            onAfterApply.run();
            Toast.makeText(context, "Note restored.", Toast.LENGTH_SHORT).show();
        });
    }

    private void showClearHistoryConfirmation(final android.content.Context context, final EditText editText,
            final Dialog historyDialog, final TextView allDiffsButton) {
        final LinearLayout body = verticalLayout(context);
        body.addView(AiUiTheme.label(context,
                "Permanently clear all saved edit history for this note?", 14));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button clearButton = AiUiTheme.button(context, "Clear History", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(clearButton, AiUiTheme.weightedButtonParams(context));
        final Dialog confirmation = AiUiTheme.showDialog(context, "Clear Edit History", body, actions, null);
        cancelButton.setOnClickListener(view -> confirmation.dismiss());
        clearButton.setOnClickListener(view -> {
            try {
                diffHistoryStore.clearHistory(currentFile);
                updateAllDiffsButton(allDiffsButton);
            } catch (IOException exception) {
                confirmation.dismiss();
                showError(exception.getMessage());
                return;
            }
            confirmation.dismiss();
            historyDialog.dismiss();
            showAllDiffsDialog(context, editText, allDiffsButton);
        });
    }

    private static String displayProvider(final String providerId) {
        final AiProviderRouter.Provider provider = AiProviderRouter.Provider.fromId(providerId);
        return provider == null ? providerId : provider.getDisplayName();
    }

    private static void addFooterButton(final android.content.Context context, final LinearLayout footer,
            final Button button) {
        final LinearLayout row = AiUiTheme.actionRow(context);
        row.addView(button, AiUiTheme.weightedButtonParams(context));
        final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(context, 6));
        footer.addView(row, rowParams);
    }

    private void handleIntentResult(final EditText editText, final String originalMarkdown,
            final GeminiMarkdownHelper.IntentResult result, final String updatedMarkdown,
            final FolderSearchHelper.SearchResult folderResults, final String instruction,
            final String provider, final String model) {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        if ("edit".equals(result.getMode())) {
            showDiffPreview(editText, originalMarkdown, updatedMarkdown, instruction, provider, model);
        } else if ("answer".equals(result.getMode())) {
            final String matchedFile = result.getMatchedFile() != null
                    ? result.getMatchedFile()
                    : folderResults == null ? null : folderResults.getBestMatch();
            showAnswerDialog(editText, originalMarkdown, result.getAnswer(), matchedFile,
                    instruction, provider, model);
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
            showError("The AI provider returned an unsupported intent.");
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

    private void pickImage(final ImageResultCallback callback) {
        pendingImageCallback = callback;
        imageLauncher.launch("image/*");
    }

    private void onImageSelected(final android.net.Uri uri) {
        final ImageResultCallback callback = pendingImageCallback;
        pendingImageCallback = null;
        if (uri == null || callback == null || fragment.getContext() == null) {
            return;
        }
        try {
            callback.onSelected(ImageEncodingHelper.encodeJpeg(
                    fragment.requireContext().getContentResolver(), uri));
        } catch (IOException exception) {
            showError(exception.getMessage());
        }
    }

    private void showTutorChat(final EditText editText, final String markdown, final String imageBase64) {
        final android.content.Context context = fragment.getContext();
        if (context == null || currentFile == null) {
            return;
        }
        AiStudyDialogs.showTutorChat(context, currentFile.getAbsolutePath(), markdown,
                (recentTurns, question, image, callback) -> EXECUTOR.execute(() -> {
                    try {
                        final StringBuilder history = new StringBuilder();
                        for (int index = 0; index < recentTurns.size() - 1; index++) {
                            final AiStudyDialogs.Turn turn = recentTurns.get(index);
                            history.append(turn.isUser() ? "Student: " : "Tutor: ")
                                    .append(turn.getText()).append('\n');
                        }
                        final String prompt = (history.length() == 0 ? "" : "Recent conversation:\n"
                                + history + "\n")
                                + "Answer the student's latest question clearly and use the note as context:\n"
                                + question;
                        final String answer = new AiProviderRouter(settingsStore)
                                .generateStudyAnswer(markdown, prompt, image);
                        MAIN_HANDLER.post(() -> callback.onResult(answer, null));
                    } catch (IOException exception) {
                        MAIN_HANDLER.post(() -> callback.onResult(null, exception.getMessage()));
                    }
                }), text -> appendToNotePreview(editText, editText.getText().toString(),
                        "\n\n" + text, "Append tutor response", "tutor", ""),
                input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }, imageBase64);
    }

    private void appendToNotePreview(final EditText editText, final String original,
            final String content, final String instruction, final String provider, final String model) {
        try {
            final String patch = new JSONObject().put("edits", new JSONArray().put(new JSONObject()
                    .put("action", "append_end").put("target", "").put("content", content))).toString();
            final String updated = GeminiMarkdownHelper.applyEdits(original, patch);
            showDiffPreview(editText, original, updated, instruction, provider, model);
        } catch (org.json.JSONException | IOException exception) {
            showError(exception.getMessage());
        }
    }

    private static String formatMermaid(final String response) throws IOException {
        if (response == null || response.trim().isEmpty()) {
            throw new IOException("The AI provider returned an empty diagram.");
        }
        final String trimmed = response.trim();
        if (trimmed.startsWith("```mermaid") && trimmed.endsWith("```")) {
            return trimmed;
        }
        final String content = GeminiMarkdownHelper.stripMarkdownFences(trimmed).trim();
        if (content.startsWith("flowchart ") || content.startsWith("graph ")
                || content.startsWith("mindmap")) {
            return "```mermaid\n" + content + "\n```";
        }
        throw new IOException("The AI provider did not return a Mermaid flowchart or mind-map.");
    }

    private void showAnkiPreview(final android.content.Context context,
            final List<AnkiCsvHelper.Card> cards, final String csv) {
        final LinearLayout body = verticalLayout(context);
        body.addView(AiUiTheme.label(context, cards.size() + " cards", 14));
        for (int index = 0; index < Math.min(cards.size(), 5); index++) {
            final AnkiCsvHelper.Card card = cards.get(index);
            final LinearLayout item = verticalLayout(context);
            item.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            item.setBackground(AiUiTheme.inputBackground(context));
            item.addView(AiUiTheme.label(context, "Front: " + card.getFront(), 13));
            final TextView back = AiUiTheme.label(context, "Back: " + card.getBack(), 13);
            back.setTextColor(AiUiTheme.SECONDARY_TEXT);
            item.addView(back);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(context, 8), 0, 0);
            body.addView(item, params);
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button copy = AiUiTheme.button(context, "Copy CSV", AiUiTheme.ButtonKind.SECONDARY);
        final Button save = AiUiTheme.button(context, "Save .csv", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(copy, AiUiTheme.weightedButtonParams(context));
        actions.addView(save, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Anki CSV Preview", body, actions, null);
        copy.setOnClickListener(view -> {
            final android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showError("Could not access the clipboard.");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Anki CSV", csv));
            Toast.makeText(context, "CSV copied.", Toast.LENGTH_SHORT).show();
        });
        save.setOnClickListener(view -> {
            try {
                final File file = saveAnkiCsv(csv);
                dialog.dismiss();
                Toast.makeText(context, "Saved " + file.getName(), Toast.LENGTH_SHORT).show();
            } catch (IOException exception) {
                showError(exception.getMessage());
            }
        });
    }

    private File saveAnkiCsv(final String csv) throws IOException {
        final File parent = currentFile == null ? null : currentFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The current note's folder is not available.");
        }
        String base = currentFile.getName().replaceFirst("(?i)\\.md$", "")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "note";
        }
        for (int suffix = 0; ; suffix++) {
            final File file = new File(parent, base + "_anki"
                    + (suffix == 0 ? "" : "_" + suffix) + ".csv");
            if (!file.createNewFile()) {
                continue;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(csv.getBytes(StandardCharsets.UTF_8));
                return file;
            } catch (IOException exception) {
                if (!file.delete()) {
                    exception.addSuppressed(new IOException("Could not remove incomplete CSV: "
                            + file.getAbsolutePath()));
                }
                throw exception;
            }
        }
    }

    private void showSettingsDialog() {
        final android.content.Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final LinearLayout content = verticalLayout(context);
        final TextView providerLabel = AiUiTheme.label(context, "Primary Provider", 13);
        providerLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        content.addView(providerLabel);
        final HorizontalScrollView providerScroll = new HorizontalScrollView(context);
        providerScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout providerRow = AiUiTheme.actionRow(context);
        providerScroll.addView(providerRow);
        content.addView(providerScroll);

        final CheckBox autoFailover = new CheckBox(context);
        autoFailover.setText("Auto-Failover on 429 / errors (including timeouts)");
        autoFailover.setTextColor(AiUiTheme.PRIMARY_TEXT);
        autoFailover.setButtonTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        autoFailover.setChecked(settingsStore.isAutoFailoverEnabled());
        content.addView(autoFailover);
        addVerticalGap(context, content, 10);

        final AiProviderRouter.Provider[] providers = AiProviderRouter.Provider.values();
        final ProviderForm[] forms = new ProviderForm[providers.length];
        final TextView[] providerChips = new TextView[providers.length];
        final LinearLayout formsContainer = verticalLayout(context);
        final String[] selectedProvider = {settingsStore.getPrimaryProvider()};
        for (int index = 0; index < providers.length; index++) {
            final AiProviderRouter.Provider provider = providers[index];
            final ProviderForm form = createProviderForm(context, provider);
            forms[index] = form;
            formsContainer.addView(form.container);
            final TextView chip = new TextView(context);
            providerChips[index] = chip;
            chip.setText(providerChipText(provider,
                    !settingsStore.getProviderApiKey(provider).isEmpty()));
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            AiUiTheme.styleChip(chip, provider.getId().equals(selectedProvider[0]));
            final LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.setMargins(0, dp(context, 7), dp(context, 7), dp(context, 9));
            providerRow.addView(chip, chipParams);
            chip.setOnClickListener(view -> {
                selectedProvider[0] = provider.getId();
                updateProviderSelection(providers, forms, providerChips, selectedProvider[0]);
            });
        }
        content.addView(formsContainer);
        updateProviderSelection(providers, forms, providerChips, selectedProvider[0]);

        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button saveButton = AiUiTheme.button(context, "Save", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(saveButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Settings", content, actions, null);
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        for (int index = 0; index < providers.length; index++) {
            final AiProviderRouter.Provider provider = providers[index];
            final ProviderForm form = forms[index];
            form.fetchModels.setOnClickListener(view -> fetchModels(context, dialog, provider, form));
        }
        saveButton.setOnClickListener(view -> {
            for (int index = 0; index < providers.length; index++) {
                final ProviderForm form = forms[index];
                settingsStore.saveProvider(providers[index], form.apiKey.getText().toString(),
                        form.model.getText().toString(),
                        form.baseUrl == null ? "" : form.baseUrl.getText().toString());
            }
            settingsStore.savePreferences(selectedProvider[0], autoFailover.isChecked());
            dialog.dismiss();
        });
    }

    private ProviderForm createProviderForm(final android.content.Context context,
            final AiProviderRouter.Provider provider) {
        final LinearLayout container = verticalLayout(context);
        final EditText baseUrl = provider == AiProviderRouter.Provider.CUSTOM
                ? new EditText(context) : null;
        if (baseUrl != null) {
            addSettingsLabel(context, container, "Base URL");
            baseUrl.setSingleLine(true);
            baseUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            baseUrl.setHint("https://api.example.com/v1");
            styleInput(baseUrl, context);
            baseUrl.setText(settingsStore.getProviderBaseUrl(provider));
            container.addView(baseUrl);
            addVerticalGap(context, container, 10);
        }

        addSettingsLabel(context, container, "API Key");
        final EditText apiKey = new EditText(context);
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint("Paste your " + provider.getDisplayName() + " API key");
        styleInput(apiKey, context);
        apiKey.setText(settingsStore.getProviderApiKey(provider));
        container.addView(apiKey);
        addVerticalGap(context, container, 10);

        addSettingsLabel(context, container, "Model ID");
        final EditText model = new EditText(context);
        model.setSingleLine(true);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setHint(provider.getDefaultModel().isEmpty() ? "Enter a model ID" : provider.getDefaultModel());
        styleInput(model, context);
        model.setText(settingsStore.getProviderModel(provider));
        final LinearLayout modelRow = new LinearLayout(context);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.addView(model, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button fetchModels = AiUiTheme.button(context, "Fetch Models", AiUiTheme.ButtonKind.SECONDARY);
        modelRow.addView(fetchModels, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        final LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(context, 24), dp(context, 24));
        progressParams.gravity = Gravity.CENTER_VERTICAL;
        progressParams.setMargins(dp(context, 8), 0, 0, 0);
        modelRow.addView(progress, progressParams);
        container.addView(modelRow);
        return new ProviderForm(container, baseUrl, apiKey, model, fetchModels, progress);
    }

    private void fetchModels(final android.content.Context context, final Dialog settingsDialog,
            final AiProviderRouter.Provider provider, final ProviderForm form) {
        final String apiKey = form.apiKey.getText().toString().trim();
        if (apiKey.isEmpty()) {
            showModelFetchError(context, "Enter your " + provider.getDisplayName()
                    + " API key before fetching models.");
            return;
        }
        final String baseUrl = form.baseUrl == null
                ? provider.getDefaultBaseUrl() : form.baseUrl.getText().toString().trim();
        form.fetchModels.setEnabled(false);
        form.progress.setVisibility(View.VISIBLE);
        EXECUTOR.execute(() -> {
            try {
                final List<String> models = provider == AiProviderRouter.Provider.GEMINI
                        ? GeminiMarkdownHelper.fetchAvailableModels(apiKey)
                        : OpenAiCompatibleHelper.fetchAvailableModels(provider.getDisplayName(), baseUrl, apiKey);
                MAIN_HANDLER.post(() -> {
                    if (!settingsDialog.isShowing() || fragment.getContext() == null) {
                        return;
                    }
                    form.progress.setVisibility(View.GONE);
                    form.fetchModels.setEnabled(true);
                    if (models.isEmpty()) {
                        showModelFetchError(context, "No available models were returned by "
                                + provider.getDisplayName() + ".");
                        return;
                    }
                    showModelPicker(context, models.toArray(new String[0]), form.model::setText);
                });
            } catch (IOException exception) {
                MAIN_HANDLER.post(() -> {
                    if (!settingsDialog.isShowing() || fragment.getContext() == null) {
                        return;
                    }
                    form.progress.setVisibility(View.GONE);
                    form.fetchModels.setEnabled(true);
                    showModelFetchError(context, exception.getMessage());
                });
            }
        });
    }

    private static void addSettingsLabel(final android.content.Context context, final LinearLayout parent,
            final String text) {
        final TextView label = AiUiTheme.label(context, text, 13);
        label.setTextColor(AiUiTheme.SECONDARY_TEXT);
        parent.addView(label);
    }

    private static CharSequence providerChipText(final AiProviderRouter.Provider provider,
            final boolean configured) {
        final String value = provider.getDisplayName() + "  ●";
        final SpannableString text = new SpannableString(value);
        text.setSpan(new ForegroundColorSpan(configured
                ? Color.rgb(110, 231, 183) : AiUiTheme.SECONDARY_TEXT),
                value.length() - 1, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static void updateProviderSelection(final AiProviderRouter.Provider[] providers,
            final ProviderForm[] forms, final TextView[] chips, final String selectedProvider) {
        for (int index = 0; index < providers.length; index++) {
            final boolean selected = providers[index].getId().equals(selectedProvider);
            AiUiTheme.styleChip(chips[index], selected);
            forms[index].container.setVisibility(selected ? View.VISIBLE : View.GONE);
        }
    }

    private static final class ProviderForm {
        private final LinearLayout container;
        private final EditText baseUrl;
        private final EditText apiKey;
        private final EditText model;
        private final Button fetchModels;
        private final ProgressBar progress;

        private ProviderForm(final LinearLayout container, final EditText baseUrl, final EditText apiKey,
                final EditText model, final Button fetchModels, final ProgressBar progress) {
            this.container = container;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.fetchModels = fetchModels;
            this.progress = progress;
        }
    }

    private static void showModelFetchError(final android.content.Context context, final String message) {
        final LinearLayout body = verticalLayout(context);
        final TextView detail = AiUiTheme.label(context,
                message == null || message.isEmpty()
                        ? "The provider model listing failed. Please try again." : message, 14);
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
            final String answer, final String matchedFile, final String instruction,
            final String provider, final String model) {
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
                showDiffPreview(editText, originalMarkdown, updated, instruction, provider, model);
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

    private void showDiffPreview(final EditText editText, final String original, final String updated,
            final String instruction, final String provider, final String model) {
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
            final String beforeApplying = editText.getText().toString();
            if (!original.equals(beforeApplying)) {
                showError("The note changed after this diff was prepared. Run the assistant again.");
                return;
            }
            try {
                diffHistoryStore.addEntry(
                        currentFile, instruction, provider, model, beforeApplying, updated);
            } catch (IOException exception) {
                showError("Could not save this edit to history: " + exception.getMessage());
                return;
            }
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
            switch (line.getType()) {
                case ADDED:
                    addDiffLine(context, preview, "+ " + line.getText(),
                            AiUiTheme.ADDED_BACKGROUND, AiUiTheme.ADDED_TEXT);
                    break;
                case REMOVED:
                    addDiffLine(context, preview, "- " + line.getText(),
                            AiUiTheme.REMOVED_BACKGROUND, AiUiTheme.REMOVED_TEXT);
                    break;
                default:
                    addDiffLine(context, preview, "  " + line.getText(),
                            AiUiTheme.UNCHANGED_BACKGROUND, AiUiTheme.UNCHANGED_TEXT);
                    break;
            }
        }
    }

    private static void renderFormattedDiff(final android.content.Context context,
            final LinearLayout preview, final String diffText) {
        preview.removeAllViews();
        if (diffText == null || diffText.isEmpty()) {
            preview.addView(AiUiTheme.label(context, "(No diff available)", 13));
            return;
        }
        final String[] lines = diffText.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            final String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            if (line.startsWith("+ ")) {
                addDiffLine(context, preview, line, AiUiTheme.ADDED_BACKGROUND, AiUiTheme.ADDED_TEXT);
            } else if (line.startsWith("- ")) {
                addDiffLine(context, preview, line, AiUiTheme.REMOVED_BACKGROUND, AiUiTheme.REMOVED_TEXT);
            } else {
                addDiffLine(context, preview, line, AiUiTheme.UNCHANGED_BACKGROUND,
                        AiUiTheme.UNCHANGED_TEXT);
            }
        }
    }

    private static void addDiffLine(final android.content.Context context, final LinearLayout preview,
            final String text, final int background, final int foreground) {
        final TextView lineView = new TextView(context);
        lineView.setText(text);
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
