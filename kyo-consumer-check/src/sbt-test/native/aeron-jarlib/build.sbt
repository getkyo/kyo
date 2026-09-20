import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that links the shim library the PUBLISHED JVM artifact carries, instead of
// compiling the shim from source and linking an Aeron archive it does not have. This is the wiring
// kyo-natives-plugin is meant to do for the application; written by hand here, it is both the proof that the
// mechanism works against real artifacts and the manual path for a build that wants no kyo plugin.
val NativeLib  = config("nativeLib").hide
val kyoVersion = sys.props("kyo.version")
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-aeron" % kyoVersion,
        // The Native classpath holds the Native artifact, which ships C source and no library. The library
        // lives in the JVM artifact for the same module and version, resolved here in its own configuration
        // so it never reaches the application's own classpath or its POM.
        ivyConfigurations += NativeLib,
        libraryDependencies += ("io.getkyo" % "kyo-aeron_3" % kyoVersion) % NativeLib,
        Compile / resourceGenerators += Def.task {
            val out = target.value / "kyo-ffi" / "natives" / hostOsArch
            IO.createDirectory(out)
            val ext = if (hostOsArch.startsWith("darwin")) "dylib" else "so"
            val jar     = update.value.select(configurationFilter(NativeLib.name)).head
            val staging = IO.createTemporaryDirectory
            try
                IO.unzip(jar, staging, (_: String).endsWith(s"$hostOsArch/libkyo_aeron.$ext"), preserveLastModified = true)
                val lib = (staging ** s"libkyo_aeron.$ext").get.headOption
                    .getOrElse(sys.error(s"no libkyo_aeron.$ext for $hostOsArch in $jar"))
                // Flat, because `-L` names this directory and `-l` expects `lib<id>.<ext>` directly in it.
                IO.copyFile(lib, out / lib.getName)
                streams.value.log.info(s"[jarlib] ${lib.getName} from ${jar.getName}")
            finally IO.delete(staging)
            Nil
        }.taskValue,
        nativeConfig := {
            val base = nativeConfig.value
            val dir  = (target.value / "kyo-ffi" / "natives" / hostOsArch).getAbsolutePath
            // @loader_path and $ORIGIN both mean "beside the binary", so the link records no build path and
            // the application deploys as a directory.
            val rpath = if (hostOsArch.startsWith("darwin")) "-Wl,-rpath,@loader_path" else "-Wl,-rpath,$ORIGIN"
            base
                .withBaseName("consumer")
                // The shim compiles to nothing: its entry points come from the linked library, and a stub
                // defined here would shadow them.
                .withCompileOptions(base.compileOptions :+ "-DKYO_FFI_EXTERNAL_KYO_AERON")
                .withLinkingOptions(base.linkingOptions ++ Seq(s"-L$dir", "-lkyo_aeron", rpath))
        },
        Compile / nativeLink := (Compile / nativeLink).dependsOn(Compile / resources).value
    )
