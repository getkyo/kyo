package kyo.internal

import kyo.AllowUnsafe
import scala.scalajs.js

/** Browser language detection for [[kyo.Locale.default]]: `navigator.language`, for example `"de-DE"`.
  *
  * Empty under Node, where there is no navigator and `kyo.System` supplies the environment instead.
  */
private[kyo] object LocalePlatformSpecific:

    def browserLanguageTag()(using AllowUnsafe): String =
        // The `typeof` guard must stay INLINE on the global selection: binding `js.Dynamic.global.navigator`
        // to a val first emits a bare `navigator` read, which throws ReferenceError where it is undeclared.
        if js.typeOf(js.Dynamic.global.navigator) == "undefined" then ""
        else
            val language = js.Dynamic.global.navigator.language
            if js.isUndefined(language) || language == null then "" else language.asInstanceOf[String]

end LocalePlatformSpecific
