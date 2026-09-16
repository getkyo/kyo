package kyo.internal

/** The JVM facts behind [[Platform]]. The operating system and architecture are read at run time, since one jar runs on any machine. */
abstract class PlatformSpecific extends PlatformStatic with PlatformOsFromValues {
    final val isJVM           = true
    final val isJS            = false
    final val isNative        = false
    final val isWasm          = false
    final val canSplitModules = false
    final val isNodeLike      = false
    final val isBrowser       = false

    val host: Platform.Host = Platform.Host.Jvm

    /** Whether a debugger agent is attached (a `jdwp` JVM argument). Lazy, so programs that never ask do not initialize JMX. */
    lazy val isDebugEnabled: Boolean =
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString.contains("jdwp")

    val os: Platform.Os     = Platform.Os.fromJavaName(java.lang.System.getProperty("os.name", ""))
    val arch: Platform.Arch = Platform.Arch.fromToken(java.lang.System.getProperty("os.arch", ""))

    /** Terminates the JVM with `code`. */
    def exit(code: Int): Unit = java.lang.System.exit(code)
}
