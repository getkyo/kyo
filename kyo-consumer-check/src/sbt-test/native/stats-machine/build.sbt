import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that depends on kyo-stats-machine and registers its provider, as the module's README says
// a Native build must. The macOS shim compiles in this build and reads host metrics through libSystem only.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-stats-machine" % sys.props("kyo.version"),
        nativeConfig ~= {
            _.withBaseName("consumer")
                .withServiceProviders(Map("kyo.stats.internal.ExporterFactory" -> Seq("kyo.stats.machine.MachineStatFactory")))
        }
    )
