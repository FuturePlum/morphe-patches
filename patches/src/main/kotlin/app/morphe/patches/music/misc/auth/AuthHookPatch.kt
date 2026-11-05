/*
 * Custom addition: lets the extension know the sign-in headers the app sends in its own requests,
 * so it can ask YouTube for the liked songs of the account (favorites by artist page).
 *
 * Same hook as the one YouTube uses. It only reads the headers of the requests the app builds.
 */

package app.morphe.patches.music.misc.auth

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.misc.auth.authHookPatch

internal val authHookPatch = authHookPatch(
    // The empty page id check only exists in recent versions of the YouTube app.
    emptyPageIdHook = { false },
    block = {
        dependsOn(sharedExtensionPatch)
    }
)
