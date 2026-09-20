package kyo.natives.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for reading the platform off a project's auto-plugin labels.
  *
  * sbt builds a label as the plugin object's class name with the trailing `$` stripped, so the label of a top-level
  * plugin object is its fully qualified name. That is the form these assert.
  */
class PlatformTest extends AnyFunSuite with Matchers {

    test("the Scala Native label wins, in the form sbt reports it") {
        Platform.of(Set("scala.scalanative.sbtplugin.ScalaNativePlugin")) shouldBe Platform.Native
        Platform.of(Set("ScalaNativePlugin")) shouldBe Platform.Native
    }

    test("the Scala.js label yields JS") {
        Platform.of(Set("org.scalajs.sbtplugin.ScalaJSPlugin")) shouldBe Platform.Js
    }

    test("neither label yields JVM") {
        Platform.of(Set("sbt.plugins.JvmPlugin", "sbt.plugins.IvyPlugin")) shouldBe Platform.Jvm
        Platform.of(Set.empty) shouldBe Platform.Jvm
    }

    test("a plugin whose name merely ends in the same word is not mistaken for the platform's") {
        Platform.of(Set("com.example.MyScalaNativePluginHelper")) shouldBe Platform.Jvm
    }
}
