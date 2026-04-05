package kyo.internal

import scala.concurrent.ExecutionContext
import scala.scalajs.js

object Platform:
    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = false
    inline def isJS                        = true
    inline def isNative: Boolean           = false
    inline def isDebugEnabled: Boolean     = false
    def exit(code: Int): Unit =
        scala.scalajs.js.Dynamic.global.process.exitCode = code

    // OS detection via Node.js
    private val nodePlatform: String =
        try js.Dynamic.global.process.platform.asInstanceOf[String]
        catch case _: Throwable => "unknown"

    val isWindows: Boolean = nodePlatform == "win32"
    val isMac: Boolean     = nodePlatform == "darwin"
    val isLinux: Boolean   = nodePlatform == "linux"

    val fileSeparator: String = if isWindows then "\\" else "/"
    val pathSeparator: String = if isWindows then ";" else ":"
    val lineSeparator: String = if isWindows then "\r\n" else "\n"
end Platform
