package kyo.internal

import kyo.AllowUnsafe

/** Platform-specific accessors for the default `System.live` implementation. On JVM and Scala Native the standard Java stdlib populates
  * `os.name`, `user.name`, and `line.separator` via `java.lang.System` — simply delegate.
  */
private[kyo] object SystemPlatformSpecific:
    def env(name: String)(using AllowUnsafe): String      = java.lang.System.getenv(name)
    def property(name: String)(using AllowUnsafe): String = java.lang.System.getProperty(name)
    def osName()(using AllowUnsafe): String               = java.lang.System.getProperty("os.name", "")
    def osArch()(using AllowUnsafe): String               = java.lang.System.getProperty("os.arch", "")
end SystemPlatformSpecific
