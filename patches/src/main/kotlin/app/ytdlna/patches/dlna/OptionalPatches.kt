package app.ytdlna.patches.dlna

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC

/*
 * Optional parts of DLNA casting. Each needs only "DLNA cast", none needs another: they can be
 * turned on and off on their own when patching. Without one, casting works as before.
 */

private const val FEATURES_CLASS = "Lapp/ytdlna/extension/Features;"
private const val CAST_CLASS = "Lapp/ytdlna/extension/DlnaCast;"

/** A patch that switches on Features.<method>() (its constant becomes true). */
private fun featurePatch(
    name: String,
    description: String,
    method: String,
    vararg compatibility: app.morphe.patcher.patch.Compatibility,
) = bytecodePatch(name = name, description = description, default = true) {
    compatibleWith(*compatibility)
    dependsOn(dlnaCastPatch)

    execute {
        mutableClassDefBy(FEATURES_CLASS).methods.single { it.name == method }
            .replaceInstruction(0, "const/4 v0, 0x1")
    }
}

@Suppress("unused")
val dlnaCoverPagePatch = featurePatch(
    name = "DLNA cover page",
    description = "While casting, YouTube Music's player shows the album cover on its colors over the " +
        "whole page (darker toward the bottom, fading to the next song's), like Spotify.",
    method = "coverPage",
    COMPATIBILITY_YOUTUBE_MUSIC,
)

@Suppress("unused")
val dlnaPortraitLockPatch = featurePatch(
    name = "DLNA portrait lock",
    description = "Keeps the phone upright while casting (the video plays on the TV).",
    method = "portraitLock",
    COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC,
)

@Suppress("unused")
val dlnaSonosRoomsPatch = featurePatch(
    name = "DLNA Sonos rooms",
    description = "Sonos groups in the device list (one row per group, named after its room), a rooms " +
        "dialog to add or remove rooms and set their volume, the group's volume on the volume keys, " +
        "and the same for renderers that feed Sonos rooms themselves.",
    method = "rooms",
    COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC,
)

@Suppress("unused")
val dlnaRendererExtrasPatch = featurePatch(
    name = "DLNA renderer extras",
    description = "Captions, lyrics (YouTube Music) and SponsorBlock segments for renderers that ask for " +
        "them (see the renderer guide). Plain renderers get the same as without it.",
    method = "rendererExtras",
    COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC,
)

@Suppress("unused")
val dlnaPlayerControlsPatch = bytecodePatch(
    name = "DLNA player controls",
    description = "While casting, YouTube keeps the player controls on screen (no hide timer), so the " +
        "renderer can be controlled at a glance. A tap still hides and shows them.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)
    dependsOn(dlnaCastPatch)

    execute {
        val method = PlayerControlsHideTimerFingerprint.method
        if (method.implementation!!.registerCount - 1 < 1) {
            throw PatchException("No free register in the controls hide timer")
        }
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $CAST_CLASS->keepPlayerControls()Z
                move-result v0
                if-eqz v0, :hide_timer
                return-void
                :hide_timer
                nop
            """
        )
    }
}
