package kyo.internal

import kyo.AllowUnsafe

/** Platform-specific accessors for the default `System.live` implementation. On JVM and Scala Native the standard Java stdlib populates the
  * environment and system properties via `java.lang.System`, so these simply delegate.
  */
private[kyo] object SystemPlatformSpecific:
    def env(name: String)(using AllowUnsafe): String      = java.lang.System.getenv(name)
    def property(name: String)(using AllowUnsafe): String = java.lang.System.getProperty(name)
end SystemPlatformSpecific
