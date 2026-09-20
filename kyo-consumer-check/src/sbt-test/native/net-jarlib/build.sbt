import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that gets TLS from the BoringSSL shim library the published artifact carries,
// with no system OpenSSL and no kyo plugin. This is the wiring kyo-natives-plugin is meant to do.
val NativeLib  = config("nativeLib").hide
val kyoVersion = sys.props("kyo.version")
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % kyoVersion,
        // kyo-net keeps its natives in per-os-arch classifier jars, so the BoringSSL library comes from the
        // `<os-arch>-boringssl` classifier of the JVM artifact, resolved in its own configuration.
        ivyConfigurations += NativeLib,
        libraryDependencies += ("io.getkyo" % "kyo-net_3" % kyoVersion % NativeLib)
            .classifier(s"$hostOsArch-boringssl"),
        Compile / resourceGenerators += Def.task {
            val out = target.value / "kyo-ffi" / "natives" / hostOsArch
            IO.createDirectory(out)
            val ext     = if (hostOsArch.startsWith("darwin")) "dylib" else "so"
            val jar     = update.value.select(configurationFilter(NativeLib.name)).head
            val staging = IO.createTemporaryDirectory
            try
                IO.unzip(jar, staging, (_: String).endsWith(s"libkyonet_boringssl.$ext"), preserveLastModified = true)
                val lib = (staging ** s"libkyonet_boringssl.$ext").get.headOption
                    .getOrElse(sys.error(s"no libkyonet_boringssl.$ext for $hostOsArch in $jar"))
                IO.copyFile(lib, out / lib.getName)
                streams.value.log.info(s"[jarlib] ${lib.getName} from ${jar.getName}")
            finally IO.delete(staging)
            Nil
        }.taskValue,
        nativeConfig := {
            val base  = nativeConfig.value
            val dir   = (target.value / "kyo-ffi" / "natives" / hostOsArch).getAbsolutePath
            val rpath = if (hostOsArch.startsWith("darwin")) "-Wl,-rpath,@loader_path" else "-Wl,-rpath,$ORIGIN"
            base
                .withBaseName("consumer")
                .withCompileOptions(base.compileOptions :+ "-DKYO_FFI_EXTERNAL_KYONET_BORINGSSL")
                .withLinkingOptions(base.linkingOptions ++ Seq(s"-L$dir", "-lkyonet_boringssl", rpath))
        },
        Compile / nativeLink := (Compile / nativeLink).dependsOn(Compile / resources).value
    )
