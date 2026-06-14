import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Native-only end-to-end: Scala Native project that binds to a C lib. The
// NativeEmitter emits `@link("ne_lib")`, so the linker needs to find an
// actual `libne_lib` shared library, we pre-compile `ne_lib.c` into a
// shared library via a small sbt task and add its directory to
// nativeLinkingOptions ("-Lpath -lne_lib" is emitted automatically via
// @link). Keeping the C source under src/main/c/ (not resources/scala-native)
// so Scala Native does NOT also compile it into the binary (which would
// duplicate the `ne_add` symbols).
lazy val buildNeLib = taskKey[File]("Compile ne_lib.c into libne_lib.{dylib,so,dll} and return its directory.")

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin, ScalaNativePlugin)
    .settings(
        ffiLibraryId := "ne_lib",
        // Native: we do NOT use ffiCompile, it's a no-op on Native. Instead the
        // buildNeLib task (below) compiles the C source into a shared lib we point
        // the Scala Native linker at via nativeConfig.withLinkingOptions.
        ffiCSources  := Nil,
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % sys.props("kyo.version"),
        buildNeLib := {
            val log     = streams.value.log
            val cSrc    = baseDirectory.value / "src" / "main" / "c" / "ne_lib.c"
            val outDir  = target.value / "nativelib"
            IO.createDirectory(outDir)
            val osName  = sys.props.getOrElse("os.name", "").toLowerCase
            val (ext, flag) =
                if (osName.contains("mac")) ("dylib", "-dynamiclib")
                else if (osName.contains("win")) ("dll", "-shared")
                else ("so", "-shared")
            val outLib = outDir / s"libne_lib.$ext"
            if (!outLib.exists() || outLib.lastModified() < cSrc.lastModified()) {
                val cc  = sys.env.getOrElse("CC", "cc")
                val cmd = Seq(cc, flag, "-fPIC", "-o", outLib.getAbsolutePath, cSrc.getAbsolutePath)
                log.info(s"[ne_lib] ${cmd.mkString(" ")}")
                val rc = scala.sys.process.Process(cmd).!
                if (rc != 0) sys.error(s"cc failed with exit code $rc")
            }
            outDir
        },
        nativeConfig := {
            val base     = nativeConfig.value
            val libDir   = buildNeLib.value.getAbsolutePath
            // Extend the Scala Native linker command with -L and -rpath so it finds libne_lib at link-time and runtime.
            base
                .withMode(scala.scalanative.build.Mode.releaseFast)
                .withLinkingOptions(base.linkingOptions ++ Seq(s"-L$libDir", s"-Wl,-rpath,$libDir"))
        }
    )
