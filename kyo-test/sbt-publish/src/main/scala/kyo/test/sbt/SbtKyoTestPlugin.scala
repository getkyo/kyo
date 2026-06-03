package kyo.test.sbt

import sbt._
import sbt.Keys._

/** sbt AutoPlugin that wires kyo-test into external projects and automatically adds the matching `kyo-test-runner` dependency.
  *
  * External project usage:
  * {{{
  * // project/plugins.sbt
  * addSbtPlugin("io.getkyo" % "sbt-kyo-test-publish" % "<version>")
  *
  * // build.sbt
  * lazy val myProject = project
  *   .enablePlugins(SbtKyoTestPlugin)
  * }}}
  *
  * `SbtKyoTestPlugin` extends [[KyoTestPlugin]] and additionally injects
  * `"io.getkyo" %% "kyo-test-runner" % <version> % Test` into `libraryDependencies`,
  * so external consumers do not need a manual `dependsOn` or explicit runner dep.
  *
  * Monorepo projects continue to use `dependsOn(`kyo-test-runner` % Test)` directly
  * and are unaffected by this plugin.
  */
object SbtKyoTestPlugin extends AutoPlugin {
    override def trigger  = noTrigger
    override def requires = KyoTestPlugin

    override def projectSettings: Seq[Setting[?]] = Seq(
        libraryDependencies += "io.getkyo" %% "kyo-test-runner" % BuildInfo.kyoVersion % Test
    )
}
