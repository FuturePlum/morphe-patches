/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import java.util.Locale;

/** Tiny French / English text picker for the offline screens. */
public final class Txt {

    private Txt() {
    }

    private static boolean french() {
        return "fr".equals(Locale.getDefault().getLanguage());
    }

    public static String t(String fr, String en) {
        return french() ? fr : en;
    }
}
