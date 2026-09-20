package kyo.internal

import kyo.AllowUnsafe

/** Platform-specific parts of the default `System.live` implementation. On the JVM and Scala Native the runtime sets the `user.name` system
  * property for the user running the process.
  */
private[kyo] object SystemPlatformSpecific:
    def userName()(using AllowUnsafe): String = HostConfig.property("user.name")

    /** Both runtimes report the count available to this process, so the runtime's own answer is the right one here. The Scala.js shim is the
      * one that has to look elsewhere, because Scala.js stubs this to 1 on every host.
      */
    def availableProcessors()(using AllowUnsafe): Int = Runtime.getRuntime.availableProcessors()
end SystemPlatformSpecific
