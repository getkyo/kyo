import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// An application that depends on kyo-http and reaches kyo-net only through it. The delivery reads every module the
// dependency report holds, so BoringSSL is delivered for a transitive kyo-net exactly as for a direct one.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-http" % sys.props("kyo.version"),
        // The artifact has to carry BoringSSL for this target, so a hole in the release fails the build here.
        kyoNativesSource := NativesSource.Jar,
        nativeConfig ~= (_.withBaseName("consumer"))
    )
