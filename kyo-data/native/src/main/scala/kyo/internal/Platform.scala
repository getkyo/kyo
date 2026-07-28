package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = false
    inline def isJS: Boolean               = false
    inline def isWasm: Boolean             = false
    inline def isNative: Boolean           = true
    inline def isDebugEnabled: Boolean     = false

    inline def maxStackDepth: Int = 256

    def exit(code: Int): Unit = java.lang.System.exit(code)

    // OS detection
    val isWindows: Boolean = scala.scalanative.meta.LinktimeInfo.isWindows
    val isMac: Boolean     = scala.scalanative.meta.LinktimeInfo.isMac
    val isLinux: Boolean   = scala.scalanative.meta.LinktimeInfo.isLinux
    // Resolved at link time against the compilation target rather than by a runtime os.name read.
    // Darwin resolves as isMac above, never as a BSD.
    val isBsd: Boolean =
        scala.scalanative.meta.LinktimeInfo.isFreeBSD || scala.scalanative.meta.LinktimeInfo.isOpenBSD ||
            scala.scalanative.meta.LinktimeInfo.isNetBSD
    val isMacOrBsd: Boolean = isMac || isBsd

    val fileSeparator: String = java.io.File.separator
    val pathSeparator: String = java.io.File.pathSeparator
    val lineSeparator: String = java.lang.System.lineSeparator()
end Platform
