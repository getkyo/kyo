package kyo.test.sbt

import org.scalajs.jsenv.JSEnv
import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt._
import sbt.Keys._
import scala.util.Properties

/** Auto-triggered companion that swaps the JVM framework for the Scala.js framework on any project where both [[KyoTestPlugin]] and
  * `ScalaJSPlugin` are enabled, and offers an environment that runs the linked tests in Chrome.
  *
  * To run a configuration's tests in a browser, assign the environment to its `jsEnv`:
  * {{{
  * Test / jsEnv := kyoTestBrowserEnv.value
  * }}}
  * The environment starts kyo-test-browser, whose classpath `SbtKyoTestBrowserPlugin` resolves for projects that enable
  * `SbtKyoTestPlugin`. A build that wires kyo-test by hand adds [[browserSettings]] to its Scala.js projects and sets
  * `kyoTestBrowserClasspath` to that artifact's runtime classpath.
  */
object KyoTestJsPlugin extends AutoPlugin {
    override def trigger  = allRequirements
    override def requires = KyoTestPlugin && ScalaJSPlugin

    object autoImport {
        val kyoTestBrowserEnv: TaskKey[JSEnv] =
            taskKey[JSEnv](
                "A Scala.js environment that runs the linked tests in Chrome; assign it to jsEnv, e.g. Test / jsEnv := kyoTestBrowserEnv.value"
            )
        val kyoTestBrowserClasspath: TaskKey[Seq[File]] =
            taskKey[Seq[File]]("The runtime classpath of kyo-test-browser, the program kyoTestBrowserEnv starts for each run")
        val kyoTestChromeVersion: SettingKey[Option[String]] =
            settingKey[Option[String]]("The chrome-headless-shell version kyoTestBrowserEnv runs; None resolves the latest Stable release")
        val kyoTestBrowserJavaOptions: SettingKey[Seq[String]] =
            settingKey[Seq[String]]("JVM options for the kyo-test-browser process")
    }
    import autoImport._

    override def globalSettings: Seq[Setting[?]] = browserGlobalSettings

    /** The defaults of the browser keys. They live in Global so a setting at any narrower scope wins, whichever plugin ordering put it
      * there: a ThisBuild value, SbtKyoTestBrowserPlugin's resolved classpath, or a hand-wired build's own classpath.
      */
    val browserGlobalSettings: Seq[Setting[?]] = Seq(
        kyoTestChromeVersion      := None,
        kyoTestBrowserJavaOptions := Seq("-Xmx1g"),
        kyoTestBrowserClasspath   := {
            throw new MessageOnlyException(
                "kyoTestBrowserClasspath is not set: enable SbtKyoTestPlugin, which resolves kyo-test-browser, " +
                    "or set it to kyo-test-browser's runtime classpath"
            )
        }
    )

    override def projectSettings: Seq[Setting[?]] = Seq(
        testFrameworks :=
            testFrameworks.value
                .filterNot(_.implClassNames.contains("kyo.test.runner.SbtFramework")) :+
                new TestFramework("kyo.test.runner.JsFramework")
    ) ++ browserSettings

    /** The environment `kyoTestBrowserEnv` builds, for a Scala.js project that does not enable this plugin; add [[browserGlobalSettings]]
      * to the build's global settings with it.
      */
    val browserSettings: Seq[Setting[?]] = Seq(
        kyoTestBrowserEnv := {
            val executable = if (Properties.isWin) "java.exe" else "java"
            val home       = javaHome.value.getOrElse(file(sys.props("java.home")))
            new KyoTestBrowserJSEnv(
                (home / "bin" / executable).getAbsolutePath,
                kyoTestBrowserClasspath.value,
                kyoTestBrowserJavaOptions.value,
                kyoTestChromeVersion.value
            )
        }
    )
}
