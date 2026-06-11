package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = false
    inline def isJS: Boolean               = false
    inline def isWasm: Boolean             = false
    inline def isNative: Boolean           = true
    inline def isDebugEnabled: Boolean     = false

    // Frames a synchronous chain runs before the kernel suspends through Safepoint to stay
    // stack-safe. Native frames are larger than the JVM's, so it uses the lower 256 bound
    // (matching WASM) rather than 512.
    inline def maxStackDepth: Int = 256

    def exit(code: Int): Unit = java.lang.System.exit(code)

    // OS detection
    val isWindows: Boolean = scala.scalanative.meta.LinktimeInfo.isWindows
    val isMac: Boolean     = scala.scalanative.meta.LinktimeInfo.isMac
    val isLinux: Boolean   = scala.scalanative.meta.LinktimeInfo.isLinux

    val fileSeparator: String = java.io.File.separator
    val pathSeparator: String = java.io.File.pathSeparator
    val lineSeparator: String = java.lang.System.lineSeparator()
end Platform
