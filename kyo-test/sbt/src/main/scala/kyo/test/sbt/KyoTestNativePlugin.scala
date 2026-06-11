package kyo.test.sbt

import sbt._
import sbt.Keys._
import scala.scalanative.sbtplugin.ScalaNativePlugin

/** Auto-triggered companion that swaps the JVM framework for the Scala Native framework on any project where both [[KyoTestPlugin]] and
  * [[ScalaNativePlugin]] are enabled.
  */
object KyoTestNativePlugin extends AutoPlugin {
    override def trigger  = allRequirements
    override def requires = KyoTestPlugin && ScalaNativePlugin

    override def projectSettings: Seq[Setting[_]] = Seq(
        testFrameworks := testFrameworks.value
            .filterNot(_.implClassNames.contains("kyo.test.runner.SbtFramework")) :+
            new TestFramework("kyo.test.runner.NativeFramework")
    )
}
