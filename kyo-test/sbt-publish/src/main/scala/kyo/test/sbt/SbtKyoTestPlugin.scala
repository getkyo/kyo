package kyo.test.sbt

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
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
  * `SbtKyoTestPlugin` extends [[KyoTestPlugin]] and additionally injects the matching `kyo-test-runner` into
  * `libraryDependencies`, so external consumers need no manual `dependsOn` or explicit runner dep.
  *
  * The dependency is re-crossed through `platformDepsCrossVersion` so each platform resolves its own artifact. A plain `%%` yields the JVM
  * jar everywhere; on JS and Native that still compiles, since the jar carries `.tasty`, but the framework class never reaches the linked
  * test binary and the run reports zero tests and exits successfully.
  *
  * Keep this injection here rather than in [[KyoTestJsPlugin]] / [[KyoTestNativePlugin]]: those auto-trigger on
  * `KyoTestPlugin && Scala{JS,Native}Plugin`, and `kyo-test-runner` enables `KyoTestPlugin` itself, so it would gain a dependency on its
  * own published artifact.
  *
  * Monorepo projects continue to use `dependsOn(`kyo-test-runner` % Test)` directly
  * and are unaffected by this plugin.
  */
object SbtKyoTestPlugin extends AutoPlugin {
    override def trigger  = noTrigger
    override def requires = KyoTestPlugin

    override def projectSettings: Seq[Setting[?]] = Seq(
        libraryDependencies += ("io.getkyo" %% "kyo-test-runner" % BuildInfo.kyoVersion % Test)
            .cross(platformDepsCrossVersion.value)
    )
}
