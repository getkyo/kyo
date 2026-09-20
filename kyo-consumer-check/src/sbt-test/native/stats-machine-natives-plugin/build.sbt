import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// The stats-machine fixture beside this one, with the provider enlisted by the plugin instead of by hand. The two
// asserting the same sampled output is what keeps the plugin honest about doing what the manual line does.
//
// kyo-stats-machine delivers no library to Scala Native: its shim compiles in this build. So this fixture is also
// what proves the plugin is worth enabling for a module with no natives at all, which is the case the enlistment
// covers and the delivery does not.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-stats-machine" % sys.props("kyo.version"),
        // No withServiceProviders. Without the plugin's enlistment the link drops MachineStatFactory, the sampler
        // never starts, and the assertion below fails with no error of its own: that silence is the failure mode.
        nativeConfig ~= (_.withBaseName("consumer")),
        TaskKey[Unit]("writeServiceProviders") := {
            val enlisted = kyoNativesServiceProviders.value
            IO.write(
                baseDirectory.value / "service-providers.txt",
                enlisted.toSeq.sortBy(_._1).map { case (i, impls) => s"$i=${impls.mkString(",")}" }.mkString("", "\n", "\n")
            )
        }
    )
