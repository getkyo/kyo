package kyo

import kyo.ios._
import org.graalvm.polyglot.Value

object embeds {

  type Embeds = IOs

  extension (inline sc: StringContext) {
    inline def js(inline args: Any*): Value > Embeds =
      ${ EmbedMacro.evalLanguageImpl("js", 'sc, 'args) }
    inline def python(inline args: Any*): Any > Embeds =
      ${ EmbedMacro.evalLanguageImpl("python", 'sc, 'args) }
    inline def ruby(inline args: Any*): Value > Embeds =
      ${ EmbedMacro.evalLanguageImpl("ruby", 'sc, 'args) }
    inline def R(inline args: Any*): Value > Embeds =
      ${ EmbedMacro.evalLanguageImpl("R", 'sc, 'args) }
  }

  object Embeds {
    def run[T, S](v: T > (Embeds with S)): T > (IOs with S) =
      v
  }
}
