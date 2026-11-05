/*
 * Custom addition: a Downloads chip at the start of the chip row of the home page,
 * before the moods (Relax, Energize...).
 *
 * The chip row of the app is a view the app inflates for several pages. Each time one is
 * inflated it comes here. Only the one of the home page gets the chip: it is found by the views
 * around it, since the home page is the only page with the layout of the top level pages. The
 * chip is a view added to the row itself, so nothing of the app is replaced: turning the setting
 * off gives the stock row back.
 *
 * Tapping the chip opens the downloads screen on top of the home page. Closing it shows the
 * home page again, since there is no other page under it.
 *
 * A second chip, Favorites, sits right after it and opens the liked songs sorted by artist.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class HomeDownloadsChip {

    private static final String CHIP_LIST_ID = "chip_cloud";
    private static final String CLEAR_BUTTON_ID = "search_clear_button";
    /** Only in the layout of the top level pages (the home page), not in the library page. */
    private static final String TOP_LEVEL_PAGE_ID = "background_image_container";
    private static final String NAVIGATION_BAR_ID = "pivot_bar";

    private static final int CHIP_HEIGHT_DP = 32;
    private static final int CHIP_RADIUS_DP = 8;
    private static final int CHIP_PADDING_DP = 12;
    private static final int GAP_DP = 8;
    private static final int DEFAULT_START_MARGIN_DP = 16;

    /** The rows already looked at. Holds the rows weakly, so nothing is kept alive. */
    private static final Set<View> seen = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Injection point. Called with the chip row, right after the app inflated it.
     */
    public static void onChipCloudInflated(View view) {
        try {
            if (view == null || !seen.add(view)) return;

            Controller controller = new Controller(view);
            view.addOnAttachStateChangeListener(controller);
            view.addOnLayoutChangeListener(controller);
            if (view.isAttachedToWindow()) controller.update();
        } catch (Exception ex) {
            Logger.printException(() -> "onChipCloudInflated failure", ex);
        }
    }

    private static final class Controller implements View.OnAttachStateChangeListener,
            View.OnLayoutChangeListener {

        private final View anchor;
        /** Whether the row is the one of the home page. Null while it cannot be known yet. */
        @Nullable
        private Boolean isHomeRow;

        @Nullable
        private TextView downloadsChip;
        @Nullable
        private TextView favoritesChip;
        private int originalStartPadding = -1;
        private int appliedStartPadding = -1;

        Controller(View anchor) {
            this.anchor = anchor;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            update();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            update();
        }

        void update() {
            try {
                if (!anchor.isAttachedToWindow()) return;

                final int listId = ResourceUtils.getIdentifier(ResourceType.ID, CHIP_LIST_ID);
                if (listId == 0) return;
                View list = anchor.findViewById(listId);
                if (list == null && anchor.getId() == listId) list = anchor;
                if (list == null || !(list.getParent() instanceof RelativeLayout)) return;
                RelativeLayout row = (RelativeLayout) list.getParent();

                if (isHomeRow == null) {
                    Boolean verdict = classify(row);
                    if (verdict == null) {
                        // Not known yet: the row is not under its page, or another tab is shown.
                        hide(row, list);
                        return;
                    }
                    isHomeRow = verdict;
                }
                if (!isHomeRow) return;

                final View clearButton = row.findViewById(
                        ResourceUtils.getIdentifier(ResourceType.ID, CLEAR_BUTTON_ID));
                // While a mood is chosen the app shows its clear button at the start of the row.
                // The chip steps aside then, so the row is exactly the one of the app.
                final boolean moodChosen = clearButton != null
                        && clearButton.getVisibility() == View.VISIBLE;

                final boolean showDownloads = Settings.HOME_DOWNLOADS_CHIP.get()
                        && DownloadsPatch.isInAppDownloadsMode()
                        && !moodChosen;
                final boolean showFavorites = Settings.HOME_FAVORITES_CHIP.get() && !moodChosen;
                if (showDownloads || showFavorites) {
                    show(row, list, showDownloads, showFavorites);
                } else {
                    hide(row, list);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "HomeDownloadsChip update failure", ex);
            }
        }

        /**
         * @return True for the row of the home page, false for any other row,
         * null when it cannot be told yet.
         */
        @Nullable
        private Boolean classify(View row) {
            ViewGroup page = null;
            ViewParent parent = row.getParent();
            while (parent != null) {
                if (parent instanceof ViewGroup
                        && parent.getClass().getName().endsWith("CoordinatorLayout")) {
                    page = (ViewGroup) parent;
                    break;
                }
                parent = parent.getParent();
            }
            if (page == null) return null;

            final int topLevelId = ResourceUtils.getIdentifier(ResourceType.ID, TOP_LEVEL_PAGE_ID);
            if (topLevelId == 0 || page.findViewById(topLevelId) == null) return Boolean.FALSE;

            // The home page is the first tab of the navigation bar.
            if (isOtherTabSelected()) return null;
            return Boolean.TRUE;
        }

        private boolean isOtherTabSelected() {
            Activity activity = Utils.getActivity();
            if (activity == null) return false;

            final int barId = ResourceUtils.getIdentifier(ResourceType.ID, NAVIGATION_BAR_ID);
            if (barId == 0) return false;
            View bar = activity.findViewById(barId);
            if (!(bar instanceof ViewGroup)) return false;

            ViewGroup tabs = (ViewGroup) bar;
            while (tabs.getChildCount() == 1 && tabs.getChildAt(0) instanceof ViewGroup) {
                tabs = (ViewGroup) tabs.getChildAt(0);
            }
            if (tabs.getChildCount() < 2) return false;
            for (int i = 0; i < tabs.getChildCount(); i++) {
                if (tabs.getChildAt(i).isSelected()) return i > 0;
            }
            return false;
        }

        private void show(RelativeLayout row, View list, boolean downloads, boolean favorites) {
            if (originalStartPadding < 0) originalStartPadding = list.getPaddingStart();
            if (downloads && downloadsChip == null) {
                downloadsChip = buildChip(row, str("morphe_music_downloads_screen_title"),
                        DownloadsPatch::openLocalDownloadsFromHome);
            }
            if (favorites && favoritesChip == null) {
                favoritesChip = buildChip(row, str("morphe_music_favorites_chip_label"),
                        DownloadsPatch::openFavoritesFromHome);
            }

            final int startMargin = originalStartPadding > 0
                    ? originalStartPadding : dp(row, DEFAULT_START_MARGIN_DP);
            final int gap = dp(row, GAP_DP);

            // The chips follow each other, and the moods start after the last one that shows.
            View last = null;
            if (downloads) {
                place(row, downloadsChip, null, startMargin);
                last = downloadsChip;
            } else if (downloadsChip != null) {
                downloadsChip.setVisibility(View.GONE);
            }
            if (favorites) {
                place(row, favoritesChip, last, last == null ? startMargin : gap);
                last = favoritesChip;
            } else if (favoritesChip != null) {
                favoritesChip.setVisibility(View.GONE);
            }

            RelativeLayout.LayoutParams listParams = (RelativeLayout.LayoutParams) list.getLayoutParams();
            if (listParams.getRule(RelativeLayout.END_OF) != last.getId()) {
                listParams.addRule(RelativeLayout.END_OF, last.getId());
                list.setLayoutParams(listParams);
            }
            if (list.getPaddingStart() != gap) {
                appliedStartPadding = gap;
                list.setPaddingRelative(gap, list.getPaddingTop(), list.getPaddingEnd(),
                        list.getPaddingBottom());
            }
        }

        /** Puts the chip at the start of the row, or right after another chip. */
        private void place(RelativeLayout row, TextView view, @Nullable View after, int startMargin) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
            final int wantedAfter = after == null ? 0 : after.getId();
            final boolean atStart = after == null;
            final boolean isAtStart = params.getRule(RelativeLayout.ALIGN_PARENT_START) != 0;
            if (params.getRule(RelativeLayout.END_OF) != wantedAfter
                    || isAtStart != atStart
                    || params.getMarginStart() != startMargin) {
                if (atStart) {
                    params.removeRule(RelativeLayout.END_OF);
                    params.addRule(RelativeLayout.ALIGN_PARENT_START);
                } else {
                    params.removeRule(RelativeLayout.ALIGN_PARENT_START);
                    params.addRule(RelativeLayout.END_OF, wantedAfter);
                }
                params.setMarginStart(startMargin);
                view.setLayoutParams(params);
            }
            if (view.getParent() == null) row.addView(view);
            if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
        }

        private void hide(RelativeLayout row, View list) {
            if (downloadsChip == null && favoritesChip == null) return;
            if (downloadsChip != null && downloadsChip.getVisibility() != View.GONE) {
                downloadsChip.setVisibility(View.GONE);
            }
            if (favoritesChip != null && favoritesChip.getVisibility() != View.GONE) {
                favoritesChip.setVisibility(View.GONE);
            }

            RelativeLayout.LayoutParams listParams = (RelativeLayout.LayoutParams) list.getLayoutParams();
            if (listParams.getRule(RelativeLayout.END_OF) != 0) {
                listParams.removeRule(RelativeLayout.END_OF);
                list.setLayoutParams(listParams);
            }
            // The padding goes back only if the app did not change it in the meantime.
            if (appliedStartPadding >= 0 && originalStartPadding >= 0
                    && list.getPaddingStart() == appliedStartPadding) {
                list.setPaddingRelative(originalStartPadding, list.getPaddingTop(),
                        list.getPaddingEnd(), list.getPaddingBottom());
            }
            appliedStartPadding = -1;
        }

        private TextView buildChip(RelativeLayout row, String label, Runnable onClick) {
            Context context = row.getContext();
            TextView view = DownloadsUi.text(context, label, 14, DownloadsUi.foreground());
            view.setId(View.generateViewId());
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(true);
            view.setPadding(dp(row, CHIP_PADDING_DP), 0, dp(row, CHIP_PADDING_DP), 0);
            view.setClickable(true);
            view.setFocusable(true);
            view.setContentDescription(label);
            view.setBackground(DownloadsUi.rippleCard(
                    DownloadsUi.mix(DownloadsUi.background(), DownloadsUi.foreground(), 0.12f),
                    DownloadsUi.withAlpha(DownloadsUi.foreground(), 40),
                    Math.max(1, dp(row, 1) / 2),
                    dp(row, CHIP_RADIUS_DP)));
            view.setOnClickListener(v -> onClick.run());

            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT, dp(row, CHIP_HEIGHT_DP));
            params.addRule(RelativeLayout.ALIGN_PARENT_START);
            params.addRule(RelativeLayout.CENTER_VERTICAL);
            view.setLayoutParams(params);
            return view;
        }

        private static int dp(View view, int value) {
            return DownloadsUi.dp(view.getContext(), value);
        }
    }
}
