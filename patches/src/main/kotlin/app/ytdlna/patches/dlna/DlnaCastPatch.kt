package app.ytdlna.patches.dlna

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

private const val CAPTURE_CLASS = "Lapp/ytdlna/extension/StreamCapture;"
private const val CAST_CLASS = "Lapp/ytdlna/extension/DlnaCast;"
private const val MUSIC_MODE_CLASS = "Lapp/ytdlna/extension/MusicMode;"
private const val AUDIO_CLASS = "Lapp/ytdlna/extension/CastAudio;"
private const val TEST_HOOKS_CLASS = "Lapp/ytdlna/extension/TestHooks;"
private const val INTENTS_CLASS = "Lapp/ytdlna/extension/PlayerIntents;"
private const val CAPTIONS_CLASS = "Lapp/ytdlna/extension/Captions;"
private const val AUDIO_TRACK = "Landroid/media/AudioTrack;"
private const val FOCUS_LISTENER = "Landroid/media/AudioManager\$OnAudioFocusChangeListener;"

private fun isFocusChange(method: com.android.tools.smali.dexlib2.iface.Method) =
    method.name == "onAudioFocusChange" && method.returnType == "V" &&
        method.parameterTypes.map { it.toString() } == listOf("I") && method.implementation != null
private const val MEDIA_SESSION = "Landroid/media/session/MediaSession;"

/** `new MediaSession(...)`: the constructor call, with the new session in its first register. */
private fun mediaSessionInit(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Int? {
    if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
    if (reference.definingClass != MEDIA_SESSION || reference.name != "<init>") return null
    return when (instruction) {
        is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction -> instruction.startRegister
        is com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction -> instruction.registerC
        else -> null
    }
}

/** AudioTrack methods routed through CastAudio: name + descriptor -> static replacement. */
private val AUDIO_TRACK_CALLS = mapOf(
    "play()V" to "play($AUDIO_TRACK)V",
    "setVolume(F)I" to "setVolume(${AUDIO_TRACK}F)I",
    "setStereoVolume(FF)I" to "setStereoVolume(${AUDIO_TRACK}FF)I",
)

private fun audioTrackCall(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): String? {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
    if (reference.definingClass != AUDIO_TRACK) return null
    val signature = reference.name + "(" + reference.parameterTypes.joinToString("") + ")" + reference.returnType
    return AUDIO_TRACK_CALLS[signature]?.let { "${reference.name}${it.substring(it.indexOf('('))}" }
}
private const val MDX_MEDIA_ROUTE_BUTTON_CLASS =
    "Lcom/google/android/libraries/youtube/mdx/mediaroute/MdxMediaRouteButton;"
private const val SHARE_ACTIVITY = "app.ytdlna.extension.DlnaShareActivity"
private const val MDX_ENTRY_POINT_BUTTON_CLASS =
    "Lcom/google/android/libraries/youtube/mdx/mediaroute/entrypoint/MdxEntryPointButton;"

/** SSDP discovery needs a multicast lock; network and Wi-Fi state access the apps already have. */
private val PERMISSIONS = listOf("android.permission.CHANGE_WIFI_MULTICAST_STATE")

private val NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

/** Loads a file bundled with the patches. */
private object PatchResources {
    fun open(path: String) = javaClass.getResourceAsStream(path)
}

/** File the Morphe settings patch merges add-on preferences from. */
private const val ADD_ON_PREFERENCES_FILE = "morphe_addon_prefs.xml"

/** Morphe's "Player" settings screens, where our switches go. */
private const val MUSIC_PLAYER_SETTINGS_SCREEN = "morphe_settings_music_screen_4_player"
private const val YOUTUBE_PLAYER_SETTINGS_SCREEN = "morphe_settings_screen_05_player"

private val ALBUM_ART_SWITCH = """
    <SwitchPreference
        android:key="ytdlna_album_art_lookup"
        android:title="@string/ytdlna_album_art_lookup_title"
        android:summary="@string/ytdlna_album_art_lookup_summary"
        android:defaultValue="true" />
""".trimIndent()

private val GOOGLE_CAST_LINK_SWITCH = """
    <SwitchPreference
        android:key="ytdlna_google_cast_link"
        android:title="@string/ytdlna_google_cast_link_title"
        android:summary="@string/ytdlna_google_cast_link_summary"
        android:defaultValue="false" />
""".trimIndent()

private val CAPTIONS_SWITCH = """
    <SwitchPreference
        android:key="ytdlna_captions"
        android:title="@string/ytdlna_captions_title"
        android:summary="@string/ytdlna_captions_summary"
        android:defaultValue="true" />
""".trimIndent()

private val DEBUG_LOG_SWITCH = """
    <SwitchPreference
        android:key="ytdlna_debug_log"
        android:title="@string/ytdlna_debug_log_title"
        android:summary="@string/ytdlna_debug_log_summary"
        android:defaultValue="false" />
""".trimIndent()

/** Strings of both apps, by resource folder. */
private val STRINGS = mapOf(
    "values" to listOf(
        "ytdlna_google_cast_link_title" to "DLNA: Google Cast in the device list",
        "ytdlna_google_cast_link_summary" to "Shows “Google Cast…” in the DLNA device list (also when " +
            "opened from the share menu), leading to the app's own cast dialog.",
        "ytdlna_captions_title" to "DLNA: Captions",
        "ytdlna_captions_summary" to "Sends the video's captions (in the phone's language) to renderers that " +
            "can show them. Other renderers are not affected.",
        "ytdlna_debug_log_title" to "DLNA: Debug log",
        "ytdlna_debug_log_summary" to "Shows “Share debug log” in the DLNA device list and records " +
            "extra details (such as SponsorBlock skips on the TV). For bug reports.",
    ),
    "values-de" to listOf(
        "ytdlna_google_cast_link_title" to "DLNA: Google Cast in der Geräteauswahl",
        "ytdlna_google_cast_link_summary" to "Zeigt „Google Cast…“ in der DLNA-Geräteauswahl (auch beim " +
            "Öffnen über Teilen); führt zum Cast-Dialog der App.",
        "ytdlna_captions_title" to "DLNA: Untertitel",
        "ytdlna_captions_summary" to "Sendet die Untertitel des Videos (in der Sprache des Handys) an Geräte, " +
            "die sie anzeigen können. Andere Geräte sind nicht betroffen.",
        "ytdlna_debug_log_title" to "DLNA: Debug-Log",
        "ytdlna_debug_log_summary" to "Zeigt „Debug-Log teilen“ in der DLNA-Geräteauswahl und zeichnet " +
            "zusätzliche Details auf (etwa SponsorBlock-Sprünge auf dem Fernseher). Für Fehlerberichte.",
    ),
)

/** Strings of YouTube Music only. */
private val MUSIC_STRINGS = mapOf(
    "values" to listOf(
        "ytdlna_album_art_lookup_title" to "DLNA: Album covers from music databases",
        "ytdlna_album_art_lookup_summary" to "Shows the album cover from Deezer or iTunes on the TV. " +
            "Title and artist are sent to these services.",
    ),
    "values-de" to listOf(
        "ytdlna_album_art_lookup_title" to "DLNA: Album-Cover aus Musikdatenbanken",
        "ytdlna_album_art_lookup_summary" to "Zeigt auf dem Fernseher das Album-Cover von Deezer oder iTunes. " +
            "Dafür werden Titel und Interpret an diese Dienste gesendet.",
    ),
)

private fun escapeXml(text: String) =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")

/** Shown when the patch is used without the official Morphe patches. */
private const val REQUIRES_OFFICIAL_PATCHES =
    "\"DLNA cast\" is an add-on to the official Morphe patches and cannot be used alone. " +
        "Patch together with the official Morphe patch bundle, including \"GmsCore support\"."

private val dlnaManifestPatch = resourcePatch {
    // Runs after all patches: by then GmsCore support has renamed the package.
    finalize {
        val packageName = document("AndroidManifest.xml").use { it.documentElement.getAttribute("package") }
        if (packageName.startsWith("com.google.android.")) {
            throw PatchException("$REQUIRES_OFFICIAL_PATCHES (Package name unchanged: $packageName)")
        }
    }

    execute {
        val isMusic = document("AndroidManifest.xml").use { doc ->
            doc.documentElement.getAttribute("package").contains("music")
        }
        // Strings for our settings.
        // Added like Morphe adds strings: to strings.xml without decoding it. A file written
        // this way adds its strings to the app's; if another patch already decoded the
        // file, the strings are appended to it.
        (if (isMusic) listOf(STRINGS, MUSIC_STRINGS) else listOf(STRINGS)).forEach { set ->
            set.forEach { (folder, strings) ->
                val elements = strings.joinToString("") { (name, value) ->
                    "    <string name=\"$name\">${escapeXml(value)}</string>\n"
                }
                get("res/$folder/strings.xml", false).apply {
                    val text = if (exists()) {
                        readText()
                    } else {
                        parentFile.mkdirs()
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n"
                    }
                    val closing = text.lastIndexOf("</resources>")
                    writeText(text.substring(0, closing) + elements + text.substring(closing))
                }
            }
        }
        // Switches in the Morphe settings (Player screen). Merged by Morphe's settings patch;
        // without it the switches are absent and their defaults are used.
        run {
            val screen = if (isMusic) {
                "<screen key=\"$MUSIC_PLAYER_SETTINGS_SCREEN\">\n$ALBUM_ART_SWITCH\n$GOOGLE_CAST_LINK_SWITCH\n$CAPTIONS_SWITCH\n$DEBUG_LOG_SWITCH\n</screen>"
            } else {
                "<screen key=\"$YOUTUBE_PLAYER_SETTINGS_SCREEN\">\n$GOOGLE_CAST_LINK_SWITCH\n$CAPTIONS_SWITCH\n$DEBUG_LOG_SWITCH\n</screen>"
            }
            val declaration = get(ADD_ON_PREFERENCES_FILE, false)
            if (declaration.exists() && declaration.readText().contains("</morphe-add-on-preferences>")) {
                declaration.writeText(
                    declaration.readText().replace("</morphe-add-on-preferences>", "$screen\n</morphe-add-on-preferences>")
                )
            } else {
                declaration.writeText(
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                        "<morphe-add-on-preferences xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                        "$screen\n</morphe-add-on-preferences>\n"
                )
            }
        }

        // Rust stream proxy, stored as an asset for the ABIs the app already has.
        // The app installs with extractNativeLibs=false, which needs uncompressed libraries
        // in lib/, but the patcher compresses added files. The extension copies the
        // library out of the assets and loads it at runtime instead.
        val apkAbis = listApkEntries("lib/")
            .map { it.removePrefix("lib/").substringBefore('/') }
            .filter { it.isNotEmpty() }
            .toSet()
        val abis = if (apkAbis.isEmpty()) NATIVE_ABIS else NATIVE_ABIS intersect apkAbis
        abis.forEach { abi ->
            val library = PatchResources.open("/ytdlna/native/$abi/libytdlna.so") ?: return@forEach
            get("assets/ytdlna/$abi/libytdlna.so", false).apply {
                parentFile.mkdirs()
                outputStream().use { out -> library.use { it.copyTo(out) } }
            }
        }

        document("AndroidManifest.xml").use { doc ->
            val existing = doc.getElementsByTagName("uses-permission").let { list ->
                (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") }
            }
            PERMISSIONS.filterNot(existing::contains).forEach { permission ->
                doc.documentElement.appendChild(
                    doc.createElement("uses-permission").apply {
                        setAttribute("android:name", permission)
                    }
                )
            }

            // Share target: "Stream to DLNA device".
            val application = doc.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                doc.createElement("activity").apply {
                    setAttribute("android:name", SHARE_ACTIVITY)
                    setAttribute("android:exported", "true")
                    setAttribute("android:label", "DLNA")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:noHistory", "true")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    appendChild(
                        doc.createElement("intent-filter").apply {
                            appendChild(doc.createElement("action").apply {
                                setAttribute("android:name", "android.intent.action.SEND")
                            })
                            appendChild(doc.createElement("category").apply {
                                setAttribute("android:name", "android.intent.category.DEFAULT")
                            })
                            appendChild(doc.createElement("data").apply {
                                setAttribute("android:mimeType", "text/plain")
                            })
                        }
                    )
                }
            )
        }
    }
}

/**
 * Hooks the MedialibPlayer's playVideo(), pauseVideo(reason) and seekTo(ms, source): every play,
 * pause and seek of the app's player ends there (YouTube and YouTube Music, whichever director
 * or facade it took). Found by the player's own log messages. Missing ones are skipped:
 * casting works without them.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.hookPlayerCommands() {
    MedialibPauseFingerprint.matchOrNull()?.method?.addInstructions(
        0, "invoke-static/range { p0 .. p1 }, $INTENTS_CLASS->onPause(Ljava/lang/Object;I)V"
    )
    MedialibPlayFingerprint.matchOrNull()?.method?.addInstructions(
        0, "invoke-static/range { p0 .. p0 }, $INTENTS_CLASS->onPlay(Ljava/lang/Object;)V"
    )
    MedialibSeekFingerprint.matchOrNull()?.method?.addInstructions(
        0, "invoke-static/range { p0 .. p3 }, $INTENTS_CLASS->onSeek(Ljava/lang/Object;JLjava/lang/Object;)V"
    )
}

@Suppress("unused")
val dlnaCastPatch = bytecodePatch(
    name = "DLNA cast",
    description = "Adds DLNA / UPnP casting to the cast button. " +
        "Streams the current video or song to a DLNA media renderer on the local network. " +
        "Requires the official Morphe patches (with GmsCore support). " +
        "The cast button shows when a DLNA renderer is found; turn off Morphe's \"Hide cast button\" " +
        "(on by default) to see it. Casting from the share menu works either way.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(dlnaManifestPatch)

    // Runs after all patches: the official extension is merged by then if its patches are used.
    finalize {
        if (classDefByOrNull("Lapp/morphe/extension/shared/Utils;") == null) {
            throw PatchException("$REQUIRES_OFFICIAL_PATCHES (Morphe extension missing)")
        }

        // Audio focus while casting: the app is silent then, so another app taking the focus
        // (the Sonos app opening, the system) must not pause it; its player would pass that
        // pause on to the TV as the user's. CastAudio drops focus changes while it mutes.
        // Here, after all patches: an official one edits YouTube's listener by index, and
        // would replace our first instruction instead (the app then fails to verify).
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/ytdlna/")) return@classDefForEach
            if (FOCUS_LISTENER !in classDef.interfaces) return@classDefForEach
            if (classDef.methods.none(::isFocusChange)) return@classDefForEach
            mutableClassDefBy(classDef.type).methods.filter(::isFocusChange).forEach { method ->
                // p1: the change. 0 back means drop it.
                method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p1 .. p1 }, $AUDIO_CLASS->onAudioFocusChange(I)I
                        move-result p1
                        if-nez p1, :focus_change
                        return-void
                        :focus_change
                        nop
                    """
                )
            }
        }
    }

    val testHooks by booleanOption(
        key = "testHooks",
        default = false,
        title = "Test hooks",
        description = "Remote control for the automated regression tests. Do not use for normal builds.",
    )

    extendWith("extensions/extension.mpe")

    execute {
        if (testHooks == true) {
            // TestHooks.ENABLED is a compile-time constant: its read is a single const/4.
            mutableClassDefBy(TEST_HOOKS_CLASS).methods.single { it.name == "isEnabled" }
                .replaceInstruction(0, "const/4 v0, 0x1")
        }

        // Capture the streams the player is about to use.
        // p1 = StreamingData proto, p2 = VideoDetails proto. Neither 'this' nor
        // locals are touched, so this is safe before the super constructor call.
        // While casting, the player gets a reduced copy (audio only), see LocalPlayback.
        VideoStreamingDataConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $CAPTURE_CLASS->onStreamingData(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $STREAMING_DATA_CLASS
            """
        )

        // Remember which video is playing. The first String getter the method calls on the
        // player response model (its first parameter) returns the video id.
        VideoLoadFingerprint.method.apply {
            val getVideoId = instructions.firstNotNullOf { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference?.takeIf {
                    (instruction.opcode == Opcode.INVOKE_INTERFACE ||
                        instruction.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
                        it.returnType == "Ljava/lang/String;" &&
                        it.parameterTypes.isEmpty()
                }
            }
            val model = if (AccessFlags.STATIC.isSet(accessFlags)) "p0" else "p1"
            // v0 is free at the start of the method.
            addInstructionsWithLabels(
                0,
                """
                    if-eqz $model, :no_model
                    invoke-interface/range { $model .. $model }, $getVideoId
                    move-result-object v0
                    invoke-static { v0 }, $CAPTURE_CLASS->onVideoLoaded(Ljava/lang/String;)V
                    invoke-static/range { $model .. $model }, $CAPTIONS_CLASS->onPlayerResponse(Ljava/lang/Object;)V
                    :no_model
                    nop
                """
            )
        }

        // Silence the app's own audio while casting: route its AudioTrack volume and play
        // calls through CastAudio. The device volume and other apps are not affected.
        val audioClasses = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/ytdlna/")) return@classDefForEach
            if (classDef.methods.any { method ->
                    method.implementation?.instructions?.any { audioTrackCall(it) != null } == true
                }) {
                audioClasses += classDef.type
            }
        }
        audioClasses.forEach { type ->
            mutableClassDefBy(type).methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                implementation.instructions.toList().forEachIndexed { index, instruction ->
                    val target = audioTrackCall(instruction) ?: return@forEachIndexed
                    val registers = if (instruction is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction) {
                        "/range { v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1} }"
                    } else {
                        val five = instruction as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
                        " { " + listOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG)
                            .take(five.registerCount).joinToString(", ") { "v$it" } + " }"
                    }
                    method.replaceInstruction(index, "invoke-static$registers, $AUDIO_CLASS->$target")
                }
            }
        }

        // The app's media sessions, reported as they are created: the extension follows the
        // one that plays (its notification, the other way to find it, is not always there).
        val sessionClasses = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/ytdlna/")) return@classDefForEach
            if (classDef.methods.any { method ->
                    method.implementation?.instructions?.any { mediaSessionInit(it) != null } == true
                }) {
                sessionClasses += classDef.type
            }
        }
        sessionClasses.forEach { type ->
            mutableClassDefBy(type).methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                implementation.instructions.toList().withIndex().reversed().forEach { (index, instruction) ->
                    val session = mediaSessionInit(instruction) ?: return@forEach
                    method.addInstructions(
                        index + 1,
                        "invoke-static/range { v$session .. v$session }, $CAST_CLASS->onMediaSession(Ljava/lang/Object;)V"
                    )
                }
            }
        }

        // Play, pause and seek commands to the app's player, with their source: what the user
        // (or a patch) asked for, as opposed to what the player did on its own.
        hookPlayerCommands()

        // YouTube Music: follow the song/video switch, so the renderer gets audio or video.
        MusicPlaybackModeEnumFingerprint.matchOrNull()?.let { enumMatch ->
            val modeType = enumMatch.classDef.type
            fun isSetter(method: com.android.tools.smali.dexlib2.iface.Method) =
                method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(modeType)
            // The mode store is the only class that starts with a mode in its constructor (which
            // may have unused parameters, depending on the version) and has a setter for it.
            val store = classDefByOrNull { classDef ->
                classDef.methods.any(::isSetter) && classDef.methods.any { method ->
                    method.name == "<init>" &&
                        method.implementation?.instructions?.any { instruction ->
                            instruction.opcode == Opcode.SGET_OBJECT &&
                                ((instruction as ReferenceInstruction).reference as? FieldReference)?.type == modeType
                        } == true
                }
            } ?: throw PatchException("Song/video mode store not found")
            val setters = mutableClassDefBy(store).methods.filter(::isSetter)
            setters.forEach { setter ->
                setter.addInstructions(
                    0,
                    "invoke-static/range { p1 .. p1 }, $MUSIC_MODE_CLASS->onModeChanged(Ljava/lang/Object;)V"
                )
            }
        }

        // The app shows its cast buttons only when a Google Cast device is available. Its button
        // controller decides that in one place for all buttons (and tells other parts of the
        // layout, like the mini player): there, DLNA renderers count as well.
        // The answer comes from one of three branches (casting disabled, a remembered device, the
        // media router); they meet where it is compared with the last one: `if (shown != available)`.
        CastButtonControllerUpdateFingerprint.method.apply {
            val list = instructions.toList()
            val compare = list.indices.firstOrNull { i ->
                val read = list[i]
                val next = list.getOrNull(i + 1)
                read.opcode == Opcode.IGET_BOOLEAN &&
                    ((read as ReferenceInstruction).reference as FieldReference).definingClass == definingClass &&
                    (next?.opcode == Opcode.IF_EQ || next?.opcode == Opcode.IF_NE) &&
                    (next as TwoRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA
            } ?: throw PatchException("Cast button availability check not found")
            val shown = getInstruction<TwoRegisterInstruction>(compare).registerA
            val branch = getInstruction<TwoRegisterInstruction>(compare + 1)
            val available = if (branch.registerA == shown) branch.registerB else branch.registerA
            // After the field read, which is where the branches meet (a label), not before it.
            // "Shown" is reported different once after renderers came or went, so the controller
            // updates its buttons although the Google Cast answer did not change.
            addInstructions(
                compare + 1,
                """
                    invoke-static/range { v$available .. v$available }, $CAST_CLASS->isCastAvailable(Z)Z
                    move-result v$available
                    invoke-static/range { v$shown .. v$shown }, $CAST_CLASS->castButtonsShown(Z)Z
                    move-result v$shown
                """
            )
            // The controller, to run this update again when renderers come or go.
            addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $CAST_CLASS->setCastButtonController(Ljava/lang/Object;)V
                    const-string v0, "$name"
                    invoke-static { v0 }, $CAST_CLASS->setCastButtonUpdate(Ljava/lang/String;)V
                """
            )
        }

        // Show the DLNA device picker when the cast button is tapped.
        val castClickHook = """
            invoke-static/range { p0 .. p0 }, $CAST_CLASS->onCastButtonClick(Landroid/view/View;)Z
            move-result v0
            if-eqz v0, :original
            const/4 v0, 0x1
            return v0
            :original
            nop
        """
        CastButtonPerformClickFingerprint.method.addInstructionsWithLabels(0, castClickHook)

        // YouTube Music's subclass has its own performClick that does not always call super.
        classDefByOrNull(MDX_MEDIA_ROUTE_BUTTON_CLASS)?.let { classDef ->
            mutableClassDefBy(classDef).methods.firstOrNull { method ->
                method.name == "performClick" && method.parameterTypes.isEmpty() && method.returnType == "Z"
            }?.addInstructionsWithLabels(0, castClickHook)
        }

        // YouTube's newer cast button (21.4x) is no MediaRouteButton: it gets a performClick
        // that opens the picker as well.
        classDefByOrNull(MDX_ENTRY_POINT_BUTTON_CLASS)?.let { classDef ->
            val button = mutableClassDefBy(classDef)
            if (button.methods.any { it.name == "performClick" && it.parameterTypes.isEmpty() }) {
                throw PatchException("Cast entry point button already overrides performClick")
            }
            button.methods.add(
                ImmutableMethod(
                    button.type,
                    "performClick",
                    listOf(),
                    "Z",
                    AccessFlags.PUBLIC.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { p0 }, $CAST_CLASS->onCastButtonClick(Landroid/view/View;)Z
                            move-result v0
                            if-eqz v0, :original
                            const/4 v0, 0x1
                            return v0
                            :original
                            invoke-super { p0 }, ${button.superclass}->performClick()Z
                            move-result v0
                            return v0
                        """
                    )
                }
            )
        }
    }
}
