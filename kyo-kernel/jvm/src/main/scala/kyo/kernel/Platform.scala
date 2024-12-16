package kyo.kernel

import kyo.kernel.Mode
import scala.concurrent.ExecutionContext

object Platform:

    val mode: Mode =
        Option(System.getProperty("kyo.kernel.Platform.mode"))
            .map(Mode.valueOf).getOrElse(Mode.Development)

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
