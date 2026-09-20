import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// The same TLS round trip as the net-plugin fixture, which reaches TLS through kyo-ffi-plugin and six hand-written
// lines of nativeConfig. Here the whole Native setup is one addSbtPlugin and one enablePlugins, and the two fixtures
// asserting the same round trip is what keeps that claim honest.
//
// kyo-net is the module that needs both halves at once: its BoringSSL shim sits over a library kyo publishes, and its
// io_uring shim sits over a library only the linking machine can supply. One plugin now answers both.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        // The artifact has to carry BoringSSL for this target, so a hole in the release fails the build here rather
        // than becoming a run-time surprise.
        kyoNativesSource := NativesSource.Jar,
        nativeConfig ~= (_.withBaseName("consumer")),
        // The probe's answer, written where the test can read it. Which system libraries resolve is a property of the
        // machine, so the test reads this rather than asserting a fixed set.
        TaskKey[Unit]("writeSystemLibraries") := {
            val resolved = kyoNativesSystemLibraries.value
            IO.write(
                baseDirectory.value / "system-libraries.txt",
                resolved.map(r => s"${r.id} ${r.linkFlags.mkString(" ")}").mkString("", "\n", "\n")
            )
        }
    )
