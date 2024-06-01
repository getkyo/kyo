package kyo.internal

object Platform:
    def executionContext = scala.concurrent.ExecutionContext.global
    def isJVM: Boolean   = true
    def isJS: Boolean    = false
    def isDebugEnabled: Boolean =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .getInputArguments()
            .toString.contains("jdwp")
end Platform
