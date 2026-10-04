package kyo.test.sbt

import sbt._
import sbt.Keys._
import scala.scalanative.sbtplugin.ScalaNativePlugin

/** Auto-triggered companion that swaps the JVM framework for the Scala Native framework on any project where both [[KyoTestPlugin]] and
  * `ScalaNativePlugin` are enabled.
  */
object KyoTestNativePlugin extends AutoPlugin {
    override def trigger  = allRequirements
    override def requires = KyoTestPlugin && ScalaNativePlugin

    override def projectSettings: Seq[Setting[?]] = Seq(
        testFrameworks :=
            testFrameworks.value
                .filterNot(_.implClassNames.contains("kyo.test.runner.SbtFramework")) :+
                new TestFramework("kyo.test.runner.NativeFramework")
    ) ++ staleIrSettings

    /** Keeps `Compile` and `Test` free of Scala Native IR whose classes Zinc deleted (see [[StaleNirFileManager]]), and makes each
      * config's `clean` remove its IR. Every suite's reflective-instantiation module is linked, so a stale suite's IR fails the link on
      * whatever it referenced.
      *
      * Public for builds that wire kyo-test without this plugin, as kyo's own build does.
      */
    def staleIrSettings: Seq[Setting[?]] = inConfig(Compile)(staleIrConfigSettings) ++ inConfig(Test)(staleIrConfigSettings)

    private def staleIrConfigSettings: Seq[Setting[?]] = Seq(
        incOptions := {
            val options  = incOptions.value
            val hooks    = options.externalHooks
            val manager  = new StaleNirFileManager(classDirectory.value.toPath)
            val existing = hooks.getExternalClassFileManager
            val combined =
                if (existing.isPresent) xsbti.compile.WrappedClassFileManager.of(existing.get, java.util.Optional.of(manager))
                else manager
            options.withExternalHooks(hooks.withExternalClassFileManager(combined))
        },
        // A config's clean deletes only the products Zinc recorded, which never include `.nir`.
        clean := {
            clean.value
            IO.delete((classDirectory.value ** "*.nir").get)
        }
    )
}
