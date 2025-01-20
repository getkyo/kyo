package kyo.kernel

import scala.concurrent.ExecutionContext

object Platform:
    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    val isJVM: Boolean                     = false
    val isJS: Boolean                      = true
    val isNative: Boolean                  = false
    val isDebugEnabled: Boolean            = false
end Platform
