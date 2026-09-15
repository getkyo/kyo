package kyo.internal

import kyo.AllowUnsafe

/** Platform-specific parts of the default `System.live` implementation. On the JVM and Scala Native the runtime sets the `user.name` system
  * property for the user running the process.
  */
private[kyo] object SystemPlatformSpecific:
    def userName()(using AllowUnsafe): String = HostConfig.property("user.name")
end SystemPlatformSpecific
