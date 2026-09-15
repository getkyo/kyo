package kyo.test.sbt

import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt._
import sbt.Keys._

/** Resolves kyo-test-browser for `kyoTestBrowserEnv` on Scala.js projects that enable [[SbtKyoTestPlugin]].
  *
  * The artifact is fetched when the environment is first used, not on every `update`: a project that never runs its tests in a browser
  * never downloads it. It is the JVM artifact, so it is resolved with the Scala 3 binary suffix alone, not the Scala.js one.
  */
object SbtKyoTestBrowserPlugin extends AutoPlugin {
    override def trigger  = allRequirements
    override def requires = SbtKyoTestPlugin && KyoTestJsPlugin && ScalaJSPlugin

    import KyoTestJsPlugin.autoImport._

    override def projectSettings: Seq[Setting[?]] = Seq(
        kyoTestBrowserClasspath := {
            val module = "io.getkyo" % "kyo-test-browser_3" % BuildInfo.kyoVersion
            dependencyResolution.value.retrieve(module, None, target.value / "kyo-test-browser", streams.value.log) match {
                case Right(files)  => files
                case Left(warning) => throw warning.resolveException
            }
        }
    )
}
