package kyo.internal

import scala.concurrent.ExecutionContext

object Platform:

    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    val isJVM: Boolean                     = true
    inline val isJS                        = false
    val isNative: Boolean                  = false
    val isDebugEnabled: Boolean =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .getInputArguments()
            .toString.contains("jdwp")
    def exit(code: Int): Unit = java.lang.System.exit(code)
end Platform
