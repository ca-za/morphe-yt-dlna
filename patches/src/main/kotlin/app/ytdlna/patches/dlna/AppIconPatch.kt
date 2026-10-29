package app.ytdlna.patches.dlna

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

/*
 * The DLNA app icon: YouTube as a screen, YouTube Music as a record, each with a play button and
 * the cast waves (scripts/icons/generate.py draws them). Optional and off by default.
 *
 * Morphe's "Custom branding" sets the icon in its own finalize step, and the order of two
 * finalize steps isn't known. So the icon is set twice: in the manifest (application, every
 * launcher entry), and as the contents of Morphe's launcher icon files, which "Custom branding"
 * writes earlier (when it executes): whichever icon it then points the app at is this one.
 */

private const val LAUNCHER = "ytdlna_launcher"
private const val BACKGROUND = "ytdlna_icon_background"
private const val FOREGROUND = "ytdlna_icon_foreground"
private const val MONOCHROME = "ytdlna_icon_monochrome"

private val ADAPTIVE_ICON = """
    <?xml version="1.0" encoding="utf-8"?>
    <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@drawable/$BACKGROUND" />
        <foreground android:drawable="@drawable/$FOREGROUND" />
        <monochrome android:drawable="@drawable/$MONOCHROME" />
    </adaptive-icon>
""".trimIndent() + "\n"

private object IconResources {
    fun read(path: String): String =
        javaClass.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
            ?: throw PatchException("Missing icon resource $path")
}

@Suppress("unused")
val dlnaAppIconPatch = resourcePatch(
    name = "DLNA app icon",
    description = "An app icon for DLNA casting: YouTube as a screen, YouTube Music as a record, each " +
        "with a play button and cast waves. Replaces the icon of \"Custom branding\" too (all its " +
        "styles then show this one).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC)

    val style by stringOption(
        key = "style",
        default = "night",
        values = mapOf("Night (dark)" to "night", "Day (light)" to "day"),
        title = "Icon style",
        description = "Night: on a dark violet background. Day: on a light background.",
        required = true,
    ) { it == "night" || it == "day" }

    finalize {
        val isMusic = document("AndroidManifest.xml").use { doc ->
            doc.documentElement.getAttribute("package").contains("music")
        }
        val folder = "/ytdlna/branding/${if (isMusic) "music" else "youtube"}"
        val chosen = style ?: "night"

        fun write(path: String, text: String) = get(path, false).apply {
            parentFile.mkdirs()
            writeText(text)
        }
        write("res/drawable/$BACKGROUND.xml", IconResources.read("$folder/$chosen/background.xml"))
        write("res/drawable/$FOREGROUND.xml", IconResources.read("$folder/$chosen/foreground.xml"))
        write("res/drawable/$MONOCHROME.xml", IconResources.read("$folder/monochrome.xml"))
        write("res/mipmap-anydpi/$LAUNCHER.xml", ADAPTIVE_ICON)

        // "Custom branding": its launcher icons (one per style, and the user's) become this one.
        get("res/mipmap-anydpi", false).listFiles { f -> f.name.startsWith("morphe_launcher_") && f.name.endsWith(".xml") }
            ?.forEach { it.writeText(ADAPTIVE_ICON) }

        document("AndroidManifest.xml").use { doc ->
            val application = doc.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:icon", "@mipmap/$LAUNCHER")
            // Adaptive icons replace round ones (Android 8+): a launcher preferring the round icon
            // would otherwise keep the old one.
            application.removeAttribute("android:roundIcon")
            for (tag in listOf("activity", "activity-alias")) {
                val list = doc.getElementsByTagName(tag)
                for (i in 0 until list.length) {
                    val element = list.item(i) as Element
                    if (element.hasAttribute("android:icon") || element.hasAttribute("android:roundIcon")) {
                        element.setAttribute("android:icon", "@mipmap/$LAUNCHER")
                        element.removeAttribute("android:roundIcon")
                    }
                }
            }
        }
    }
}
