package kyo.kernel

import kyo.kernel.Mode
import scala.concurrent.ExecutionContext

object Platform:

    val mode: Mode =
        Option(System.getProperty("kyo.kernel.Platform.mode"))
            .map(Mode.valueOf).getOrElse(Mode.Development)

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    val isJVM: Boolean                     = false
    val isJS: Boolean                      = false
    val isNative: Boolean                  = true
    val isDebugEnabled: Boolean            = false
end Platform
