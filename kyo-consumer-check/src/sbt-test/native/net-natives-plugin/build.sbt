import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// kyo-net is the module that needs both halves at once: its BoringSSL shim sits over a library kyo publishes, and its
// io_uring and OpenSSL shims sit over libraries only the linking machine can supply. This fixture is the claim that
// one addSbtPlugin and one enablePlugins covers both.
//
// The net-plugin fixture beside it reaches the same round trip through kyo-ffi-plugin and a hand-written nativeConfig.
// Keeping both is what keeps that path covered and what would catch the two diverging.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        // The artifact has to carry BoringSSL for this target, so a hole in the release fails the build here rather
        // than becoming a run-time surprise.
        kyoNativesSource := NativesSource.Jar,
        nativeConfig ~= (_.withBaseName("consumer")),
        // The probe's answer AND what reached nativeConfig, which is what the link actually reads. Which system
        // libraries resolve is a property of the machine, so the test compares the two rather than asserting a fixed
        // set: every id the probe resolved must have its define in compileOptions and its flags in linkingOptions,
        // which is false the moment the system half stops being folded in.
        TaskKey[Unit]("writeSystemLibraries") := {
            val resolved = kyoNativesSystemLibraries.value
            val config   = nativeConfig.value
            IO.write(
                baseDirectory.value / "system-libraries.txt",
                resolved.map(r => s"${r.id} ${r.linkFlags.mkString(" ")}").mkString("", "\n", "\n")
            )
            IO.write(
                baseDirectory.value / "native-config.txt",
                (config.compileOptions ++ config.linkingOptions).mkString("", "\n", "\n")
            )
        },
        TaskKey[Unit]("writeReleaseBinary") :=
            IO.write(baseDirectory.value / "release-binary.txt", (Compile / nativeLinkReleaseFast).value.getAbsolutePath)
    )
