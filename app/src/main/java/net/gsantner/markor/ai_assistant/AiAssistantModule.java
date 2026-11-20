package net.gsantner.markor.ai_assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiAssistantModule {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Fragment fragment;
    private final ActivityResultLauncher<Intent> voiceLauncher;
    private final AiSettingsStore settingsStore;
    private EditText activeInstructionInput;

    public AiAssistantModule(final Fragment fragment) {
        this.fragment = fragment;
        settingsStore = new AiSettingsStore(fragment.requireContext());
        voiceLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onVoiceResult);
    }

    public void openEditorDialog(final EditText editText) {
        if (fragment.getContext() == null || editText == null) {
            return;
        }
        final android.content.Context context = fragment.requireContext();
        final LinearLayout content = verticalLayout(context);
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final EditText instructions = new EditText(context);
        instructions.setHint("Describe the Markdown edit (Hindi, Hinglish, or English)");
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
        progressLabel.setText("Generating edit...");
        progressLabel.setVisibility(View.GONE);
        progressLabel.setGravity(Gravity.CENTER);
        content.addView(progressLabel);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("AI Edit")
                .setView(content)
                .setPositiveButton("Generate Edit", null)
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
                        Toast.makeText(context, "Enter an instruction before generating an edit.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String originalMarkdown = editText.getText().toString();
                    final String model = settingsStore.getModel();
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    voiceButton.setEnabled(false);
                    settingsButton.setEnabled(false);
                    instructions.setEnabled(false);
                    progress.setVisibility(View.VISIBLE);
                    progressLabel.setVisibility(View.VISIBLE);
                    EXECUTOR.execute(() -> {
                        try {
                            final String updatedMarkdown = GeminiMarkdownHelper.generateMarkdownEdit(
                                    apiKey, model, originalMarkdown, instruction);
                            MAIN_HANDLER.post(() -> {
                                dialog.dismiss();
                                if (fragment.getContext() != null) {
                                    showDiffPreview(editText, originalMarkdown, updatedMarkdown);
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
        content.addView(keyLabel);
        content.addView(apiKeyInput);
        content.addView(modelLabel);
        content.addView(modelInput);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("AI Edit Settings")
                .setView(content)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
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
                    .setTitle("AI Edit Failed")
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
