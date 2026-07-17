package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = true
    inline def isJS                        = false
    inline def isWasm: Boolean             = false
    inline def isNative: Boolean           = false

    inline def maxStackDepth: Int = 256

    val isDebugEnabled: Boolean =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .getInputArguments()
            .toString.contains("jdwp")
    def exit(code: Int): Unit = java.lang.System.exit(code)

    // OS detection
    private val osName: String = java.lang.System.getProperty("os.name", "").toLowerCase

    /** Classifies an already-lowercased `os.name` as macOS. Darwin is the macOS kernel name, so it classifies as mac rather than as a BSD,
      * matching the JS and Native definitions.
      */
    private[kyo] def isMacName(name: String): Boolean = name.contains("mac") || name.contains("darwin")

    /** Classifies an already-lowercased `os.name` as a BSD. The BSD JDK ports report "FreeBSD", "OpenBSD", and "NetBSD". */
    private[kyo] def isBsdName(name: String): Boolean = name.contains("bsd")

    val isWindows: Boolean  = osName.contains("windows")
    val isMac: Boolean      = isMacName(osName)
    val isLinux: Boolean    = osName.contains("linux")
    val isBsd: Boolean      = isBsdName(osName)
    val isMacOrBsd: Boolean = isMac || isBsd

    val fileSeparator: String = java.io.File.separator
    val pathSeparator: String = java.io.File.pathSeparator
    val lineSeparator: String = java.lang.System.lineSeparator()
end Platform
