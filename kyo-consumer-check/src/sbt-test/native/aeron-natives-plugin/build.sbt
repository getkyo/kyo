import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// The same application as the aeron-jarlib fixture, which wires the library out of the published artifact by hand.
// Here the wiring is one `enablePlugins`, and the two fixtures asserting the same round trip is what keeps the plugin
// honest about doing what the manual path does.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-aeron" % sys.props("kyo.version"),
        // The artifact has to carry the library for this target, so a hole in the release fails the build here rather
        // than becoming a run-time surprise.
        kyoNativesSource := NativesSource.Jar,
        nativeConfig ~= (_.withBaseName("consumer"))
    )
