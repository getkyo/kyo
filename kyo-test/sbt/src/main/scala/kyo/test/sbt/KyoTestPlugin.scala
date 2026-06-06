package kyo.test.sbt

import sbt._
import sbt.Keys._

/** sbt AutoPlugin that wires the kyo-test framework into a project's `testFrameworks`.
  *
  * The base `KyoTestPlugin` registers the JVM framework (`kyo.test.runner.SbtFramework`). When a project also has the Scala.js or Scala
  * Native plugin enabled, [[KyoTestJsPlugin]] / [[KyoTestNativePlugin]] auto-trigger and swap the JVM entry for the matching JS / Native
  * framework class.
  *
  * '''External users''' should enable [[SbtKyoTestPlugin]] instead. It extends this plugin and automatically adds
  * `"io.getkyo" %% "kyo-test-runner" % version % Test` to `libraryDependencies`, so no manual dep wiring is needed:
  * {{{
  * // project/plugins.sbt
  * addSbtPlugin("io.getkyo" % "sbt-kyo-test-publish" % "<version>")
  *
  * // build.sbt
  * lazy val myProject = project.enablePlugins(SbtKyoTestPlugin)
  * }}}
  *
  * '''Monorepo''' projects use `dependsOn` directly and enable this base plugin:
  * {{{
  * lazy val myLib = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  *   .enablePlugins(kyo.test.sbt.KyoTestPlugin)
  *   .dependsOn(`kyo-test-runner` % Test)
  * }}}
  *
  * This plugin does NOT add `kyo-test-runner` as a test dep; that is handled by [[SbtKyoTestPlugin]] for external consumers.
  */
object KyoTestPlugin extends AutoPlugin {
    override def trigger  = noTrigger
    override def requires = sbt.plugins.JvmPlugin

    override def projectSettings: Seq[Setting[_]] = Seq(
        testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")
    )
}
