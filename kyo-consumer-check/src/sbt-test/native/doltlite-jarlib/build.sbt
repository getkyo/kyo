import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that gets the DoltLite engine from the library the published artifact carries.
// Without it the shim compiles stubs and opening a database reports the engine unavailable.
val NativeLib  = config("nativeLib").hide
val kyoVersion = sys.props("kyo.version")
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-sql-doltlite" % kyoVersion,
        ivyConfigurations += NativeLib,
        libraryDependencies += ("io.getkyo" % "kyo-sql-doltlite_3" % kyoVersion) % NativeLib,
        Compile / resourceGenerators += Def.task {
            val out = target.value / "kyo-ffi" / "natives" / hostOsArch
            IO.createDirectory(out)
            val ext     = if (hostOsArch.startsWith("darwin")) "dylib" else "so"
            val jar     = update.value.select(configurationFilter(NativeLib.name)).head
            val staging = IO.createTemporaryDirectory
            try
                IO.unzip(jar, staging, (_: String).endsWith(s"$hostOsArch/libkyo_doltlite.$ext"), preserveLastModified = true)
                val lib = (staging ** s"libkyo_doltlite.$ext").get.headOption
                    .getOrElse(sys.error(s"no libkyo_doltlite.$ext for $hostOsArch in $jar"))
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
                .withCompileOptions(base.compileOptions :+ "-DKYO_FFI_EXTERNAL_KYO_DOLTLITE")
                .withLinkingOptions(base.linkingOptions ++ Seq(s"-L$dir", "-lkyo_doltlite", rpath))
        },
        Compile / nativeLink := (Compile / nativeLink).dependsOn(Compile / resources).value
    )
