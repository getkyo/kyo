package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:
    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = false
    inline def isJS                        = true
    inline def isNative: Boolean           = false
    inline def isDebugEnabled: Boolean     = false
    def exit(code: Int): Unit =
        scala.scalajs.js.Dynamic.global.process.exitCode = code
end Platform
