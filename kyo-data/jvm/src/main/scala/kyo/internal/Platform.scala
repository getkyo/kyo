package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = true
    inline def isJS                        = false
    inline def isWasm: Boolean             = false
    inline def isNative: Boolean           = false

    // Frames a synchronous chain runs before the kernel suspends through Safepoint to stay
    // stack-safe. 512 fits comfortably within the default JVM thread stack.
    inline def maxStackDepth: Int = 512

    val isDebugEnabled: Boolean =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .getInputArguments()
            .toString.contains("jdwp")
    def exit(code: Int): Unit = java.lang.System.exit(code)

    // OS detection
    val isWindows: Boolean = java.lang.System.getProperty("os.name", "").toLowerCase.contains("windows")
    val isMac: Boolean     = java.lang.System.getProperty("os.name", "").toLowerCase.contains("mac")
    val isLinux: Boolean   = java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    val fileSeparator: String = java.io.File.separator
    val pathSeparator: String = java.io.File.pathSeparator
    val lineSeparator: String = java.lang.System.lineSeparator()
end Platform
