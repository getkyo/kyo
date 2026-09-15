package kyo.internal

import kyo.AllowUnsafe
import scala.scalajs.js

/** Browser language detection for [[kyo.Locale.default]]: `navigator.language`, for example `"de-DE"`.
  *
  * Empty on every host that is not a browser, where `kyo.System` supplies the environment instead. The check is on the host, not on the
  * presence of `navigator`: Node defines `navigator.language` too (since 21.2), from its ICU default rather than the process environment.
  */
private[kyo] object LocalePlatformSpecific:

    def browserLanguageTag()(using AllowUnsafe): String =
        if !Platform.isBrowser then ""
        else
            PlatformJs.jsGlobal("navigator").fold("") { navigator =>
                val language = navigator.language
                if js.typeOf(language) == "string" then language.asInstanceOf[String] else ""
            }

end LocalePlatformSpecific
