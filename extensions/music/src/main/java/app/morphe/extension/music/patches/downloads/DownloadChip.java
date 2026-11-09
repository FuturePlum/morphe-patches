/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.music.patches.NavigationBarPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;

/**
 * Adds a "Downloads" chip at the start of the chip bar of the home page.
 * <p>
 * The chip copies the shape, the colors, the font and the margins of a chip the app already
 * drew, so it looks the same. It is added to the row of chips itself when that row is a
 * horizontal scroll view, so it scrolls with the others. When the chips are a recycler list,
 * space is kept before the first chip and the new chip follows the first chip while it
 * is on screen.
 */
@SuppressWarnings("unused")
public final class DownloadChip {

    private static final int MAX_ATTEMPTS = 25;
    private static final long RETRY_DELAY_MILLISECONDS = 300;
    private static final int GAP_FALLBACK_DP = 8;

    private static final WeakHashMap<View, Boolean> handled = new WeakHashMap<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private DownloadChip() {
    }

    /**
     * Injection point. Called with the chip bar each time one is inflated.
     */
    public static void onChipCloudInflated(View chipCloud) {
        try {
            if (chipCloud == null || Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP) return;
            if (handled.containsKey(chipCloud)) return;
            handled.put(chipCloud, Boolean.TRUE);
            handler.postDelayed(() -> tryInstall(chipCloud, 1), RETRY_DELAY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "onChipCloudInflated failure", ex);
        }
    }

    private static boolean isHomeTab() {
        String name = NavigationBarPatch.getLastNavigationName();
        return name.isEmpty() || "TAB_HOME".equals(name);
    }

    /** The search results have chips too, and the search field is on screen while they are. */
    private static boolean searchIsOpen(View chipCloud) {
        View root = chipCloud.getRootView();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof EditText && view.isShown()) return true;
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return false;
    }

    private static void tryInstall(View chipCloud, int attempt) {
        try {
            if (!chipCloud.isAttachedToWindow()) {
                if (attempt < MAX_ATTEMPTS) {
                    handler.postDelayed(() -> tryInstall(chipCloud, attempt + 1), RETRY_DELAY_MILLISECONDS);
                }
                return;
            }
            if (!isHomeTab() || searchIsOpen(chipCloud)) return;

            View scroller = findScroller(chipCloud);
            if (scroller instanceof HorizontalScrollView scrollView
                    && scrollView.getChildAt(0) instanceof ViewGroup row) {
                if (row.getChildCount() == 0) {
                    retry(chipCloud, attempt);
                    return;
                }
                installInRow(row);
            } else if (scroller != null) {
                ViewGroup list = (ViewGroup) scroller;
                if (list.getChildCount() == 0) {
                    retry(chipCloud, attempt);
                    return;
                }
                installBeforeList(list);
            } else if (chipCloud instanceof LinearLayout row
                    && row.getOrientation() == LinearLayout.HORIZONTAL) {
                if (row.getChildCount() == 0) {
                    retry(chipCloud, attempt);
                    return;
                }
                installInRow(row);
            } else {
                Logger.printDebug(() -> "Download chip: no chip row found in "
                        + chipCloud.getClass().getName());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Download chip install failure", ex);
        }
    }

    private static void retry(View chipCloud, int attempt) {
        if (attempt < MAX_ATTEMPTS) {
            handler.postDelayed(() -> tryInstall(chipCloud, attempt + 1), RETRY_DELAY_MILLISECONDS);
        }
    }

    private static boolean isRecyclerView(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith("RecyclerView")) return true;
        }
        return false;
    }

    private static View findScroller(View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof HorizontalScrollView
                    || (view instanceof ViewGroup && isRecyclerView(view))) {
                return view;
            }
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    private static boolean alreadyThere(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if ("morphe_download_chip".equals(row.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    /** The chips are real children of a row, so the new one scrolls like the rest. */
    private static void installInRow(ViewGroup row) {
        if (alreadyThere(row)) return;
        View sample = row.getChildAt(0);
        View chip = createChip(row.getContext(), sample);

        ViewGroup.LayoutParams sampleParams = sample.getLayoutParams();
        LinearLayout.LayoutParams params = sampleParams instanceof ViewGroup.MarginLayoutParams margins
                ? new LinearLayout.LayoutParams(-2, margins.height)
                : new LinearLayout.LayoutParams(-2, -2);
        if (sampleParams instanceof ViewGroup.MarginLayoutParams margins) {
            params.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin);
        } else {
            params.setMargins(0, 0, dp(row.getContext(), GAP_FALLBACK_DP), 0);
        }
        row.addView(chip, 0, params);
        Logger.printDebug(() -> "Download chip added to " + row.getClass().getName());
    }

    /**
     * Keeps room before the first chip of a recycler list, and draws the new chip there.
     * It is shown while the first chip is on screen, and follows it while the list scrolls.
     */
    private static void installBeforeList(ViewGroup list) {
        ViewGroup host = hostFor(list);
        if (host == null) return;
        for (int i = 0; i < host.getChildCount(); i++) {
            if ("morphe_download_chip".equals(host.getChildAt(i).getTag())) return;
        }

        View sample = list.getChildAt(0);
        TextView chip = (TextView) createChip(list.getContext(), sample);
        chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        final int chipWidth = chip.getMeasuredWidth();
        final int sampleHeight = Math.max(sample.getHeight(), chip.getMeasuredHeight());

        int gap = GAP_FALLBACK_DP;
        if (sample.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
            gap = Math.max(margins.rightMargin, margins.leftMargin) > 0
                    ? Math.round(Math.max(margins.rightMargin, margins.leftMargin)
                    / list.getResources().getDisplayMetrics().density)
                    : GAP_FALLBACK_DP;
        }
        final int gapPx = dp(list.getContext(), gap);
        final int reserved = chipWidth + gapPx;

        list.setPadding(list.getPaddingLeft() + reserved, list.getPaddingTop(),
                list.getPaddingRight(), list.getPaddingBottom());
        list.setClipToPadding(false);

        host.addView(chip, new FrameLayout.LayoutParams(chipWidth, sampleHeight));
        final View[] anchorHolder = {null};
        final int[] listLocation = new int[2];
        final int[] hostLocation = new int[2];

        ViewTreeObserver.OnPreDrawListener sync = () -> {
            try {
                if (!list.isShown() || list.getChildCount() == 0) {
                    chip.setVisibility(View.INVISIBLE);
                    return true;
                }
                // Not scrolled at all: the first child is the first chip.
                if (!list.canScrollHorizontally(-1)) anchorHolder[0] = list.getChildAt(0);

                View first = anchorHolder[0];
                if (first == null || first.getParent() != list) {
                    chip.setVisibility(View.INVISIBLE);
                    return true;
                }
                list.getLocationInWindow(listLocation);
                host.getLocationInWindow(hostLocation);
                chip.setVisibility(View.VISIBLE);
                chip.setTranslationX(listLocation[0] - hostLocation[0] + first.getLeft() - reserved);
                chip.setTranslationY(listLocation[1] - hostLocation[1] + first.getTop()
                        + (first.getHeight() - chip.getLayoutParams().height) / 2f);
            } catch (Exception ex) {
                Logger.printException(() -> "Download chip sync failure", ex);
            }
            return true;
        };
        list.getViewTreeObserver().addOnPreDrawListener(sync);
        list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (list.getViewTreeObserver().isAlive()) {
                    list.getViewTreeObserver().addOnPreDrawListener(sync);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (list.getViewTreeObserver().isAlive()) {
                    list.getViewTreeObserver().removeOnPreDrawListener(sync);
                }
            }
        });
        Logger.printDebug(() -> "Download chip floating before " + list.getClass().getName());
    }

    /** The chip has to sit in a layout where children can overlap, next to the list. */
    private static ViewGroup hostFor(ViewGroup list) {
        ViewGroup parent = list.getParent() instanceof ViewGroup group ? group : null;
        if (parent != null && !isRecyclerView(parent)
                && (parent instanceof FrameLayout
                || parent.getClass().getName().endsWith("ConstraintLayout")
                || parent.getClass().getName().endsWith("RelativeLayout"))) {
            return parent;
        }
        Activity activity = Utils.getActivity();
        if (activity != null && activity.findViewById(android.R.id.content) instanceof FrameLayout content) {
            return content;
        }
        return null;
    }

    private static View createChip(Context context, View sample) {
        TextView reference = sample instanceof TextView text ? text : findText(sample);

        TextView chip = new TextView(context);
        chip.setTag("morphe_download_chip");
        chip.setText(Txt.t("Téléchargements", "Downloads"));
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setAllCaps(false);
        chip.setClickable(true);
        chip.setFocusable(true);

        if (reference != null) {
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, reference.getTextSize());
            chip.setTextColor(reference.getTextColors());
            chip.setTypeface(reference.getTypeface());
            chip.setIncludeFontPadding(reference.getIncludeFontPadding());
            chip.setLetterSpacing(reference.getLetterSpacing());
        } else {
            chip.setTextSize(14);
            chip.setTextColor(0xFFFFFFFF);
        }

        Drawable background = sample.getBackground() != null ? sample.getBackground()
                : reference != null ? reference.getBackground() : null;
        if (background != null && background.getConstantState() != null) {
            chip.setBackground(background.getConstantState().newDrawable().mutate());
            // A chip the app drew as selected must not make this one look selected.
            chip.setSelected(false);
            chip.setActivated(false);
        }

        if (sample instanceof TextView) {
            chip.setPadding(sample.getPaddingLeft(), sample.getPaddingTop(),
                    sample.getPaddingRight(), sample.getPaddingBottom());
        } else {
            int left = sample.getPaddingLeft() + (reference != null ? reference.getPaddingLeft() : 0);
            int right = sample.getPaddingRight() + (reference != null ? reference.getPaddingRight() : 0);
            chip.setPadding(Math.max(left, dp(context, 12)), 0, Math.max(right, dp(context, 12)), 0);
        }
        if (sample.getMinimumHeight() > 0) chip.setMinHeight(sample.getMinimumHeight());

        chip.setOnClickListener(v -> DownloadsPatch.openDownloadsScreen());
        return chip;
    }

    private static TextView findText(View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof TextView text && text.getText().length() > 0) return text;
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }
}
