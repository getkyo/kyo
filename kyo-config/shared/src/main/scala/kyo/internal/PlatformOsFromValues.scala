package kyo.internal

/** Derives [[Platform]]'s operating system and architecture flags from values read at run time.
  *
  * Mixed into the JVM and Scala.js facts, where one jar or one bundle runs on any machine. Scala Native declares the same flags itself,
  * resolved at link time.
  */
trait PlatformOsFromValues {
    def os: Platform.Os
    def arch: Platform.Arch

    final def isWindows: Boolean  = os eq Platform.Os.Windows
    final def isMac: Boolean      = os eq Platform.Os.MacOS
    final def isLinux: Boolean    = os eq Platform.Os.Linux
    final def isBsd: Boolean      = os eq Platform.Os.BSD
    final def isMacOrBsd: Boolean = isMac || isBsd
    final def isX86_64: Boolean   = arch eq Platform.Arch.X86_64
    final def isAarch64: Boolean  = arch eq Platform.Arch.Aarch64
}
