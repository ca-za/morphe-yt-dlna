package app.ytdlna.patches.dlna

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

private const val CAPTURE_CLASS = "Lapp/ytdlna/extension/StreamCapture;"
private const val CAST_CLASS = "Lapp/ytdlna/extension/DlnaCast;"
private const val SHARE_ACTIVITY = "app.ytdlna.extension.DlnaShareActivity"

private val PERMISSIONS = listOf(
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.INTERNET",
)

private val NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

/** Loads a file bundled with the patches. */
private object PatchResources {
    fun open(path: String) = javaClass.getResourceAsStream(path)
}

private val dlnaManifestPatch = resourcePatch {
    execute {
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
        "Streams the current video or song to a DLNA media renderer on the local network.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE, COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(dlnaManifestPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // Capture the streams the player is about to use.
        // p1 = StreamingData proto, p2 = VideoDetails proto. Neither 'this' nor
        // locals are touched, so this is safe before the super constructor call.
        VideoStreamingDataConstructorFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p2 }, $CAPTURE_CLASS->onStreamingData(Ljava/lang/Object;Ljava/lang/Object;)V"
        )

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
        CastButtonPerformClickFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $CAST_CLASS->onCastButtonClick(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :original
                const/4 v0, 0x1
                return v0
                :original
                nop
            """
        )
    }
}
