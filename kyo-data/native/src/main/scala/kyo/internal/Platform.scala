package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    inline def isJVM: Boolean              = false
    inline def isJS: Boolean               = false
    inline def isNative: Boolean           = true
    inline def isDebugEnabled: Boolean     = false
    def exit(code: Int): Unit              = java.lang.System.exit(code)
end Platform
