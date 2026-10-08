package kyo.ffi.sbt

import java.io.File
import sbt._
import sbt.Keys._

/** Folds the flags a Scala Native project's FFI dependencies declare into its `nativeConfig`, so a consumer of kyo-net links without
  * writing the two `nativeConfig` lines kyo-net/README.md shows.
  *
  * It enables itself on every project that enables both [[KyoFfiPlugin]] and `ScalaNativePlugin`, and wires the configs the link tasks
  * read (`nativeLink`, `nativeLinkReleaseFast`, `nativeLinkReleaseFull`, in Compile and Test) rather than the project's `nativeConfig`.
  * Those are derived from the project's `nativeConfig`, so whatever the build wrote there, including the explicit form, is already in
  * them: a unit already present is not added again, and a guard is evaluated against the clang and compile options the link will use.
  *
  * sbt-scala-native is not a dependency of this plugin: a JVM-only or Scala.js build has none on its classpath, so every reference to it
  * stays in [[ScalaNativeWiring]], which loads only when it is present. Without it the plugin never triggers and adds no settings.
  */
object KyoFfiNativePlugin extends AutoPlugin {

    private lazy val scalaNativeLoaded: Boolean =
        try {
            Class.forName("scala.scalanative.sbtplugin.ScalaNativePlugin$", false, getClass.getClassLoader)
            true
        } catch {
            case _: ClassNotFoundException => false
            case _: LinkageError           => false
        }

    override def trigger: PluginTrigger = if (scalaNativeLoaded) allRequirements else noTrigger

    override def requires: Plugins = if (scalaNativeLoaded) KyoFfiPlugin && ScalaNativeWiring.plugin else KyoFfiPlugin

    override lazy val projectSettings: Seq[Setting[?]] = if (scalaNativeLoaded) ScalaNativeWiring.settings else Nil
}

private[sbt] object ScalaNativeWiring {
    import KyoFfiPlugin.autoImport._
    import scala.scalanative.build.NativeConfig
    import scala.scalanative.sbtplugin.ScalaNativePlugin
    import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

    def plugin: AutoPlugin = ScalaNativePlugin

    def settings: Seq[Setting[?]] = (KyoFfiPlugin.ffiNativeHeaderProbe := headerProbe((ThisBuild / nativeConfig).value)) +:
        Seq(Compile, Test).flatMap { config =>
            Seq[Setting[?]](
                wire(config / nativeLink / nativeConfig),
                wire(config / nativeLinkReleaseFast / nativeConfig),
                wire(config / nativeLinkReleaseFull / nativeConfig)
            )
        }

    private def wire(key: TaskKey[NativeConfig]): Setting[?] =
        key := {
            val base     = key.value
            val platform = ffiTargetPlatform.value
            val cp       = (Compile / dependencyClasspath).value.map(_.data)
            val targetOs = CCompiler.resolveTargetOsArch(ffiTargetOsArch.value)._1
            if (platform != "Native") base else withDependencyFlags(base, cp, targetOs)
        }

    private[sbt] def withDependencyFlags(base: NativeConfig, cp: Seq[File], targetOs: String): NativeConfig = {
        val compileUnits =
            KyoFfiPlugin.nativeFlagUnits(cp, KyoFfiPlugin.ffiNativeCompileFlagsDir, KyoFfiPlugin.ffiNativeInBuildCompileFlagsDir, targetOs)
        val withCompile = base.withCompileOptions(KyoFfiPlugin.appendMissingUnits(base.compileOptions, compileUnits))
        val linkUnits   = KyoFfiPlugin.nativeFlagUnits(
            cp,
            KyoFfiPlugin.ffiNativeLinkFlagsDir,
            KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir,
            targetOs,
            headerProbe(withCompile)
        )
        withCompile.withLinkingOptions(KyoFfiPlugin.appendMissingUnits(withCompile.linkingOptions, linkUnits))
    }

    private def headerProbe(config: NativeConfig): String => Boolean =
        KyoFfiPlugin.nativeHeaderProbe(
            config.clang.toAbsolutePath.toString,
            config.compileOptions ++ config.targetTriple.toSeq.flatMap(t => Seq("-target", t))
        )
}
