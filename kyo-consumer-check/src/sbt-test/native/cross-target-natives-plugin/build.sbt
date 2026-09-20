import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A build whose declared target and whose compiler disagree. kyoNativesTargets names a pole this host's compiler does
// not build for, so kyo-aeron's library is wanted for that pole and the binary would be this one's.
//
// Whether a release happens to carry a library for the named pole is not what makes this wrong, and the guard does not
// wait to find out: it fires on the declaration. A locally published snapshot carries only the host's library, so the
// fetch here finds nothing, and a guard keyed on what was fetched would pass this build and hand back a binary whose
// Aeron entry points are stubs.
//
// The disagreement is stated in `.settings`, exactly where an application states it, which is the point: an
// auto-plugin's settings are applied first, so a check reading `nativeConfig` while building `nativeConfig` sees the
// default and never fires.
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-aeron" % sys.props("kyo.version"),
        kyoNativesTargets := Seq(if (hostOsArch.startsWith("darwin")) "linux-x86_64" else "darwin-aarch64"),
        nativeConfig ~= (_.withBaseName("consumer"))
    )
