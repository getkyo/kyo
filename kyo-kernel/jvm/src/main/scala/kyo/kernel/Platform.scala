package kyo.kernel

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    val isJVM: Boolean                     = true
    val isJS: Boolean                      = false
    val isNative: Boolean                  = false
    val isDebugEnabled: Boolean =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .getInputArguments()
            .toString.contains("jdwp")
end Platform
