package kyo.lang.plugin

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.plugins.StandardPlugin
import dotty.tools.dotc.report

/** The scalac plugin that compiles `.kyo` sources.
  *
  * A `.kyo` file is Scala syntax in which an effectful expression exposes its result directly, while the compiled definition keeps Kyo's
  * `A < S` contract. The plugin contributes two phases to an ordinary Scala 3 compilation and leaves the typer untouched:
  *
  *   - [[Preparation]] runs between the parser and the typer on `.kyo` compilation units only. It wraps every expression in a marker,
  *     wraps every definition body in the `kyo.lang.internal.Body` macro, attaches the `kyo.lang.Checked` metadata, and rejects the
  *     declarations the language does not admit.
  *   - [[CheckMetadata]] runs after the typer on every unit and rejects a `.scala` source that declares the `Checked` metadata by hand,
  *     so the metadata always attests a validated `.kyo` definition.
  *
  * The lowering itself happens in the `Body` macro during normal typing, through kyo-direct, which is why the compile classpath must
  * carry the `kyo-lang` library and kyo-direct next to this plugin.
  *
  * Load it with `-Xplugin:<kyo-lang-plugin jar>`; `-Xplugin-require:kyo-lang` makes a missing plugin a compile error.
  */
final class KyoLangPlugin extends StandardPlugin:
    val name: String        = "kyo-lang"
    val description: String = "Compiles .kyo sources: direct effect syntax over Kyo"

    override def initialize(options: List[String])(using Context): List[PluginPhase] =
        if options.nonEmpty then report.error("The kyo-lang plugin takes no options")
        List(new Preparation, new CheckMetadata)
end KyoLangPlugin
