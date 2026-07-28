package kyo.internal

import scala.concurrent.ExecutionContext
import scala.scalajs.js

object Platform:
    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = false
    // WASM shares the JS execution model (single-threaded, Node runtime), so isJS stays true:
    // code that branches on isJS for single-threaded behavior must take the same path here.
    // isWasm is the additive flag for the cases where WASM genuinely diverges from JS.
    inline def isJS                    = true
    inline def isWasm: Boolean         = true
    inline def isNative: Boolean       = false
    inline def isDebugEnabled: Boolean = false

    // Frames a synchronous chain runs before the kernel suspends through Safepoint to stay
    // stack-safe. The Scala.js WebAssembly backend runs on a smaller call stack than the JS
    // backend, so it uses a lower bound than the JVM/JS default of 512.
    inline def maxStackDepth: Int = 256

    def exit(code: Int): Unit =
        scala.scalajs.js.Dynamic.global.process.exitCode = code

    // OS detection via Node.js
    private val nodePlatform: String =
        try js.Dynamic.global.process.platform.asInstanceOf[String]
        catch case _: Throwable => "unknown"

    val isWindows: Boolean = nodePlatform == "win32"
    val isMac: Boolean     = nodePlatform == "darwin"
    val isLinux: Boolean   = nodePlatform == "linux"
    // Node reports "freebsd" and "openbsd"; a NetBSD build reports "netbsd". Darwin is reported as
    // "darwin" and classifies as mac above, never as a BSD.
    val isBsd: Boolean      = nodePlatform.contains("bsd")
    val isMacOrBsd: Boolean = isMac || isBsd

    val fileSeparator: String = if isWindows then "\\" else "/"
    val pathSeparator: String = if isWindows then ";" else ":"
    val lineSeparator: String = if isWindows then "\r\n" else "\n"
end Platform
