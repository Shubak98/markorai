package net.gsantner.markor.ai_assistant;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AiUiTheme {
    public static final int SURFACE = Color.rgb(13, 13, 16);
    public static final int SURFACE_BORDER = Color.rgb(46, 22, 27);
    public static final int INPUT = Color.rgb(22, 22, 27);
    public static final int INPUT_BORDER = Color.rgb(42, 42, 50);
    public static final int ACCENT = Color.rgb(229, 57, 53);
    public static final int ACCENT_PRESSED = Color.rgb(183, 28, 28);
    public static final int SECONDARY = Color.rgb(27, 27, 34);
    public static final int SECONDARY_BORDER = Color.rgb(58, 32, 38);
    public static final int PRIMARY_TEXT = Color.rgb(244, 244, 246);
    public static final int SECONDARY_TEXT = Color.rgb(154, 154, 166);
    public static final int DIVIDER = Color.rgb(36, 36, 44);
    public static final int ADDED_BACKGROUND = Color.rgb(16, 42, 30);
    public static final int ADDED_TEXT = Color.rgb(110, 231, 183);
    public static final int REMOVED_BACKGROUND = Color.rgb(50, 18, 24);
    public static final int REMOVED_TEXT = Color.rgb(255, 138, 138);
    public static final int UNCHANGED_BACKGROUND = Color.rgb(20, 20, 25);
    public static final int UNCHANGED_TEXT = Color.rgb(194, 194, 204);

    public enum ButtonKind {
        PRIMARY,
        SECONDARY,
        MIC
    }

    private AiUiTheme() {
    }

    public static Dialog showDialog(final Context context, final String title, final View body,
            final View footer, final View headerAction) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(rounded(SURFACE, dp(context, 20), SURFACE_BORDER, dp(context, 1)));
        shell.setClipToOutline(true);
        shell.addView(createHeader(context, title, headerAction),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final ScrollView bodyScroll = new ScrollView(context);
        bodyScroll.setFillViewport(false);
        bodyScroll.setClipToPadding(false);
        bodyScroll.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8));
        bodyScroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(bodyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final LinearLayout stickyFooter = new LinearLayout(context);
        stickyFooter.setOrientation(LinearLayout.VERTICAL);
        stickyFooter.addView(divider(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        final LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.setMargins(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12));
        stickyFooter.addView(footer, footerParams);
        shell.addView(stickyFooter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(shell);
        dialog.setCanceledOnTouchOutside(true);
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.64f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (window != null) {
            final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.92f), (int) (metrics.heightPixels * 0.84f));
            window.setGravity(Gravity.CENTER);
        }
        return dialog;
    }

    public static GradientDrawable cardBackground(final Context context) {
        return rounded(SURFACE, dp(context, 20), SURFACE_BORDER, dp(context, 1));
    }

    public static GradientDrawable inputBackground(final Context context) {
        return rounded(INPUT, dp(context, 14), INPUT_BORDER, dp(context, 1));
    }

    public static GradientDrawable rounded(final int color, final int radius, final int borderColor,
            final int borderWidth) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (borderWidth > 0) {
            drawable.setStroke(borderWidth, borderColor);
        }
        return drawable;
    }

    public static Button button(final Context context, final String label, final ButtonKind kind) {
        final Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setMinHeight(dp(context, 44));
        button.setMinWidth(dp(context, 72));
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        styleButton(button, kind);
        return button;
    }

    public static void styleButton(final Button button, final ButtonKind kind) {
        final Context context = button.getContext();
        final int fill;
        final int border;
        final int text;
        final int ripple;
        if (kind == ButtonKind.PRIMARY) {
            fill = ACCENT;
            border = ACCENT;
            text = Color.WHITE;
            ripple = ACCENT_PRESSED;
        } else if (kind == ButtonKind.MIC) {
            fill = Color.rgb(36, 19, 23);
            border = ACCENT;
            text = ACCENT;
            ripple = Color.rgb(75, 20, 27);
        } else {
            fill = SECONDARY;
            border = SECONDARY_BORDER;
            text = Color.rgb(242, 242, 245);
            ripple = Color.rgb(55, 33, 39);
        }
        final GradientDrawable shape = rounded(fill, dp(context, 22), border, dp(context, 1));
        button.setTextColor(text);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
    }

    public static void styleChip(final TextView chip, final boolean selected) {
        final Context context = chip.getContext();
        chip.setTextColor(selected ? Color.WHITE : Color.rgb(184, 184, 194));
        chip.setTypeface(android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setBackground(new RippleDrawable(
                ColorStateList.valueOf(selected ? ACCENT_PRESSED : Color.rgb(48, 48, 58)),
                rounded(selected ? ACCENT : Color.rgb(26, 26, 33), dp(context, 24),
                        selected ? ACCENT : Color.rgb(48, 48, 58), dp(context, 1)),
                null));
        chip.setPadding(dp(context, 13), dp(context, 7), dp(context, 13), dp(context, 7));
    }

    public static LinearLayout actionRow(final Context context) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    public static TextView headerAction(final Context context, final String label) {
        final TextView action = new TextView(context);
        action.setText(label);
        action.setTextColor(SECONDARY_TEXT);
        action.setTextSize(20);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(context, 12), dp(context, 4), dp(context, 4), dp(context, 4));
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.rgb(55, 33, 39)),
                rounded(SECONDARY, dp(context, 16), SECONDARY_BORDER, dp(context, 1)), null));
        return action;
    }

    public static TextView label(final Context context, final String text, final int size) {
        final TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(PRIMARY_TEXT);
        label.setTextSize(size);
        return label;
    }

    public static View divider(final Context context) {
        final View divider = new View(context);
        divider.setBackgroundColor(DIVIDER);
        return divider;
    }

    public static LinearLayout.LayoutParams weightedButtonParams(final Context context) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        return params;
    }

    public static int dp(final Context context, final int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static LinearLayout createHeader(final Context context, final String title,
            final View headerAction) {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), dp(context, 13), dp(context, 16), dp(context, 12));
        final View accent = new View(context);
        accent.setBackground(rounded(ACCENT, dp(context, 4), ACCENT, 0));
        row.addView(accent, new LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)));
        final TextView titleView = label(context, title, 19);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(context, 10), 0, dp(context, 8), 0);
        row.addView(titleView, titleParams);
        if (headerAction != null) {
            row.addView(headerAction, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40)));
        }
        header.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(divider(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return header;
    }
}
