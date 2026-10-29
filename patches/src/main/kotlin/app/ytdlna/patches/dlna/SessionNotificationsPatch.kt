package app.ytdlna.patches.dlna

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.ytdlna.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

private const val SESSION_WATCH = "Lapp/ytdlna/extension/SessionWatch;"
private const val JOB_SERVICE = "app.ytdlna.extension.SessionWatch\$Job"
private const val NETWORK_RECEIVER = "app.ytdlna.extension.SessionWatch\$NetworkReceiver"
private const val BOOT_RECEIVER = "app.ytdlna.extension.SessionWatch\$BootReceiver"
private const val REMOTE_SERVICE = "app.ytdlna.extension.RemoteControl\$Remote"
private const val REMOTE_RECEIVER = "app.ytdlna.extension.RemoteControl\$Receiver"

/** The job's own process: the app's start-up is skipped there. */
private const val PROCESS = ":ytdlna"

private val SESSION_PERMISSIONS = listOf(
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.POST_NOTIFICATIONS",
    // The search resumes after a reboot without opening the app.
    "android.permission.RECEIVE_BOOT_COMPLETED",
    // The remote control: a media session in the foreground while it controls a renderer.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
)

/** The app's Application class (from the manifest), for the bytecode part. */
private var applicationClass: String? = null

private val sessionManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val existing = doc.getElementsByTagName("uses-permission").let { list ->
                (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") }
            }
            SESSION_PERMISSIONS.filterNot(existing::contains).forEach { permission ->
                doc.documentElement.appendChild(
                    doc.createElement("uses-permission").apply { setAttribute("android:name", permission) }
                )
            }
            val application = doc.getElementsByTagName("application").item(0) as Element
            applicationClass = application.getAttribute("android:name").takeIf { it.isNotEmpty() }
            application.appendChild(
                doc.createElement("service").apply {
                    setAttribute("android:name", JOB_SERVICE)
                    setAttribute("android:process", PROCESS)
                    setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                    setAttribute("android:exported", "false")
                }
            )
            application.appendChild(
                doc.createElement("receiver").apply {
                    setAttribute("android:name", NETWORK_RECEIVER)
                    setAttribute("android:process", PROCESS)
                    setAttribute("android:exported", "false")
                }
            )
            application.appendChild(
                doc.createElement("service").apply {
                    setAttribute("android:name", REMOTE_SERVICE)
                    setAttribute("android:process", PROCESS)
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "mediaPlayback")
                }
            )
            application.appendChild(
                doc.createElement("receiver").apply {
                    setAttribute("android:name", REMOTE_RECEIVER)
                    setAttribute("android:process", PROCESS)
                    setAttribute("android:exported", "false")
                }
            )
            // Only the system sends these (protected broadcasts), so exported is safe.
            application.appendChild(
                doc.createElement("receiver").apply {
                    setAttribute("android:name", BOOT_RECEIVER)
                    setAttribute("android:process", PROCESS)
                    setAttribute("android:exported", "true")
                    appendChild(doc.createElement("intent-filter").apply {
                        listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")
                            .forEach { action ->
                                appendChild(doc.createElement("action").apply { setAttribute("android:name", action) })
                            }
                    })
                }
            )
        }
    }
}

@Suppress("unused")
val dlnaSessionNotificationsPatch = bytecodePatch(
    name = "DLNA session notifications",
    description = "Shows a notification when a DLNA renderer in the Wi-Fi plays a title cast with the " +
        "\"DLNA cast\" patch from any phone (\"Playing on <device>\"). Searches briefly when the phone " +
        "joins an unmetered network and about every 20 minutes on one, in a separate lightweight " +
        "process; nothing runs in between. Resumes after a reboot without opening the app. Its buttons " +
        "start a remote control (media session with lock screen controls and the renderer's volume on the " +
        "volume keys) that steps aside as soon as the phone plays something itself.",
    default = false
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(dlnaCastPatch, sessionManifestPatch)

    execute {
        val name = applicationClass ?: throw PatchException("No Application class in the manifest")
        // The classes up from the app class that do the app's start-up: in the job's own
        // process, attachBaseContext only sets the context and onCreate does nothing.
        var type: String? = "L" + name.replace('.', '/') + ";"
        var attachDone = false
        var createDone = false
        while (type != null && !(attachDone && createDone)) {
            val classDef = classDefByOrNull(type) ?: break
            val attach = classDef.methods.firstOrNull {
                it.name == "attachBaseContext" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;") &&
                    it.implementation != null
            }
            val create = classDef.methods.firstOrNull {
                it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null
            }
            if ((!attachDone && attach != null) || (!createDone && create != null)) {
                val mutable = mutableClassDefBy(classDef)
                if (!attachDone && attach != null) {
                    val method = mutable.methods.first { it.name == attach.name && it.parameterTypes == attach.parameterTypes }
                    requireLocal(method.implementation!!.registerCount, 2, "${classDef.type}.attachBaseContext")
                    method.addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $SESSION_WATCH->isOwnProcess()Z
                            move-result v0
                            if-eqz v0, :app_start
                            invoke-super { p0, p1 }, Landroid/content/ContextWrapper;->attachBaseContext(Landroid/content/Context;)V
                            return-void
                            :app_start
                            nop
                        """
                    )
                    attachDone = true
                }
                if (!createDone && create != null) {
                    val method = mutable.methods.first { it.name == "onCreate" && it.parameterTypes.isEmpty() }
                    requireLocal(method.implementation!!.registerCount, 1, "${classDef.type}.onCreate")
                    method.addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $SESSION_WATCH->isOwnProcess()Z
                            move-result v0
                            if-eqz v0, :app_start
                            return-void
                            :app_start
                            nop
                        """
                    )
                    createDone = true
                }
            }
            type = classDef.superclass
        }
        if (!createDone) throw PatchException("The app's onCreate was not found in $name or its superclasses")
    }
}

/** The early return needs v0 as a free local register (not a parameter). */
private fun requireLocal(registerCount: Int, parameterRegisters: Int, where: String) {
    if (registerCount - parameterRegisters < 1) throw PatchException("No free register in $where")
}
