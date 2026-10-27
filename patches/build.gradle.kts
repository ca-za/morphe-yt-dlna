group = "app.ytdlna"

patches {
    about {
        name = "YT DLNA Patches"
        description = "DLNA casting for YouTube and YouTube Music"
        source = "git@github.com:ca-za/morphe-yt-dlna.git"
        author = "ca-za"
        contact = "na"
        website = "na"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

// The Rust stream proxy, cross-compiled for Android and bundled as a patch resource.
val nativeOutput = layout.buildDirectory.dir("generated/native")
val buildNativeProxy = tasks.register<Exec>("buildNativeProxy") {
    description = "Cross-compiles the Rust stream proxy for Android"
    val script = rootProject.file("native/build-android.sh")
    val outDir = nativeOutput.get().dir("ytdlna/native").asFile
    inputs.dir(rootProject.file("native/ytdlna-proxy/src"))
    inputs.files(
        rootProject.file("native/ytdlna-proxy/Cargo.toml"),
        rootProject.file("native/ytdlna-proxy/Cargo.lock"),
        script,
    )
    outputs.dir(outDir)
    commandLine(script.absolutePath, outDir.absolutePath)
}
// Built by buildNativeProxy: every task reading the resources (also the publishing sourcesJar)
// depends on it.
sourceSets["main"].resources.srcDir(files(nativeOutput).builtBy(buildNativeProxy))

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
