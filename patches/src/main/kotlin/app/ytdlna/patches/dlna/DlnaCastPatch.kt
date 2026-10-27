package app.ytdlna.patches.dlna

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

private const val CAPTURE_CLASS = "Lapp/ytdlna/extension/StreamCapture;"
private const val CAST_CLASS = "Lapp/ytdlna/extension/DlnaCast;"
private const val MUSIC_MODE_CLASS = "Lapp/ytdlna/extension/MusicMode;"
private const val AUDIO_CLASS = "Lapp/ytdlna/extension/CastAudio;"
private const val TEST_HOOKS_CLASS = "Lapp/ytdlna/extension/TestHooks;"
private const val AUDIO_TRACK = "Landroid/media/AudioTrack;"

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

/** SSDP discovery needs a multicast lock; network and Wi-Fi state access the apps already have. */
private val PERMISSIONS = listOf("android.permission.CHANGE_WIFI_MULTICAST_STATE")

private val NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

/** Loads a file bundled with the patches. */
private object PatchResources {
    fun open(path: String) = javaClass.getResourceAsStream(path)
}

/** File the Morphe settings patch merges add-on preferences from. */
private const val ADD_ON_PREFERENCES_FILE = "morphe_addon_prefs.xml"

private const val MUSIC_PLAYER_SETTINGS_SCREEN = "morphe_settings_music_screen_4_player"

private val ALBUM_ART_SWITCH = """
    <SwitchPreference
        android:key="ytdlna_album_art_lookup"
        android:title="@string/ytdlna_album_art_lookup_title"
        android:summary="@string/ytdlna_album_art_lookup_summary"
        android:defaultValue="true" />
""".trimIndent()

private val STRINGS = mapOf(
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
        if (isMusic) {
            // Strings for our settings.
            // Added like Morphe adds strings: to strings.xml without decoding it. A file written
            // this way adds its strings to the app's; if another patch already decoded the
            // file, the strings are appended to it.
            STRINGS.forEach { (folder, strings) ->
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
            // Switch in the Morphe settings (Player screen). Merged by Morphe's settings patch;
            // without it the switch is absent and the default (on) is used.
            val screen = "<screen key=\"$MUSIC_PLAYER_SETTINGS_SCREEN\">\n$ALBUM_ART_SWITCH\n</screen>"
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

@Suppress("unused")
val dlnaCastPatch = bytecodePatch(
    name = "DLNA cast",
    description = "Adds DLNA / UPnP casting to the cast button. " +
        "Streams the current video or song to a DLNA media renderer on the local network. " +
        "Requires the official Morphe patches (with GmsCore support).",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(dlnaManifestPatch)

    // Runs after all patches: the official extension is merged by then if its patches are used.
    finalize {
        if (classDefByOrNull("Lapp/morphe/extension/shared/Utils;") == null) {
            throw PatchException("$REQUIRES_OFFICIAL_PATCHES (Morphe extension missing)")
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

        // The app hides and disables the cast button when no Google Cast device is on the
        // network. Keep it usable so DLNA devices can be reached without a Chromecast.
        val castButtonClass = CastButtonPerformClickFingerprint.classDef
        castButtonClass.methods.single { method ->
            method.name == "setVisibility" && method.parameterTypes.map { it.toString() } == listOf("I")
        }.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $CAST_CLASS->getCastButtonVisibility(I)I
                move-result p1
            """
        )
        if (castButtonClass.methods.none { it.name == "setEnabled" }) {
            castButtonClass.methods.add(
                ImmutableMethod(
                    castButtonClass.type,
                    "setEnabled",
                    listOf(ImmutableMethodParameter("Z", null, null)),
                    "V",
                    AccessFlags.PUBLIC.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-static { p1 }, $CAST_CLASS->getCastButtonEnabled(Z)Z
                            move-result p1
                            invoke-super { p0, p1 }, Landroid/view/View;->setEnabled(Z)V
                            return-void
                        """
                    )
                }
            )
        } else {
            throw PatchException("Cast button already overrides setEnabled")
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
    }
}
