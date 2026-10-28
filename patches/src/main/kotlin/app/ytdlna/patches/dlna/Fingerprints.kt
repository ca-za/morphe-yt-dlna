package app.ytdlna.patches.dlna

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val STREAMING_DATA_CLASS =
    $$"Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass$StreamingData;"

/**
 * Constructor of the final video streaming data model (YouTube: aleb, Music: wja; its
 * toString() starts with "VideoStreamingData(itags=").
 * Its first two parameters are the streaming data proto and the video details proto.
 * This runs after any stream replacement done by other patches (such as Morphe's
 * "Spoof video streams"), so it always sees the streams the player really uses.
 */
internal object VideoStreamingDataConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    custom = { method, classDef ->
        val types = method.parameterTypes.map { it.toString() }
        types.size >= 5 &&
            types[0] == STREAMING_DATA_CLASS &&
            types[1].startsWith("L") &&
            classDef.methods.any { other ->
                other.name == "toString" && other.implementation?.instructions?.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                        ?.string?.startsWith("VideoStreamingData(") == true
                } == true
            }
    }
)

/**
 * androidx MediaRouteButton.performClick(). The class is obfuscated in YouTube Music,
 * but the method keeps its name and is the only one that launches the system output switcher.
 */
internal object CastButtonPerformClickFingerprint : Fingerprint(
    name = "performClick",
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
)

/**
 * The cast button controller's update (YouTube anqm.d / akjk.d, Music xrn.c): asks the media
 * router whether a Google Cast device is available (a static call taking the route selector and
 * flags), compares it with what it shows, and on a change shows or hides all cast buttons and
 * publishes the answer. Older versions apply the buttons in a helper method; the class has the
 * cast button's visual element id 11208 either way.
 */
internal object CastButtonControllerUpdateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, classDef ->
        method.implementation?.instructions?.any { routeAvailableCall(it) } == true &&
            classDef.methods.any { other ->
                other.implementation?.instructions?.any {
                    (it as? NarrowLiteralInstruction)?.narrowLiteral == CAST_BUTTON_ELEMENT_ID
                } == true
            }
    }
)

internal const val CAST_BUTTON_ELEMENT_ID = 11208

/** MediaRouter.isRouteAvailable(selector, flags): static, (L...;I)Z. */
internal fun routeAvailableCall(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return false
    val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return false
    val parameters = reference.parameterTypes.map { it.toString() }
    return reference.returnType == "Z" && parameters.size == 2 && parameters[0].startsWith("L") && parameters[1] == "I"
}

/**
 * Called with (player response model, video id) when the player loads a new video or track.
 * Same method in YouTube and YouTube Music. Used to know which captured streams belong to the
 * video that is actually playing, since ads and preloaded videos are captured as well.
 */
internal object VideoLoadFingerprint : Fingerprint(
    // Returns an object since Music 9.4x, void before.
    parameters = listOf("L", "Ljava/lang/String;"),
    strings = listOf("Null initialPlayabilityStatus")
)

/**
 * YouTube Music's song/video mode enum (ATV_PREFERRED, OMV_PREFERRED, DONT_PLAY_VIDEO_OVERRIDE,
 * ATV_PREFERRED_USER_TRIGGERED, OMV_PREFERRED_USER_TRIGGERED). The names stay in the static initializer.
 */
internal object MusicPlaybackModeEnumFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("OMV_PREFERRED_USER_TRIGGERED", "DONT_PLAY_VIDEO_OVERRIDE")
)

/** Whether a method loads a string starting with [prefix]. */
internal fun hasString(method: com.android.tools.smali.dexlib2.iface.Method, prefix: String) =
    method.implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(prefix) == true
    } == true

/**
 * MedialibPlayer.pauseVideo(reason). The player below the app's directors; its methods log their
 * own names, which makes them easy to find in every version.
 */
internal object MedialibPauseFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    custom = { method, _ -> hasString(method, "MedialibPlayer.pauseVideo(") }
)

/** MedialibPlayer.playVideo(). */
internal object MedialibPlayFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ -> hasString(method, "MedialibPlayer.playVideo()") }
)

/** MedialibPlayer.seekTo(ms, source). */
internal object MedialibSeekFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == "J" &&
            hasString(method, "MedialibPlayer.seekTo(")
    }
)
