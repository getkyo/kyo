package kyo.internal

import kyo.AllowUnsafe

/** Browser language detection for [[kyo.Locale.default]].
  *
  * There is no browser on the JVM or Scala Native, so detection falls entirely to the `user.language` property
  * and the environment, which `kyo.System` reads for every platform.
  */
private[kyo] object LocalePlatformSpecific:

    def browserLanguageTag()(using AllowUnsafe): String = ""

end LocalePlatformSpecific
