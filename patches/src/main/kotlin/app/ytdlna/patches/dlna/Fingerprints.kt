package app.ytdlna.patches.dlna

import app.morphe.patcher.Fingerprint

internal const val STREAMING_DATA_CLASS =
    $$"Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass$StreamingData;"

/**
 * Constructor of the final video streaming data model (YouTube: aleb, Music: wja).
 * Its first two parameters are the streaming data proto and the video details proto.
 * This runs after any stream replacement done by other patches (such as Morphe's
 * "Spoof video streams"), so it always sees the streams the player really uses.
 */
internal object VideoStreamingDataConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    custom = { method, _ ->
        val types = method.parameterTypes.map { it.toString() }
        types.size >= 5 &&
            types[0] == STREAMING_DATA_CLASS &&
            types[1].startsWith("L") &&
            types[2] == "[B"
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
