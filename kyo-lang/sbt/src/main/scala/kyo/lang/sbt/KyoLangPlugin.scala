package kyo.lang.sbt

import sbt._
import sbt.Keys._
import scala.io.Source

/** sbt integration of `.kyo` sources.
  *
  * Enabled with `.enablePlugins(KyoLangPlugin)`, the plugin:
  *
  *   - discovers `.kyo` files among the project's sources, in `Compile` and `Test`;
  *   - adds the `kyo-lang` library and the `kyo-lang-plugin` compiler plugin at `kyoLangVersion`, this plugin's own version by default,
  *     and requires the compiler plugin, so a `.kyo` source never compiles without it;
  *   - formats the `.kyo` sources with the language's pinned profile before each compilation (`kyoFormat`, on by default through
  *     `kyoFormatOnCompile`), or checks them in CI (`kyoFormatCheck`), so a `.kyo` source has one layout wherever it is compiled.
  *
  * Formatting happens before the compiler reads the sources and before Zinc stamps them, so an incremental compilation sees the
  * formatted file. The formatter is the one the plugin carries; a project's `.scalafmt.conf` does not apply to `.kyo` sources. The
  * compiler plugin alone, loaded by hand with `-Xplugin`, compiles `.kyo` sources but formats nothing.
  *
  * {{{
  * lazy val app = project.enablePlugins(KyoLangPlugin)
  * }}}
  */
object KyoLangPlugin extends AutoPlugin {

    override def trigger = noTrigger

    override def requires = sbt.plugins.JvmPlugin

    object autoImport {

        /** The kyo-lang version: the library and the compiler plugin `.kyo` sources compile with (default: this plugin's version). */
        val kyoLangVersion: SettingKey[String] =
            settingKey[String]("The kyo-lang version the project compiles .kyo sources with (default: this plugin's version).")

        /** The `.kyo` sources of a configuration. */
        val kyoSources: TaskKey[Seq[File]] = taskKey[Seq[File]]("The .kyo sources of the configuration.")

        /** Formats the `.kyo` sources in place. */
        val kyoFormat: TaskKey[Unit] = taskKey[Unit]("Formats the .kyo sources in place with the language's pinned profile.")

        /** Fails when a `.kyo` source is not in the language's format. */
        val kyoFormatCheck: TaskKey[Unit] = taskKey[Unit]("Fails if a .kyo source is not in the language's format.")

        /** Whether `compile` formats the `.kyo` sources first. */
        val kyoFormatOnCompile: SettingKey[Boolean] =
            settingKey[Boolean]("Whether compile formats the .kyo sources first (default: true).")
    }
    import autoImport._

    /** This plugin's version, baked into the jar at build time; the kyo-lang artifacts it resolves by default are released with it. */
    lazy val pluginVersion: String = {
        val stream = getClass.getResourceAsStream("/kyo-lang/version.txt")
        if (stream == null) sys.error("kyo-lang: version.txt is missing from the plugin")
        try Source.fromInputStream(stream, "UTF-8").mkString.trim
        finally stream.close()
    }

    override def projectSettings: Seq[Setting[?]] =
        Seq(
            kyoLangVersion     := pluginVersion,
            kyoFormatOnCompile := true,
            libraryDependencies ++= Seq(
                "io.getkyo" %% "kyo-lang" % kyoLangVersion.value,
                compilerPlugin("io.getkyo" %% "kyo-lang-plugin" % kyoLangVersion.value)
            ),
            scalacOptions += "-Xplugin-require:kyo-lang"
        ) ++ inConfig(Compile)(configurationSettings) ++ inConfig(Test)(configurationSettings)

    private def configurationSettings: Seq[Setting[?]] = Seq(
        unmanagedSources / includeFilter := (unmanagedSources / includeFilter).value || "*.kyo",
        kyoSources                       := unmanagedSources.value.filter(_.getName.endsWith(".kyo")),
        kyoFormat                        := Formatter.write(kyoSources.value, streams.value.log),
        kyoFormatCheck                   := Formatter.check(kyoSources.value, streams.value.log),
        compile                          :=
            compile
                .dependsOn(Def.taskDyn(if (kyoFormatOnCompile.value) kyoFormat else Def.task(())))
                .value
    )
}
