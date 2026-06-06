package kyo.test.sbt

import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt._
import sbt.Keys._

/** Auto-triggered companion that swaps the JVM framework for the Scala.js framework on any project where both [[KyoTestPlugin]] and
  * [[ScalaJSPlugin]] are enabled.
  */
object KyoTestJsPlugin extends AutoPlugin {
    override def trigger  = allRequirements
    override def requires = KyoTestPlugin && ScalaJSPlugin

    override def projectSettings: Seq[Setting[_]] = Seq(
        testFrameworks := testFrameworks.value
            .filterNot(_.implClassNames.contains("kyo.test.runner.SbtFramework")) :+
            new TestFramework("kyo.test.runner.JsFramework")
    )
}
