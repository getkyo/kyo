import sbtcrossproject.CrossPlugin.autoImport._
import scalajscrossproject.JSPlatform
import scalanativecrossproject.NativePlatform
import sbtcrossproject.CrossType
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val buildCpeLib = taskKey[File]("Compile cpe_lib.c into libcpe_lib.{dylib,so,dll} and return its directory.")

// Cross-project end-to-end. The same Scala source compiles for JVM, Native,
// and JS. Per-platform `Main` runs are gated by what the runtime currently
// supports, see test file for which assertions are exercised.
lazy val e2e = crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("e2e"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "cpe_lib",
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % sys.props("kyo.version")
    )
    .jvmSettings(
        // JVM uses the host C toolchain for the shared lib.
        ffiCSources := Seq((ThisBuild / baseDirectory).value / "src" / "main" / "c" / "cpe_lib.c"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / javaOptions     ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / fork := true
    )
    .nativeSettings(
        // The NativeEmitter emits `@link("cpe_lib")`, so the linker needs an
        // actual `libcpe_lib.{so,dylib,dll}`. Pre-compile cpe_lib.c into a
        // shared lib via `buildCpeLib` and surface its directory through
        // nativeConfig.withLinkingOptions. The C source lives under
        // src/main/c/ (NOT resources/scala-native/) so Scala Native does NOT
        // also compile it into the binary, which would duplicate the
        // `cpe_add` / `cpe_sub` / `cpe_mul_i64` symbols.
        ffiCSources := Nil,
        buildCpeLib := {
            val log    = streams.value.log
            val cSrc   = baseDirectory.value / "src" / "main" / "c" / "cpe_lib.c"
            val outDir = target.value / "nativelib"
            IO.createDirectory(outDir)
            val osName  = sys.props.getOrElse("os.name", "").toLowerCase
            val (ext, flag) =
                if (osName.contains("mac")) ("dylib", "-dynamiclib")
                else if (osName.contains("win")) ("dll", "-shared")
                else ("so", "-shared")
            val outLib = outDir / s"libcpe_lib.$ext"
            if (!outLib.exists() || outLib.lastModified() < cSrc.lastModified()) {
                val cc  = sys.env.getOrElse("CC", "cc")
                val cmd = Seq(cc, flag, "-fPIC", "-o", outLib.getAbsolutePath, cSrc.getAbsolutePath)
                log.info(s"[cpe_lib] ${cmd.mkString(" ")}")
                val rc = scala.sys.process.Process(cmd).!
                if (rc != 0) sys.error(s"cc failed with exit code $rc")
            }
            outDir
        },
        nativeConfig := {
            val base   = nativeConfig.value
            val libDir = buildCpeLib.value.getAbsolutePath
            base
                .withMode(scala.scalanative.build.Mode.releaseFast)
                .withLinkingOptions(base.linkingOptions ++ Seq(s"-L$libDir", s"-Wl,-rpath,$libDir"))
        }
    )
    .jsSettings(
        // JS uses the host C toolchain for the shared lib (loaded via koffi).
        ffiCSources := Seq((ThisBuild / baseDirectory).value / "src" / "main" / "c" / "cpe_lib.c"),
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
        scalaJSUseMainModuleInitializer := true
    )

lazy val e2eJVM    = e2e.jvm
lazy val e2eNative = e2e.native
lazy val e2eJS     = e2e.js
