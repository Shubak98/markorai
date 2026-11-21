package net.gsantner.markor.ai_assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AiStudyDialogs {
    public interface TutorRequest {
        void send(List<Turn> recentTurns, String question, String imageBase64, ResultCallback callback);
    }

    public interface ResultCallback {
        void onResult(String result, String error);
    }

    public interface AppendCallback {
        void append(String content);
    }

    public interface MicCallback {
        void start(EditText input);
    }

    private static final Map<String, List<Turn>> CHAT_TURNS = new HashMap<>();

    private AiStudyDialogs() {
    }

    public static void showTutorChat(final Context context, final String fileKey,
            final String markdown, final TutorRequest request, final AppendCallback append,
            final MicCallback mic, final String imageBase64) {
        final LinearLayout chat = new LinearLayout(context);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setTag(append);
        final List<Turn> turns = getTurns(fileKey);
        final String[] pendingImage = {imageBase64};
        for (Turn turn : turns) {
            addBubble(context, chat, turn);
        }

        final EditText input = new EditText(context);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setHint("Ask a follow-up question");
        input.setTextColor(AiUiTheme.PRIMARY_TEXT);
        input.setHintTextColor(AiUiTheme.SECONDARY_TEXT);
        input.setPadding(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 9),
                AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 9));
        input.setBackground(AiUiTheme.inputBackground(context));
        final Button micButton = AiUiTheme.button(context, "Mic", AiUiTheme.ButtonKind.MIC);
        final Button sendButton = AiUiTheme.button(context, "Send", AiUiTheme.ButtonKind.PRIMARY);
        final LinearLayout footer = AiUiTheme.actionRow(context);
        footer.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        footer.addView(micButton);
        footer.addView(sendButton);
        final TextView newChat = AiUiTheme.headerAction(context, "New Chat");
        newChat.setTextSize(13);
        final android.app.Dialog dialog = AiUiTheme.showDialog(context, "Tutor Chat",
                chat, footer, newChat);

        newChat.setOnClickListener(view -> {
            turns.clear();
            chat.removeAllViews();
            input.setText("");
        });
        micButton.setOnClickListener(view -> mic.start(input));
        sendButton.setOnClickListener(view -> {
            final String question = input.getText().toString().trim();
            if (question.isEmpty()) {
                return;
            }
            final Turn userTurn = new Turn(true, question);
            turns.add(userTurn);
            trimTurns(turns);
            addBubble(context, chat, userTurn);
            input.setText("");
            sendButton.setEnabled(false);
            final String imageForRequest = pendingImage[0];
            pendingImage[0] = null;
            request.send(new ArrayList<>(turns), question, imageForRequest, (answer, error) -> {
                sendButton.setEnabled(true);
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show();
                    return;
                }
                final Turn assistantTurn = new Turn(false, answer);
                turns.add(assistantTurn);
                trimTurns(turns);
                addBubble(context, chat, assistantTurn);
            });
        });
    }

    private static synchronized List<Turn> getTurns(final String fileKey) {
        final String key = fileKey == null ? "" : fileKey;
        List<Turn> turns = CHAT_TURNS.get(key);
        if (turns == null) {
            turns = new ArrayList<>();
            CHAT_TURNS.put(key, turns);
        }
        return turns;
    }

    private static void trimTurns(final List<Turn> turns) {
        while (turns.size() > 12) {
            turns.remove(0);
        }
    }

    private static void addBubble(final Context context, final LinearLayout parent, final Turn turn) {
        final LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 9),
                AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 9));
        bubble.setBackground(AiUiTheme.rounded(turn.user ? Color.rgb(42, 18, 22) : AiUiTheme.INPUT,
                AiUiTheme.dp(context, 14), turn.user ? AiUiTheme.SURFACE_BORDER : AiUiTheme.INPUT_BORDER,
                AiUiTheme.dp(context, 1)));
        final TextView text = AiUiTheme.label(context, turn.text, 14);
        text.setTextIsSelectable(true);
        bubble.addView(text);
        if (!turn.user) {
            final LinearLayout row = AiUiTheme.actionRow(context);
            final TextView copy = AiUiTheme.headerAction(context, "Copy");
            final TextView append = AiUiTheme.headerAction(context, "Append to Note");
            copy.setTextSize(12);
            append.setTextSize(12);
            row.addView(copy);
            row.addView(append);
            bubble.addView(row);
            copy.setOnClickListener(view -> copy(context, turn.text, "Tutor response"));
            append.setOnClickListener(view -> {
                if (parent.getTag() instanceof AppendCallback) {
                    ((AppendCallback) parent.getTag()).append(turn.text);
                }
            });
        }
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = turn.user ? Gravity.RIGHT : Gravity.LEFT;
        params.setMargins(0, 0, 0, AiUiTheme.dp(context, 8));
        bubble.setLayoutParams(params);
        parent.addView(bubble);
    }

    public static void showQuiz(final Context context, final List<Question> questions,
            final AppendCallback saveQuiz) {
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        final TextView score = AiUiTheme.label(context, "Score: 0/" + questions.size(), 14);
        score.setTextColor(AiUiTheme.ACCENT);
        score.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        body.addView(score);
        final int[] correctAnswers = {0};
        for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
            final Question question = questions.get(questionIndex);
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 10),
                    AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 10));
            card.setBackground(AiUiTheme.inputBackground(context));
            final TextView title = AiUiTheme.label(context,
                    (questionIndex + 1) + ". " + question.question, 15);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            card.addView(title);
            final boolean[] answered = {false};
            final Button[] optionButtons = new Button[question.options.size()];
            final TextView explanation = AiUiTheme.label(context, "", 13);
            for (int optionIndex = 0; optionIndex < question.options.size(); optionIndex++) {
                final int selected = optionIndex;
                final Button option = AiUiTheme.button(context, question.options.get(optionIndex),
                        AiUiTheme.ButtonKind.SECONDARY);
                optionButtons[optionIndex] = option;
                final LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                optionParams.setMargins(0, AiUiTheme.dp(context, 5), 0, 0);
                card.addView(option, optionParams);
                option.setOnClickListener(view -> {
                    if (answered[0]) {
                        return;
                    }
                    answered[0] = true;
                    final boolean right = selected == question.correctIndex;
                    if (right) {
                        correctAnswers[0]++;
                    }
                    score.setText("Score: " + correctAnswers[0] + "/" + questions.size());
                    for (int answerIndex = 0; answerIndex < optionButtons.length; answerIndex++) {
                        final boolean isCorrectOption = answerIndex == question.correctIndex;
                        final boolean isWrongSelection = answerIndex == selected && !right;
                        if (isCorrectOption || isWrongSelection) {
                            optionButtons[answerIndex].setBackground(AiUiTheme.rounded(
                                    isCorrectOption ? AiUiTheme.ADDED_BACKGROUND
                                            : AiUiTheme.REMOVED_BACKGROUND,
                                    AiUiTheme.dp(context, 12),
                                    isCorrectOption ? AiUiTheme.ADDED_TEXT
                                            : AiUiTheme.REMOVED_TEXT, AiUiTheme.dp(context, 1)));
                        }
                    }
                    explanation.setText((right ? "Correct. " : "Not quite. ")
                            + "Answer: " + question.options.get(question.correctIndex)
                            + "\n" + question.explanation);
                    explanation.setTextColor(right ? AiUiTheme.ADDED_TEXT : AiUiTheme.REMOVED_TEXT);
                    card.addView(explanation);
                });
            }
            final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, AiUiTheme.dp(context, 10), 0, 0);
            body.addView(card, cardParams);
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button save = AiUiTheme.button(context, "Save Quiz to Note", AiUiTheme.ButtonKind.PRIMARY);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        actions.addView(save, AiUiTheme.weightedButtonParams(context));
        actions.addView(close, AiUiTheme.weightedButtonParams(context));
        final android.app.Dialog dialog = AiUiTheme.showDialog(context, "MCQ Quiz", body, actions, null);
        close.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            final StringBuilder markdown = new StringBuilder("## Practice Quiz\n\n");
            for (int index = 0; index < questions.size(); index++) {
                final Question question = questions.get(index);
                markdown.append(index + 1).append(". ").append(question.question).append('\n');
                for (int option = 0; option < question.options.size(); option++) {
                    markdown.append("- ").append((char) ('A' + option)).append(". ")
                            .append(question.options.get(option)).append('\n');
                }
                markdown.append("**Answer:** ")
                        .append(question.options.get(question.correctIndex))
                        .append("\n\n").append(question.explanation).append("\n\n");
            }
            dialog.dismiss();
            saveQuiz.append(markdown.toString());
        });
    }

    public static List<Question> parseQuiz(final String json) throws IOException {
        final Map<?, ?> object = GeminiMarkdownHelper.parseJsonObject(json);
        final Object value = object.get("questions");
        if (!(value instanceof List<?>) || ((List<?>) value).isEmpty()) {
            throw new IOException("The AI provider returned no quiz questions.");
        }
        final List<Question> questions = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IOException("The AI provider returned an invalid quiz question.");
            }
            final Map<?, ?> question = (Map<?, ?>) item;
            final Object questionText = question.get("question");
            final Object optionsValue = question.get("options");
            final Object correctValue = question.get("correctIndex");
            final Object explanation = question.get("explanation");
            if (!(questionText instanceof String) || ((String) questionText).trim().isEmpty()
                    || !(optionsValue instanceof List<?>) || ((List<?>) optionsValue).size() != 4
                    || !(correctValue instanceof Number) || !(explanation instanceof String)) {
                throw new IOException("The AI provider returned a malformed quiz question.");
            }
            final List<String> options = new ArrayList<>();
            for (Object option : (List<?>) optionsValue) {
                if (!(option instanceof String) || ((String) option).trim().isEmpty()) {
                    throw new IOException("Every quiz question must have four non-empty options.");
                }
                options.add((String) option);
            }
            final double correctNumber = ((Number) correctValue).doubleValue();
            final int correctIndex = (int) correctNumber;
            if (correctNumber != correctIndex || correctIndex < 0 || correctIndex >= options.size()) {
                throw new IOException("The AI provider returned an invalid quiz answer index.");
            }
            questions.add(new Question((String) questionText, options, correctIndex, (String) explanation));
        }
        return questions;
    }

    private static void copy(final Context context, final String text, final String label) {
        final ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(context, "Copied.", Toast.LENGTH_SHORT).show();
        }
    }

    public static final class Turn {
        private final boolean user;
        private final String text;

        private Turn(final boolean user, final String text) {
            this.user = user;
            this.text = text;
        }

        public boolean isUser() {
            return user;
        }

        public String getText() {
            return text;
        }
    }

    public static final class Question {
        private final String question;
        private final List<String> options;
        private final int correctIndex;
        private final String explanation;

        private Question(final String question, final List<String> options, final int correctIndex,
                final String explanation) {
            this.question = question;
            this.options = options;
            this.correctIndex = correctIndex;
            this.explanation = explanation;
        }

        public String getQuestion() {
            return question;
        }

        public List<String> getOptions() {
            return new ArrayList<>(options);
        }

        public int getCorrectIndex() {
            return correctIndex;
        }

        public String getExplanation() {
            return explanation;
        }
    }
}
